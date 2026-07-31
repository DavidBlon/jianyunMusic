# 每周推荐歌单 —— 设计文档（修订 v3）

日期：2026-07-31
状态：已并入 v3 全部修订 + 第 4~6 轮修订（缓存策略 / 缓存字段补全 / 水合规则 / 播放日志并发 / 单飞 key / domain 结果层 / 清理器职责拆分 / 单飞锁范围 / 周归属 / 去重边 / 序列化 / 防御性检查），待用户最终确认

## 1. 目标

在"我的"页面新增**每周推荐歌单**入口：根据**上一个完整自然周（上周一 00:00 → 本周一 00:00）的播放数据**，通过**相似歌曲聚合算法**生成 App 内虚拟歌单。本周首次打开页面时生成并缓存，每周一自动切换到新一周。

## 2. 已确认的关键决策

| 决策点 | 选择 |
|--------|------|
| 歌单形式 | **App 内虚拟歌单**（不写入网易云账号） |
| 算法主干 | **相似歌曲聚合** |
| 数据窗口 | **上一个完整自然周**（设备本地时区），本周首次打开时生成 |
| 架构 | **方案 A**：独立周播放日志 + 纯 Kotlin 算法 + **UseCase 编排**（算法不碰网络） |
| 有效播放 | `playedMs >= 30 秒` **或**（`durationMs > 0` 且 `>= 50%`，防除零）；跨会话重复播放累计，同一会话去重 |
| 播放日志并发 | Mutex 包住 read-modify-write，JSON 在 IO 线程；14 天内硬上限 **2000 条**；日志清理**内部自管理**（`pruneExpiredLocked`），不回调 Cleaner |
| 周归属 | 播放事件按 **`sessionStartedAt`**（会话开始时间）归属周，跨周播放归入开始所在周 |
| 周标识 | 用**周一 LocalDate**（如 `"2026-07-27"`）做缓存键，不用 `yyyy-Www` 字符串 |
| 单飞 | **仅 UseCase 一处**持锁（Mutex **只保护任务查找/创建**，await 在锁外；按 `GenerationKey(userId + displayWeekStart)` 区分，异 key **取消旧任务**），ViewModel 不重复防重 |
| 序列化 | 周缓存用 **kotlinx.serialization**（sealed interface + `resultType` 判别符 + `ignoreUnknownKeys`）；新增依赖（§11.7） |
| 并发与超时 | 相似接口并发 ≤ 4、单请求超时 8s |

## 3. 组件划分

均遵循项目现有惯例（`NeteaseApp.instance` 单例访问、分层清晰）。

| 组件 | 层 | 职责 |
|------|-----|------|
| `WeeklyPlayLog` | data | 记录**有效播放**事件，含会话标识，按用户区分，保留最近 14 天；Mutex 并发安全 + 2000 条硬上限；**内部** `pruneExpiredLocked(now)` 只清本日志，不回调 Cleaner |
| `WeeklyRecommendationAlgorithm` | 纯 Kotlin | **仅**两个纯函数：`selectSeeds` + `rankCandidates`；**零网络调用** |
| `GenerateWeeklyRecommendationUseCase` | domain | 编排全部 I/O；**唯一**的单飞控制点；返回 `WeeklyRecResult`（domain 结果，不返回 UI 状态） |
| `NeteaseApi.getSimilarSongs` | api | 新增"相似歌曲"接口；**实现前抓真实 JSON 确认是否含候选歌手信息**（两种预案见 §6.2） |
| `WeeklyRecommendationStore` | data | 缓存（`schemaVersion` + 周一日期键 + sealed 结果 + kotlinx.serialization），处理损坏/账号切换/跨年 |
| `WeeklyCacheCleaner` | data | **应用级事件**（打开推荐页 / 退出登录 / 账号切换 / App 启动）时统一清理；直接操作 Storage 或调 `pruneExpired()`，**不调用 `WeeklyPlayLog.read()`**（§11.5） |
| `MainViewModel.weeklyRecState` | viewmodel | 暴露 `sealed` UI 状态；将 `WeeklyRecResult` 映射为 `WeeklyRecUiState`（周数文案、错误提示在此层计算） |
| `WeeklyRecommendationRow` + `WeeklyRecommendationScreen` | ui | "我的"页入口卡片 + 歌曲列表页 |

## 4. 数据模型

```kotlin
// 有效播放事件（AppCache，key: "weekly_play_log:{userId}"）
// 写入门槛：playedMs >= 30_000 或（durationMs > 0 && playedMs/durationMs >= 0.5）（先达者，含等号）
// 跨会话重复播放每次记一条（累计播放次数）；同一播放会话（playbackSessionId 相同）只记一条
// 周数据过滤使用 sessionStartedAt：周日开始听的歌按会话开始时间归属上周，不被算到下一周
data class PlayEvent(
    val songId: Long,
    val playbackSessionId: String,   // 播放器每次开始新歌时生成；会话内重复回调去重依赖它
    val sessionStartedAt: Long,      // 会话开始时间——**周归属依据**
    val qualifiedAt: Long,           // 达到有效播放门槛的时间（仅调试/审计用，可不持久化）
    val playedMs: Long,              // 实际播放时长
    val durationMs: Long             // 歌曲总时长（未知时为 0，此时只看 playedMs 阈值）
)

// 周推荐缓存（key: "weekly_rec:{userId}"）
// 周一日期作为周键，天然规避 ISO 周跨年 / 普通年份混淆
data class WeeklyRecCache(
    val schemaVersion: Int,         // 缓存结构版本；升级后旧缓存作废
    val sourceWeekStart: String,    // "2026-07-20"（上周一，LocalDate ISO 字符串）
    val displayWeekStart: String,   // "2026-07-27"（本周一，LocalDate ISO 字符串）
    val result: WeeklyRecCacheResult,
    val generatedAt: Long
)

// 生成结果缓存：成功与"数据不足"都按周缓存；网络错误不缓存（保留重试）
// 缓存字段必须覆盖 UI 所需（seedCount / 统计），否则重启后命中缓存无法还原文案
sealed interface WeeklyRecCacheResult {
    data class Success(
        val songs: List<CachedSong>,
        val seedCount: Int               // UI "根据上周 N 首常听歌曲生成"
    ) : WeeklyRecCacheResult
    data class InsufficientData(
        val validPlayCount: Int,         // UI "上周听歌太少（不同歌曲 X 首）"
        val distinctSongCount: Int
    ) : WeeklyRecCacheResult
}

// 轻量缓存条目：足够渲染列表且可离线展示；需要播放时再转完整 Song（点播时才拉详情）
data class CachedSong(
    val songId: Long,
    val name: String,
    val artists: String,   // 歌手文本（如 "周杰伦"）
    val cover: String?     // 封面 URL
)

// domain 层结果（UseCase 返回值，不包含任何 UI 文案 / 周数标签）
sealed interface WeeklyRecResult {
    data class Success(
        val songs: List<CachedSong>,
        val seedCount: Int,
        val displayWeekStart: String     // ViewModel 据此算"第 N 周"
    ) : WeeklyRecResult
    data class InsufficientData(
        val validPlayCount: Int,
        val distinctSongCount: Int
    ) : WeeklyRecResult
    data class Failure(val message: String?) : WeeklyRecResult
}

// UI 状态：sealed，天然排除非法组合；数据不足携带统计信息
// ViewModel 将 WeeklyRecResult 映射到本层（周数文案、错误提示在 UI 层计算，不进入 domain）
sealed interface WeeklyRecUiState {
    data object Loading : WeeklyRecUiState
    data class Success(
        val songs: List<CachedSong>,   // 轻量字段，与缓存一致；点播时才批量拉详情
        val seedCount: Int,            // 实际用于生成种子的歌数（≤ 8）
        val displayWeekLabel: String   // 如 "第 31 周"（由 displayWeekStart 经 IsoFields 计算）
    ) : WeeklyRecUiState
    data class InsufficientData(
        val validPlayCount: Int,     // 数据周有效播放事件数
        val distinctSongCount: Int   // 数据周不同歌曲数
    ) : WeeklyRecUiState
    data class Error(val message: String?) : WeeklyRecUiState
}
```

## 5. 有效播放记录（WeeklyPlayLog）

- **写入条件**：播放进度首次跨过以下任一阈值（先达者，**含等号**，刚好 30 秒也计入）：
  ```kotlin
  val passedByDuration = playedMs >= 30_000
  val passedByProgress =
      durationMs > 0 &&                        // 防除零：durationMs 未知（0）时只看时长阈值
      playedMs.toDouble() / durationMs >= 0.5
  ```
- **周归属**：按 **`sessionStartedAt`**（会话开始时间）归属周，过滤用 `sessionStartedAt >= sourceStart && sessionStartedAt < sourceEnd`。例：周日 23:59:45 开始播放、周一 00:00:15 达到门槛 → 该事件归**上周**（开始所在周）；`qualifiedAt` 仅用于调试/审计，可不持久化。
- **会话标识**：播放器每次开始一首新歌时生成 `playbackSessionId`。记录前检查该会话是否已写入；**同一 `playbackSessionId` 只记一条**（去重播放器重复回调，且不依赖 ViewModel/页面状态——旋转、后台恢复、播放服务重连后会话标识仍由播放器层持有）。
- **跨会话累计**：不同会话对同一首歌的正常重复播放各自记一条，以累计播放次数。
- **保留期与清理时机（日志内部自管理，不调 Cleaner）**：仅保留最近 14 天，且在**实现中执行**，不是仅文档说明：
  ```kotlin
  class WeeklyPlayLog {
      private fun pruneExpiredLocked(now: Long) { /* 只删本日志 sessionStartedAt < now - 14d 的条目 */ }

      suspend fun record(event: PlayEvent) = mutex.withLock {
          pruneExpiredLocked(now)          // 先清旧，再去重、追加、限 2000 条、写回
      }

      suspend fun read(): List<PlayEvent> = mutex.withLock {
          pruneExpiredLocked(now)          // 先清旧，再返回
      }
  }
  ```
  - **不调用 `WeeklyCacheCleaner`**（避免 Cleaner 反过来调 `read()` 形成递归、以及每记一次歌都顺带检查周推荐缓存）；应用级完整清理由 Cleaner 负责（§11.5）。
- **并发保护**：`record()` 的"读取 → 清理 → 去重 → 追加 → 保存"整段（read-modify-write）用 `private val playLogMutex = Mutex()` 包住，避免两个播放器回调并发时互相覆盖丢事件；JSON 解析与写入运行在 `Dispatchers.IO`，不在主线程。
- **数量硬上限**：最近 14 天内最多保留 **2000** 条播放事件，写入超限时丢弃最旧条目（防异常回调导致数据量膨胀）。
- **未来演进**：若播放日志数据量持续增大，可迁移到 Room 或 DataStore；当前版本先以 Mutex + 硬上限保证正确性。

## 6. 推荐算法（纯 Kotlin，两个纯函数，均不触碰网络）

### 6.1 `selectSeeds(weekSongs, hydrated) → List<Seed>`

`weekSongs`：数据周每个不同歌曲的 `(songId, playCount, lastPlayedAt)`。
`hydrated`：由 UseCase 提供歌手信息后的歌曲集合。
**空输入 → 返回空 `List<Seed>`**（算法不产生 UI 状态；"数据不足"判定由 UseCase 负责）。

**初步打分（在获取歌手信息之前计算）**：

| 因子 | 公式 |
|------|------|
| 播放次数加权 | `playCountScore = log2(1 + playCount)` |
| 最近播放加权 | `recencyScore = (lastPlayedAt - sourceWeekStart) / (weekEnd - sourceWeekStart)`，截断 [0,1] |
| 初步总分 | `preliminaryScore = 0.7 * playCountScore + 0.3 * recencyScore` |

**时间边界（重要）**：周边界用 `sourceWeekStart.atStartOfDay(zoneId).toInstant()` / `displayWeekStart.atStartOfDay(zoneId).toInstant()` 计算，**不要用 `now - 7*24*60*60*1000`** 固定毫秒减（正确处理存在夏令时的地区）。

**水合与选择顺序（修正：先打分再水合，避免漏掉新鲜度高的歌）**：
1. 对数据周**全部不同歌曲**先算 `preliminaryScore`。
2. **水合规则（明确二选一）**：
   - 数据周不同歌曲 **≤ 100 首**：批量获取**全部**歌曲详情（每批 ≤ 50），再进入选择；
   - 数据周不同歌曲 **> 100 首**（异常情况）：先按 `preliminaryScore` 取 **Top 20** 获取详情，避免一次性拉取过多。
3. `selectSeeds` 按初步分降序贪心选择，**同一 `primaryArtistId` 最多 2 首**，共取 **Top 8**。
4. 返回 `List<Seed(songId, score, primaryArtistId)>`。

### 6.2 `rankCandidates(edges, listenedSongIds) → List<SongId>`

输入所有种子拉回的相似歌曲**边**（结构见下）与**数据周已听歌曲 id 集合**。

```kotlin
data class CandidateEdge(
    val seedSongId: Long,
    val candidateSongId: Long,
    val seedScore: Double,       // 该种子的初步分（用于归一化加权）
    val simRank: Int,            // 相似列表中位置，从 1 起
    val primaryArtistId: Long    // 候选歌曲歌手列表第一位
)
```

**候选歌手信息（实现前必须抓真实 JSON 核对字段，两种预案）**：
- 预案 A：`getSimilarSongs` 返回**含候选歌曲歌手列表** → 直接构建 `CandidateEdge(primaryArtistId = 歌手列表第一位)`，保持当前流程；
- 预案 B：接口**只返回精简数据**（ID / 名称，无歌手列表）→ 改为：
  1. 汇总所有种子的候选，最多取 **80 个候选 ID**；
  2. 候选 ID 去重；
  3. 批量 `getSongDetail` 水合候选歌手信息 → 得到 `primaryArtistId`；
  4. 构建 `CandidateEdge` → `rankCandidates`；
  5. 最终详情阶段复用这批水合结果或再拉取。
- **实现第一步是核对真实 JSON**，避免算法写完才发现字段不够（§3 的 NeteaseApi 职责也标注了此点）。

**候选打分（种子加权，归一化后按比例影响）**：

| 因子 | 公式 |
|------|------|
| 种子权重 | `seedWeight = seedScore / maxSeedScore`（对已选种子归一化，∈ (0,1]） |
| 单边贡献 | `contribution = seedWeight * (SIM_LIMIT - simRank + 1)`（SIM_LIMIT = 10） |
| 候选相似分 | `simScore = Σ contribution`（跨所有种子累加） |
| 共同推荐 | `coOccurrence` = 推荐该候选的**不同种子数**（`edges.map { it.seedSongId }.distinct().size`，去重边后统计）；`coScore = 3 * coOccurrence` |
| 候选总分 | `totalScore = simScore + coScore` |

**处理步骤**：
0. **过滤非法边**（入口防御，防接口脏数据）：剔除 `candidateSongId <= 0`、`simRank !in 1..10`、`primaryArtistId` 无效、`candidateSongId == seedSongId` 的边。
1. **去重边**：按 `seedSongId + candidateSongId` 组合 `distinctBy`，防止同一颗种子重复返回同一候选 → 虚增 `simScore` / 排名贡献 / 错误影响共同推荐。
2. 剔除 `listenedSongIds`（数据周已听歌曲）。
3. **歌手上限按 `primaryArtistId`**（歌曲歌手列表第一位）计算，合作歌曲只占用第一位歌手的配额，避免过度过滤。上限：优先 3 首，结果 < 30 时放宽到 **5 首**（**不再放宽到 10**；允许不足 30 时展示实际数量）。
4. 目标 30 首（软上限）；**不足 30 时返回实际数量，不凑数**。
5. **同分稳定排序**：`coOccurrence` 降序 → `simScore` 降序 → `Σ simRank` 升序 → `songId` 升序（确定性平局决胜）。

## 7. 生成编排（GenerateWeeklyRecommendationUseCase）

**单飞（仅此一处，携带实际 key）**：
```kotlin
data class GenerationKey(
    val userId: Long,
    val displayWeekStart: LocalDate
)

private val mutex = Mutex()
private var inFlightKey: GenerationKey? = null
private var inFlight: Deferred<WeeklyRecResult>? = null

suspend fun execute(key: GenerationKey): WeeklyRecResult {
    // Mutex 只保护"查找/创建任务"，绝不在锁内 await 网络请求（生成过程不占锁，后续请求可正常进入检查/复用）
    val task = mutex.withLock {
        val existing = inFlight
        when {
            existing != null && inFlightKey == key -> existing        // 同 key → 复用
            else -> {
                existing?.cancel()                                    // 异 key → 取消旧任务（另一用户/另一周的生成结果已无等待价值）
                scope.async { generate(key) }.also {
                    inFlightKey = key
                    inFlight = it
                }
            }
        }
    }
    return try {
        task.await()
    } finally {
        mutex.withLock {
            if (inFlight === task) {      // 防止旧任务结束误清掉新任务
                inFlight = null
                inFlightKey = null
            }
        }
    }
}
```
- 请求 key 与 `inFlightKey` **相同** → 复用正在执行的任务（返回同一 Deferred）。
- 请求 key **不同** → **取消旧任务**再创建新任务（不是"等待旧任务结束"；另一用户/另一周的结果已无等待价值）。
- 任务结束 → `finally` 中**仅当 `inFlight === task`** 才清除（旧任务结束不会误清新任务的 inFlight）。
- **ViewModel 不再实现第二套防重**，只调用并接收状态。

**流程**：
```
1. 确定当前周：displayWeekStart = 本周一 LocalDate；sourceWeekStart = 上周一 LocalDate
   （周标识一律用 LocalDate 字符串；UI 标签 "第 N 周" 才用 IsoFields.WEEK_OF_WEEK_BASED_YEAR；
     周边界用 atStartOfDay(zoneId).toInstant() 计算，不用固定毫秒，见 §6.1）
2. 读缓存：schemaVersion 匹配 && displayWeekStart == 本周 && **sourceWeekStart == 本周一.minusWeeks(1)** && JSON 可解析 → 转换为 WeeklyRecResult 直接返回（Success 或 InsufficientData）；
   **任一不满足（含 sourceWeekStart 不一致，防字段被破坏/旧版本不一致）或 JSON 解析失败 → 删除该缓存条目（不让无效数据残留），视为 miss**
3. 读 WeeklyPlayLog（读取前自动清理 14 天前旧数据，见 §5），按 **sessionStartedAt** ∈ [sourceStart, sourceEnd) 过滤（周归属以会话开始时间为准，见 §5）
4. distinctSongCount < 2 → 写缓存 InsufficientData(validPlayCount, distinctSongCount) → 返回 InsufficientData
5. 对数据周全部不同歌曲算 preliminaryScore → 水合歌手信息（≤ 100 首拉全部，> 100 首取 Top 20，见 §6.1；每批 ≤ 50）
6. selectSeeds → 多样化 Top 8 种子（同 primaryArtistId ≤ 2）
7. 对每颗种子调 getSimilarSongs（每颗取 10）：
   - 并发 ≤ 4（Semaphore），单请求超时 8s；部分种子失败 → 跳过继续
8. rankCandidates → 聚合、去重、剔除数据周已听、primaryArtistId 上限（3→5）、排序
9. 候选 = 0 → Failure（不缓存）；否则 getSongDetail 取最终详情：
   **全部失败 → Failure（不缓存）；部分失败 → 丢弃失败歌曲，保留成功歌曲继续** → 转 `CachedSong` 轻量字段
10. 写缓存前再次校验：
    currentUserId == generationUserId && currentDisplayWeekStart == generationDisplayWeekStart && coroutineContext.isActive
    不满足则放弃写入（防账号切换竞态回写旧账号缓存）
11. 写缓存（Success）：**覆盖同一 key `weekly_rec:{userId}`**（每用户仅一份，不按周新增 key）→ 返回 Success(songs, seedCount, displayWeekStart)
```

## 8. 更新机制（固定周一 · 用上周数据）

- **周键**：一律用**周一 LocalDate**（ISO 字符串）作为缓存键；跨年/年末归属由 `LocalDate` 天然保证，**不使用 `yyyy-Www` 字符串**，避免普通年份与周所属年份混淆。
- **UI 标签**：`displayWeekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)` 计算"第 N 周"。
- **触发**：本周（displayWeekStart）首次打开页面时生成；一周内反复打开命中缓存，零请求。
- **时区**：周边界统一用设备默认时区；换时区致周计算变化属可接受行为，缓存按 displayWeekStart 自然过期。

## 9. UI

### "我的"页面（MyScreen）

在"设置"下方、歌单列表上方插入"每周推荐"卡片，按 sealed 状态渲染：

| 状态 | 卡片展示 |
|------|---------|
| Loading | 卡片转圈 |
| Success | 封面 + "每周推荐" + "第 31 周 · 根据上周 **8 首常听歌曲**生成 · **26 首**"（8 = seedCount，26 = **实际**歌曲数，不固定写 30） |
| InsufficientData | "上周听歌太少（不同歌曲 X 首），多听几首下周再来"（不可点击） |
| Error | "生成失败，点击重试" |

### 歌曲列表页（WeeklyRecommendationScreen）

- 新增路由 `weekly`；歌曲列表复用歌单详情页行样式，遵循 `glassSurface` 玻璃拟态与 `miniPlayerSafeBottomPadding` 惯例。
- **点击播放流程（明确加载过程）**：
  1. **进入详情页时**在后台一次性水合整份歌单的完整 `Song`（期间列表先由 `CachedSong` 渲染、显示加载态），用户点击时即可**立即**播放；
  2. 若进入时水合失败：用户点击某首歌 → 显示短暂加载状态 → 批量获取整份歌单的完整 `Song` → 找到被点击歌曲的位置 → `setQueue(songs, startIndex)` 从该曲开始播放；
  3. 获取失败 → 提示用户（toast），列表展示不受影响（始终可由 `CachedSong` 先渲染）。

## 10. 错误处理

- 部分种子相似接口失败 → 跳过继续，有多少生成多少。
- 候选 = 0 或**全部**歌曲详情拉取失败 → `Failure`（**网络错误不缓存**，保留点击重试）。
- **部分歌曲详情失败 → 丢弃失败歌曲，剩余歌曲正常展示并缓存**（与"部分种子失败仍继续生成"原则一致）。
- 候选 > 0 但 < 30 → `Success`（UI 展示实际数量）。
- `InsufficientData`（数据周 `distinctSongCount < 2`，与事件条数无关）与 `Failure` 独立，不复用；ViewModel 分别映射为 UI 的 `InsufficientData` 与 `Error`。
- **数据不足按周缓存**（本周内不会再变化，避免每次打开都重算）。
- 缓存损坏 / schemaVersion 不匹配 / JSON 解析失败 → **删除该缓存条目**，视为 miss 重新生成（无效数据不留存）。
- 播放日志每次写入 / 读取时**内部**清理 14 天前数据（§5 的 `pruneExpiredLocked`，不依赖 Cleaner）。
- 账号切换 → 不同 userId 缓存 key 天然隔离；写缓存前再次校验身份（见 §7 步骤 10）。
- 生成中重复进入 → UseCase 单飞（Mutex 只保护任务查找，await 在锁外；同 key 复用、异 key 取消旧任务），不重复请求。

## 11. 缓存策略与清理

### 11.1 key 约定（每用户固定 key，不累积）

- `weekly_play_log:{userId}`：播放日志，**单 key**。
- `weekly_rec:{userId}`：周推荐缓存，**单 key，每用户仅一份**。生成新一周推荐时**直接覆盖旧缓存**，**不按周新增 key**，避免长期使用后缓存 key 无限增长。

### 11.2 播放日志：14 天清理（由 WeeklyPlayLog 内部自管理）

- `WeeklyPlayLog` 内部 `pruneExpiredLocked(now)` 在每次 `record()` 写入前、`read()` 读取前（均在 `mutex.withLock` 内）删除 `sessionStartedAt < now - 14d` 的旧条目。
- 保留期由实现保证，不只是文档约定；**不回调 Cleaner**（避免递归与多余 I/O）。

### 11.3 周推荐缓存：无效即删

- `schemaVersion` 不匹配、JSON 解析失败、字段缺失等**任何无效情况 → 删除该缓存条目**，视为 miss 重新生成，不让无效数据一直残留。

### 11.4 轻量缓存字段

- `Success` 缓存 `CachedSong(songId, name, artists, cover)` 轻量字段（足够渲染列表、可离线展示），外加 `seedCount`；`InsufficientData` 缓存 `validPlayCount` / `distinctSongCount` 统计——**都是 UI 还原文案所需的最小字段**。
- **不持久化完整 `Song` 大对象**；点播时才批量拉详情转完整 `Song`。

### 11.5 统一清理方法

```kotlin
// WeeklyCacheCleaner
fun cleanupWeeklyCache(userId: Long, now: Long)
```

职责：
1. 删除 14 天以前的播放日志条目（`weekly_play_log:{userId}`）——**直接操作底层 Storage 或调用 `WeeklyPlayLog.pruneExpired()`**，**绝不调用 `WeeklyPlayLog.read()`**（避免 read → cleaner → read 循环）；
2. 删除无效 / schemaVersion 过期 / 解析失败的周推荐缓存（`weekly_rec:{userId}`）；
3. 保证每个用户只存在一份周推荐结果（覆盖式写入 + 上面两条清理保证）。

**调用时机（应用级事件触发，不挂到每次 record/read）**：
- **打开推荐页面**：进入 MyScreen 触发一次完整清理；
- **退出登录**：通过 `AppCache.clearUserData()` 增加对两个 weekly key 的清理（该方法需接收当前 `userId`，移除 `weekly_play_log:{userId}` 与 `weekly_rec:{userId}`）；
- **账号切换**：切换时清理旧账号 weekly 数据（不同用户不同 key 天然隔离，无混用；旧账号在途生成任务由 UseCase 单飞随登录态取消，见 §7）；
- **App 启动维护**：启动时清理一次，兜底过期数据。
- 周推荐生成流程读缓存时命中无效缓存即删（§7 步骤 2，属 Store 自身职责，**不计入** Cleaner 调用点）。

### 11.6 存储占用说明

- 缓存只占手机**本地存储**（SharedPreferences），不长期占运行内存；定期清理 + 覆盖式写入保证长期使用后数据不持续累积。

### 11.7 序列化格式（明确 JSON 方案）

- 项目当前用 Gson（AppCache / Retrofit）；**Gson 无法原生序列化 sealed interface**（需手写 RuntimeTypeAdapter 判别符）。周缓存改用 **kotlinx.serialization**（新依赖：Kotlin `plugin.serialization` 插件 + `kotlinx-serialization-json`）。
- 显式配置：
  ```kotlin
  @Serializable
  sealed interface WeeklyRecCacheResult {
      @Serializable @SerialName("success") data class Success(...) : WeeklyRecCacheResult
      @Serializable @SerialName("insufficient_data") data class InsufficientData(...) : WeeklyRecCacheResult
  }

  val weeklyJson = Json {
      ignoreUnknownKeys = true          // 字段升级更稳，旧字段不炸
      classDiscriminator = "resultType" // sealed 结果类型判别符
  }
  ```
- 好处：结构升级时旧字段不炸；能正确区分 `Success` / `InsufficientData`。

## 12. 测试

- `WeeklyPlayLogTest`：
  - 有效播放门槛：`>= 30s` / `>= 50%`（含恰好 30 秒计入）
  - **`durationMs = 0` 时不除零**，只采用 30 秒规则
  - **跨会话重复播放可累计**（同歌多次播放 → playCount 递增）
  - **同一 playbackSessionId 重复回调去重**（只记一条）
  - 14 天清理（内部 `pruneExpiredLocked`，不依赖 Cleaner）
  - **并发 record() 不丢事件**（Mutex 包住 read-modify-write）
  - **2000 条硬上限**：超限丢弃最旧条目
  - **周日开始播放、周一达到有效门槛 → 按 sessionStartedAt 归属上周**
- `WeeklyRecommendationAlgorithmTest`（纯 JVM）：
  - `selectSeeds`：播放次数加权、最近播放加权、同 `primaryArtistId` ≤ 2、种子封顶 8、**空输入 → 空列表**（不产生 UI 状态）
  - `rankCandidates`：种子权重影响得分（高频种子贡献更大）、共同推荐加分、相似排名影响、剔除已听、`primaryArtistId` 上限 **3 → 5**（不含 10）、候选 < 30 返回实际数量、**所有候选都已听过 → 空**、**同分歌曲排序稳定**、合作歌曲只占第一位歌手配额
  - **非法边过滤**：`candidateSongId <= 0` / `simRank` 越界 / `primaryArtistId` 无效 / `candidateSongId == seedSongId` 被剔除
  - **重复边去重**：同 `seedSongId + candidateSongId` 不重复加分；`coOccurrence` 按**不同种子数**统计
- `GenerateWeeklyRecommendationUseCaseTest`：
  - **不同歌曲数 < 2（即使播放 20 次同一首）→ InsufficientData**
  - 部分种子接口失败 → 继续生成
  - 候选不足 30 → 实际数量
  - **部分歌曲详情失败 → 丢弃失败歌曲，剩余正常展示并缓存；全部失败 → Failure**
  - 生成期间重复进入（同 key）→ 复用同一任务，只生成一次
  - **不同 key（另一用户 / 另一周）→ 取消旧任务，再创建新任务**
  - **任务结束 finally 清除 inFlightKey / inFlight（仅当 `inFlight === task`；旧任务结束不清新任务）**
  - **Mutex 不长时间占用**：锁只保护任务查找/创建，await 在锁外（锁竞争微秒级）
  - 生成期间切换账号 → 在途取消且不写回旧账号缓存
  - **周边界用 atStartOfDay(zoneId) 而非固定毫秒（含夏令时用例）**
- `WeeklyRecommendationStoreTest`：
  - **周日 23:59 与周一 00:00 边界**
  - **ISO 周跨年（如 2025-12-29 周一 vs 2026-01-05 周一）**
  - schemaVersion 不匹配 → **删除旧缓存**并视为 miss
  - **sourceWeekStart 与 本周一.minusWeeks(1) 不一致 → 删除缓存视为 miss**
  - 缓存损坏 / 解析失败 → **删除该条目**并视为 miss
  - 账号切换 → 不同 key
  - InsufficientData 按周缓存命中
  - 生成新一周 → **覆盖同一 key**，不新增 key（每用户仅一份）
  - 缓存只保存轻量 `CachedSong` 字段（不含完整 Song 大对象）
  - **缓存 Success 含 seedCount、InsufficientData 含统计 → 重启后命中缓存可还原 UI 文案**
  - **缓存命中转换 WeeklyRecCacheResult → WeeklyRecResult 正确**
  - **sealed 结果序列化往返一致**：Success / InsufficientData 经 kotlinx.serialization（`resultType` 判别符 + `ignoreUnknownKeys`）序列化/反序列化正确
- `WeeklyCacheCleanerTest`：
  - `cleanupWeeklyCache` 删除 14 天前播放日志（**直接操作 Storage，不调用 `read()`**）
  - 删除无效 / 版本过期的周推荐缓存
  - 清理后每用户周推荐 key 恰好一份
  - 退出登录 → `clearUserData(userId)` 同时清除播放日志与周推荐缓存
  - 账号切换 / App 启动 → 触发点清理正确
  - **不产生递归**：Cleaner 清理日志时不会触发 `WeeklyPlayLog.read()`

## 13. 核心流程（最终）

```
播放达到有效条件后记录事件（>=30s 或 >=50% 且防除零，playbackSessionId 会话内去重、跨会话累计；WeeklyPlayLog 内部 prune 14 天前，不回调 Cleaner；Mutex 防并发丢事件，硬上限 2000 条）
→ 本周首次打开页面（触发 Cleaner 应用级清理）
→ 读取上一个完整自然周的播放记录（按 sessionStartedAt 归属；周边界用 atStartOfDay(zoneId).toInstant()，不用固定毫秒；周一日期作周键）
→ 先算初步分（次数 0.7 + 新鲜度 0.3）→ 水合歌手信息（≤100 首拉全部，>100 首取 Top 20）
→ 选择多样化 Top 8 种子（同歌手 ≤ 2）
→ 并发获取相似歌曲（≤ 4 并发、单请求超时；UseCase 按 GenerationKey 单飞：Mutex 只保护任务查找，await 在锁外，异 key 取消旧任务）
→ 候选过滤非法边 + 去重（seedSongId+candidateSongId）→ 种子加权聚合、剔除上周已听、primaryArtistId 上限（3→5）
→ 获取歌曲详情（部分失败丢弃，全部失败 Failure）→ 转轻量 CachedSong
→ 写缓存前校验身份（含 sourceWeekStart 一致）→ 覆盖式缓存（单 key、schemaVersion + 周一日期键 + sealed 结果 + kotlinx.serialization；无效缓存即删）
→ UseCase 返回 WeeklyRecResult → ViewModel 映射 UI 状态 → 展示实际生成数量
```

## 14. 明确不做（YAGNI）

- 不真实创建网易云歌单（不写入账号）
- 不做"本周最爱"独立区块
- 不做服务端/云同步推荐
- 不做手动"换一批"按钮

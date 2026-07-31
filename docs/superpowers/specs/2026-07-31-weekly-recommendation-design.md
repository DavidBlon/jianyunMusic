# 每周推荐歌单 —— 设计文档（修订 v3）

日期：2026-07-31
状态：已获用户批准（含 v3 全部修订意见）

## 1. 目标

在"我的"页面新增**每周推荐歌单**入口：根据**上一个完整自然周（上周一 00:00 → 本周一 00:00）的播放数据**，通过**相似歌曲聚合算法**生成 App 内虚拟歌单。本周首次打开页面时生成并缓存，每周一自动切换到新一周。

## 2. 已确认的关键决策

| 决策点 | 选择 |
|--------|------|
| 歌单形式 | **App 内虚拟歌单**（不写入网易云账号） |
| 算法主干 | **相似歌曲聚合** |
| 数据窗口 | **上一个完整自然周**（设备本地时区），本周首次打开时生成 |
| 架构 | **方案 A**：独立周播放日志 + 纯 Kotlin 算法 + **UseCase 编排**（算法不碰网络） |
| 有效播放 | `playedMs >= 30 秒` **或** `playedMs / durationMs >= 50%`；跨会话重复播放累计，同一会话去重 |
| 周标识 | 用**周一 LocalDate**（如 `"2026-07-27"`）做缓存键，不用 `yyyy-Www` 字符串 |
| 单飞 | **仅 UseCase 一处**持锁（Mutex + inFlight Deferred，按 `userId + displayWeek` 区分），ViewModel 不重复防重 |
| 并发与超时 | 相似接口并发 ≤ 4、单请求超时 8s |

## 3. 组件划分

均遵循项目现有惯例（`NeteaseApp.instance` 单例访问、分层清晰）。

| 组件 | 层 | 职责 |
|------|-----|------|
| `WeeklyPlayLog` | data | 记录**有效播放**事件，含会话标识，按用户区分，保留最近 14 天 |
| `WeeklyRecommendationAlgorithm` | 纯 Kotlin | **仅**两个纯函数：`selectSeeds` + `rankCandidates`；**零网络调用** |
| `GenerateWeeklyRecommendationUseCase` | domain | 编排全部 I/O；**唯一**的单飞控制点 |
| `NeteaseApi.getSimilarSongs` | api | 新增"相似歌曲"接口（真实路径实现时真机验证） |
| `WeeklyRecommendationStore` | data | 缓存（`schemaVersion` + 周一日期键 + sealed 结果），处理损坏/账号切换/跨年 |
| `MainViewModel.weeklyRecState` | viewmodel | 暴露 `sealed` UI 状态，仅调用 UseCase 并接收状态 |
| `WeeklyRecommendationRow` + `WeeklyRecommendationScreen` | ui | "我的"页入口卡片 + 歌曲列表页 |

## 4. 数据模型

```kotlin
// 有效播放事件（AppCache，key: "weekly_play_log:{userId}"）
// 写入门槛：playedMs >= 30_000 或 playedMs.toDouble() / durationMs >= 0.5（先达者，含等号）
// 跨会话重复播放每次记一条（累计播放次数）；同一播放会话（playbackSessionId 相同）只记一条
data class PlayEvent(
    val songId: Long,
    val playbackSessionId: String,  // 播放器每次开始新歌时生成；会话内重复回调去重依赖它
    val timestamp: Long,
    val playedMs: Long,             // 实际播放时长
    val durationMs: Long            // 歌曲总时长（未知时为 0，此时只看 playedMs 阈值）
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

// 生成结果：成功与"数据不足"都按周缓存；网络错误不缓存（保留重试）
sealed interface WeeklyRecCacheResult {
    data class Success(val songs: List<Song>) : WeeklyRecCacheResult
    data object InsufficientData : WeeklyRecCacheResult
}

// UI 状态：sealed，天然排除非法组合；数据不足携带统计信息
sealed interface WeeklyRecUiState {
    data object Loading : WeeklyRecUiState
    data class Success(
        val songs: List<Song>,
        val seedCount: Int,          // 实际用于生成种子的歌数（≤ 8）
        val displayWeekLabel: String // 如 "第 31 周"（由 displayWeekStart 经 IsoFields 计算）
    ) : WeeklyRecUiState
    data class InsufficientData(
        val validPlayCount: Int,     // 数据周有效播放事件数
        val distinctSongCount: Int   // 数据周不同歌曲数
    ) : WeeklyRecUiState
    data class Error(val message: String?) : WeeklyRecUiState
}
```

## 5. 有效播放记录（WeeklyPlayLog）

- **写入条件**：播放进度首次跨过 `playedMs >= 30 秒` **或** `playedMs / durationMs >= 50%`（先达者，**含等号**，刚好 30 秒也计入）。
- **会话标识**：播放器每次开始一首新歌时生成 `playbackSessionId`。记录前检查该会话是否已写入；**同一 `playbackSessionId` 只记一条**（去重播放器重复回调，且不依赖 ViewModel/页面状态——旋转、后台恢复、播放服务重连后会话标识仍由播放器层持有）。
- **跨会话累计**：不同会话对同一首歌的正常重复播放各自记一条，以累计播放次数。
- **保留期**：仅保留最近 14 天。

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

**水合与选择顺序（修正：先打分再水合，避免漏掉新鲜度高的歌）**：
1. 对数据周**全部不同歌曲**先算 `preliminaryScore`。
2. UseCase 按初步分取前 20 首获取歌手信息（数据周不同歌曲通常不多；若异常 > 100 首才退化为取前 20）。
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

**候选打分（种子加权，归一化后按比例影响）**：

| 因子 | 公式 |
|------|------|
| 种子权重 | `seedWeight = seedScore / maxSeedScore`（对已选种子归一化，∈ (0,1]） |
| 单边贡献 | `contribution = seedWeight * (SIM_LIMIT - simRank + 1)`（SIM_LIMIT = 10） |
| 候选相似分 | `simScore = Σ contribution`（跨所有种子累加） |
| 共同推荐 | `coOccurrence` = 推荐该候选的种子数；`coScore = 3 * coOccurrence` |
| 候选总分 | `totalScore = simScore + coScore` |

**处理步骤**：
1. 剔除 `listenedSongIds`（数据周已听歌曲）。
2. **歌手上限按 `primaryArtistId`**（歌曲歌手列表第一位）计算，合作歌曲只占用第一位歌手的配额，避免过度过滤。上限：优先 3 首，结果 < 30 时放宽到 **5 首**（**不再放宽到 10**；允许不足 30 时展示实际数量）。
3. 目标 30 首（软上限）；**不足 30 时返回实际数量，不凑数**。
4. **同分稳定排序**：`coOccurrence` 降序 → `simScore` 降序 → `Σ simRank` 升序 → `songId` 升序（确定性平局决胜）。

## 7. 生成编排（GenerateWeeklyRecommendationUseCase）

**单飞（仅此一处）**：
```kotlin
private val mutex = Mutex()
private var inFlight: Deferred<WeeklyRecUiState>? = null
```
- 按 `userId + displayWeekStart` 维度区分；命中同一任务则复用 Deferred。
- **ViewModel 不再实现第二套防重**，只调用并接收状态。

**流程**：
```
1. 确定当前周：displayWeekStart = 本周一 LocalDate；sourceWeekStart = 上周一 LocalDate
   （周标识一律用 LocalDate 字符串；UI 标签 "第 N 周" 才用 IsoFields.WEEK_OF_WEEK_BASED_YEAR）
2. 读缓存：schemaVersion 匹配 && displayWeekStart == 本周 && JSON 可解析 → 直接返回（Success 或 InsufficientData）
3. 读 WeeklyPlayLog，过滤 events ∈ [sourceWeekStart, sourceWeekEnd)
4. distinctSongCount < 2 → 写缓存 InsufficientData → 返回 InsufficientData(validPlayCount, distinctSongCount)
5. 对数据周全部不同歌曲算 preliminaryScore → 取前 20 批量 getSongDetail 水合歌手信息（每批 ≤ 50）
6. selectSeeds → 多样化 Top 8 种子（同 primaryArtistId ≤ 2）
7. 对每颗种子调 getSimilarSongs（每颗取 10）：
   - 并发 ≤ 4（Semaphore），单请求超时 8s；部分种子失败 → 跳过继续
8. rankCandidates → 聚合、去重、剔除数据周已听、primaryArtistId 上限（3→5）、排序
9. 候选 = 0 → Error；否则 getSongDetail 取最终详情
10. 写缓存前再次校验：
    currentUserId == generationUserId && currentDisplayWeekStart == generationDisplayWeekStart && coroutineContext.isActive
    不满足则放弃写入（防账号切换竞态回写旧账号缓存）
11. 写缓存（Success）→ 返回 Success(songs, seedCount, label)
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

- 新增路由 `weekly`；歌曲列表复用歌单详情页行样式，支持点击播放（`setQueue`）。
- 遵循 `glassSurface` 玻璃拟态与 `miniPlayerSafeBottomPadding` 惯例。

## 10. 错误处理

- 部分种子相似接口失败 → 跳过继续，有多少生成多少。
- 候选 = 0 或详情拉取失败 → `Error`（**网络错误不缓存**，保留点击重试）。
- 候选 > 0 但 < 30 → `Success` 展示实际数量。
- `InsufficientData`（数据周 `distinctSongCount < 2`，与事件条数无关）与 `Error` 独立，不复用。
- **数据不足按周缓存**（本周内不会再变化，避免每次打开都重算）。
- 缓存损坏 / schemaVersion 不匹配 → 视为 miss，重新生成。
- 账号切换 → 不同 userId 缓存 key 天然隔离；写缓存前再次校验身份（见 §7 步骤 10）。
- 生成中重复进入 → UseCase 单飞（Mutex + inFlight），不重复请求。

## 11. 缓存与账号切换

- key：`weekly_rec:{userId}`、`weekly_play_log:{userId}`。
- `WeeklyRecCache` 含 `schemaVersion` + `sourceWeekStart` + `displayWeekStart` + sealed `result`。
- 常量 `schemaVersion`；升级后旧缓存作废。
- 登出 → `AppCache.clearUserData()` 同时清理两个 key。

## 12. 测试

- `WeeklyPlayLogTest`：
  - 有效播放门槛：`>= 30s` / `>= 50%`（含恰好 30 秒计入）
  - **跨会话重复播放可累计**（同歌多次播放 → playCount 递增）
  - **同一 playbackSessionId 重复回调去重**（只记一条）
  - 14 天清理
- `WeeklyRecommendationAlgorithmTest`（纯 JVM）：
  - `selectSeeds`：播放次数加权、最近播放加权、同 `primaryArtistId` ≤ 2、种子封顶 8、**空输入 → 空列表**（不产生 UI 状态）
  - `rankCandidates`：种子权重影响得分（高频种子贡献更大）、共同推荐加分、相似排名影响、剔除已听、`primaryArtistId` 上限 **3 → 5**（不含 10）、候选 < 30 返回实际数量、**所有候选都已听过 → 空**、**同分歌曲排序稳定**、合作歌曲只占第一位歌手配额
- `GenerateWeeklyRecommendationUseCaseTest`：
  - **不同歌曲数 < 2（即使播放 20 次同一首）→ InsufficientData**
  - 部分种子接口失败 → 继续生成
  - 候选不足 30 → 实际数量
  - 生成期间重复进入 → 复用同一任务，只生成一次
  - 生成期间切换账号 → 在途取消且不写回旧账号缓存
- `WeeklyRecommendationStoreTest`：
  - **周日 23:59 与周一 00:00 边界**
  - **ISO 周跨年（如 2025-12-29 周一 vs 2026-01-05 周一）**
  - schemaVersion 不匹配 → 失效
  - 缓存损坏 → 视为 miss
  - 账号切换 → 不同 key
  - InsufficientData 按周缓存命中

## 13. 核心流程（最终）

```
播放达到有效条件后记录事件（>=30s 或 >=50%，playbackSessionId 会话内去重、跨会话累计）
→ 本周首次打开页面
→ 读取上一个完整自然周的播放记录（上周一 00:00 → 本周一 00:00，用周一日期作周键）
→ 先算初步分（次数 0.7 + 新鲜度 0.3）→ 批量水合歌手信息
→ 选择多样化 Top 8 种子（同歌手 ≤ 2）
→ 并发获取相似歌曲（≤ 4 并发、单请求超时、UseCase 单飞防重）
→ 种子加权聚合、去重、剔除上周已听歌曲、primaryArtistId 上限（3→5）
→ 获取歌曲详情
→ 写缓存前校验身份 → 缓存（schemaVersion + 周一日期键 + sealed 结果）
→ 展示实际生成数量
```

## 14. 明确不做（YAGNI）

- 不真实创建网易云歌单（不写入账号）
- 不做"本周最爱"独立区块
- 不做服务端/云同步推荐
- 不做手动"换一批"按钮

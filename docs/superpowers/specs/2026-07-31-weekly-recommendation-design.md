# 每周推荐歌单 —— 设计文档（修订 v3）

日期：2026-07-31
状态：已并入 v3 + 第 4~8 轮修订（缓存策略 / 缓存字段补全 / 水合规则 / 单飞 key+scope+invokeOnCompletion 清理 / domain 结果层 / 清理器职责与锁边界 / 周归属 / 去重边 / 序列化 / 防御性检查 / 日志改 Room / 账号切换保留数据 / Double 公式 / 详情乱序恢复 / 前置验证 / SQL 聚合 / 每用户 2000 上限 / 自然日 cutoff / suspend 清理与退出登录 / 精简事件字段），待用户最终确认

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
| 播放日志存储 | **Room（SQLite）**：唯一索引 `(userId, playbackSessionId)` 会话去重；SQL 删 14 个本地自然日前（ZoneId cutoff）；**SQL 聚合**出上周 `(songId, playCount, lastPlayedAt)`；每用户 2000 上限（§5、§11.2） |
| 进程模型 | 播放服务与 App **同进程**（Manifest 无 `android:process`，已核实），无跨进程写覆盖风险 |
| 周归属 | 播放事件按 **`sessionStartedAt`**（会话开始时间）归属周，跨周播放归入开始所在周 |
| 周标识 | 用**周一 LocalDate**（如 `"2026-07-27"`）做缓存键，不用 `yyyy-Www` 字符串 |
| 单飞 | **仅 UseCase 一处**持锁（Mutex **只保护任务查找/创建**，await 在锁外；**应用级注入 Scope**；按 `GenerationKey(userId + displayWeekStart)` 区分，异 key **取消旧任务**；单飞引用由 Deferred `invokeOnCompletion` 在任务**真正完成**时清理，**调用方取消不清**），ViewModel 不重复防重 |
| 序列化 | 周推荐缓存用 **kotlinx.serialization**（sealed interface + `resultType` 判别符 + `ignoreUnknownKeys`）；播放日志走 Room **无需序列化**（§11.7） |
| 账号切换 | **保留旧账号本地数据**（缓存按 userId 隔离），仅取消旧账号在途任务；**明确退出登录**才按隐私策略清除 |
| 并发与超时 | 相似接口并发 ≤ 4、单请求超时 8s |

## 3. 组件划分

均遵循项目现有惯例（`NeteaseApp.instance` 单例访问、分层清晰）。

| 组件 | 层 | 职责 |
|------|-----|------|
| `WeeklyPlayLog` | data | **Room** 记录有效播放事件（含 userId、会话标识）；唯一索引会话去重、SQL 清 14 天前、范围查上周；公开 `pruneExpired(userId, now)`（内部走 DAO）供 Cleaner 调用 |
| `WeeklyRecommendationAlgorithm` | 纯 Kotlin | **仅**两个纯函数：`selectSeeds` + `rankCandidates`；**零网络调用** |
| `GenerateWeeklyRecommendationUseCase` | domain | 编排全部 I/O；**唯一**的单飞控制点（应用级 Scope）；返回 `WeeklyRecResult`（domain 结果，不返回 UI 状态） |
| `NeteaseApi.getSimilarSongs` | api | 新增"相似歌曲"接口；**开发前置：先抓真实 JSON 确认是否含候选歌手信息**（两种预案见 §6.2） |
| `WeeklyRecommendationStore` | data | 周推荐缓存（SharedPreferences + kotlinx.serialization：`schemaVersion` + 周一日期键 + sealed 结果），处理损坏/跨年；与播放日志存储**分离封装** |
| `WeeklyCacheCleaner` | data | **应用级事件**（打开推荐页 / 退出登录 / App 启动）时统一清理；日志侧**只能经 `WeeklyPlayLog.pruneExpired()`（内部 Room DAO）**，绝不绕过（§11.5） |
| `MainViewModel.weeklyRecState` | viewmodel | 暴露 `sealed` UI 状态；将 `WeeklyRecResult` 映射为 `WeeklyRecUiState`（周数文案、错误提示在此层计算） |
| `WeeklyRecommendationRow` + `WeeklyRecommendationScreen` | ui | "我的"页入口卡片 + 歌曲列表页 |

## 4. 数据模型

```kotlin
// 有效播放事件 —— **Room @Entity**（不存 JSON，无需序列化）
// 唯一索引 (userId, playbackSessionId)：同一播放会话只允许一行（INSERT OR IGNORE 去重）
// 周数据过滤使用 sessionStartedAt：周日开始听的歌按会话开始时间归属上周，不被算到下一周
// qualifiedAt 不持久化（判定只发生在写入路径，推荐算法不需要它；要排查播放统计再经 schemaVersion 迁移加回）
@Entity(
    tableName = "weekly_play_event",
    indices = [
        Index(value = ["userId", "sessionStartedAt"]),
        Index(value = ["userId", "playbackSessionId"], unique = true)
    ]
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val songId: Long,
    val playbackSessionId: String,   // 播放器每次开始新歌时生成；会话内重复回调去重依赖它
    val sessionStartedAt: Long       // 会话开始时间——**周归属依据**（recency 的 lastPlayedAt 也用它）
    // 不存 playedMs / durationMs：合格判定由播放层完成（见 §5），落库的已是合格事件
)

// 周推荐缓存（key: "weekly_rec:{userId}"）——**所有持久化类都标注 @Serializable**（§11.7）
// 周一日期作为周键，天然规避 ISO 周跨年 / 普通年份混淆
@Serializable
data class WeeklyRecCache(
    val schemaVersion: Int,         // 缓存结构版本；升级后旧缓存作废
    val sourceWeekStart: String,    // "2026-07-20"（上周一，LocalDate ISO 字符串）
    val displayWeekStart: String,   // "2026-07-27"（本周一，LocalDate ISO 字符串）
    val result: WeeklyRecCacheResult,
    val generatedAt: Long
)

// 生成结果缓存：成功与"数据不足"都按周缓存；网络错误不缓存（保留重试）
// 缓存字段必须覆盖 UI 所需（seedCount / 统计），否则重启后命中缓存无法还原文案
@Serializable
sealed interface WeeklyRecCacheResult {
    @Serializable @SerialName("success")
    data class Success(
        val songs: List<CachedSong>,
        val seedCount: Int               // UI "根据上周 N 首常听歌曲生成"
    ) : WeeklyRecCacheResult

    @Serializable @SerialName("insufficient_data")
    data class InsufficientData(
        val validPlayCount: Int,         // UI "上周听歌太少（不同歌曲 X 首）"
        val distinctSongCount: Int
    ) : WeeklyRecCacheResult
}

// 轻量缓存条目：足够渲染列表且可离线展示；需要播放时再转完整 Song（点播时才拉详情）
@Serializable
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

## 5. 有效播放记录（WeeklyPlayLog，Room/SQLite）

- **写入条件**：播放进度首次跨过以下任一阈值（先达者，**含等号**，刚好 30 秒也计入）；**合格判定由播放层完成**（播放层持有实时 `playedMs` / `durationMs`），判定通过后向 WeeklyPlayLog 落库**已合格事件**；`qualifiedAt` 不持久化（推荐算法只需要 `sessionStartedAt`）：
  ```kotlin
  val passedByDuration = playedMs >= 30_000
  val passedByProgress =
      durationMs > 0 &&                        // 防除零：durationMs 未知（0）时只看时长阈值
      playedMs.toDouble() / durationMs >= 0.5
  ```
- **周归属**：按 **`sessionStartedAt`**（会话开始时间）归属周，过滤用 `sessionStartedAt >= sourceStart && sessionStartedAt < sourceEnd`。例：周日 23:59:45 开始播放、周一 00:00:15 达到门槛 → 该事件归**上周**（开始所在周）。
- **会话标识**：播放器每次开始一首新歌时生成 `playbackSessionId`。**同一 `playbackSessionId` 只存一行**——表级唯一索引 `(userId, playbackSessionId)` + `INSERT OR IGNORE`，**数据库层保证去重**，不依赖代码锁/页面状态（旋转、后台恢复、播放服务重连后会话标识仍由播放器层持有）。
- **跨会话累计**：不同会话对同一首歌的正常重复播放各自一行；播放次数 = `COUNT(*) GROUP BY songId`，无需整表改写累计。
- **保留期与清理时机（日志内部自管理，不调 Cleaner）**：仅保留最近 14 天，在实现中执行：
  ```kotlin
  data class WeeklySongStat(           // 数据库聚合结果，直接就是 selectSeeds 的输入
      val songId: Long,
      val playCount: Int,              // 上周播放次数（= 合格事件行数）
      val lastPlayedAt: Long           // 最近一次 sessionStartedAt
  )

  @Dao interface WeeklyPlayLogDao {
      @Insert(onConflict = OnConflictStrategy.IGNORE)
      suspend fun insert(event: PlayEventEntity): Long              // 返回 rowId；-1 = 唯一索引冲突未新增

      @Query("DELETE FROM weekly_play_event WHERE userId = :userId AND sessionStartedAt < :cutoff")
      suspend fun deleteOlderThan(userId: Long, cutoff: Long)       // SQL 删 cutoff 前（原子单语句）

      @Query("""
          SELECT songId, COUNT(*) AS playCount, MAX(sessionStartedAt) AS lastPlayedAt
          FROM weekly_play_event
          WHERE userId = :userId AND sessionStartedAt >= :start AND sessionStartedAt < :end
          GROUP BY songId
      """)
      suspend fun queryWeeklyStats(userId: Long, start: Long, end: Long): List<WeeklySongStat>   // SQL 聚合，替代整表拉取

      @Query("""
          DELETE FROM weekly_play_event
          WHERE id IN (
              SELECT id FROM weekly_play_event
              WHERE userId = :userId ORDER BY sessionStartedAt ASC LIMIT :deleteCount
          )
      """)
      suspend fun deleteOldest(userId: Long, deleteCount: Int)      // 超上限时删该用户最旧

      @Query("SELECT COUNT(*) FROM weekly_play_event WHERE userId = :userId")
      suspend fun countByUser(userId: Long): Int

      @Query("DELETE FROM weekly_play_event WHERE userId = :userId")
      suspend fun deleteAllByUser(userId: Long)                     // 退出登录清除该用户全部日志
  }

  @Transaction
  suspend fun insertAndPrune(event: PlayEventEntity, cutoff: Long) {
      dao.deleteOlderThan(event.userId, cutoff)
      val rowId = dao.insert(event)          // 会话去重冲突 → -1L
      if (rowId == -1L) return               // 未新增行，跳过数量检查
      val count = dao.countByUser(event.userId)
      if (count > 2000) dao.deleteOldest(event.userId, count - 2000)   // **每用户**上限
  }
  ```
  - `record()` = `insertAndPrune(event, cutoff)`（`@Transaction` 原子）；`read()` = `queryWeeklyStats()`（**SQL 聚合**，直接产出 `selectSeeds` 需要的 `(songId, playCount, lastPlayedAt)`，无整表拉取）。
  - **Cleaner 唯一入口**（公开方法，内部走 DAO，不被绕过；避免 Cleaner 反过来调 `read()` 形成递归）：
    ```kotlin
    suspend fun pruneExpired(userId: Long, now: Long) {
        val cutoff = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
            .minusDays(14).atStartOfDay(zoneId).toInstant().toEpochMilli()  // 14 个本地自然日，非固定 336h
        dao.deleteOlderThan(userId, cutoff)
    }
    ```
- **并发与进程安全（第 2 点结论）**：`MusicPlaybackService` **与 App 同进程**（Manifest 无 `android:process`，已核实），无跨进程写覆盖风险；Room DAO 单条 SQL 原子 + 唯一索引保证并发 `insert` 去重正确。
- **数量硬上限**：**每个用户**最近 14 天最多保留 **2000** 条（`insertAndPrune` 事务内先 `deleteOlderThan`；`insert` 返回 -1 = 会话去重冲突未新增，跳过数量检查；否则超限用 `deleteOldest` 删该用户最旧多出部分），防异常回调导致数据量膨胀。
- **迁移**：表结构变更走 Room `MIGRATION_n` 管理。

## 6. 推荐算法（纯 Kotlin，两个纯函数，均不触碰网络）

### 6.1 `selectSeeds(weekSongs, hydrated) → List<Seed>`

`weekSongs`：数据周每个不同歌曲的 `(songId, playCount, lastPlayedAt)`（`lastPlayedAt` = 该歌最近一次 `sessionStartedAt`）。
`hydrated`：由 UseCase 提供歌手信息后的歌曲集合。
**空输入 → 返回空 `List<Seed>`**（算法不产生 UI 状态；"数据不足"判定由 UseCase 负责）。

**初步打分（在获取歌手信息之前计算）**：

| 因子 | 公式 |
|------|------|
| 播放次数加权 | `playCountScore = log2(1 + playCount)` |
| 最近播放加权 | `recencyScore = ((lastPlayedAt - startMs).toDouble() / (endMs - startMs).toDouble()).coerceIn(0.0, 1.0)`（startMs / endMs = 周边界毫秒；**全程 Double**，防整型除零与截断） |
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
- **实现第一步是核对真实 JSON**（开发前置，编码顺序固定）：真机调用 `getSimilarSongs` 保存真实 JSON → 确认候选是否含 `artistId` → 依此决定预案 A/B → 再实现 `CandidateEdge` 与完整流程（§3 的 NeteaseApi 职责也标注了此点）。

**候选打分（种子加权，归一化后按比例影响）**：

| 因子 | 公式 |
|------|------|
| 种子权重 | `seedWeight = if (maxSeedScore > 0.0) seedScore / maxSeedScore else 0.0`（对已选种子归一化；**全程 Double**，∈ [0,1]，**防除零**） |
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

**应用级 Scope（生命周期明确，注入而非自建）**：
```kotlin
@ApplicationScope
val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```
- 由 App 级 DI/单例提供并**注入** UseCase；生命周期 = **应用进程**，ViewModel/页面销毁不取消它。
- **调用方取消 ≠ 取消共享任务**：页面离开时只取消自己的 job，共享的 `inFlight` Deferred 不受影响（同 key 后续进入仍复用）。
- **唯一取消来源**：任务自身完成，或 **key 变化**（账号切换 / 周变化）时由下方单飞逻辑取消旧任务。

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
                applicationScope.async { generate(key) }.also { newTask ->
                    inFlightKey = key
                    inFlight = newTask
                    // **清理由任务自身的完成回调负责，绝不写在调用方 finally 里**：
                    // 调用方 await 被取消（页面离开）≠ 任务取消；若在 finally 清空 inFlight，
                    // 共享任务仍在运行却已无引用，同 key 再次进入会再起新任务 → 同周并发生成。
                    newTask.invokeOnCompletion {
                        applicationScope.launch {
                            mutex.withLock {
                                if (inFlight === newTask) {   // 旧任务结束不清新任务（key 已变化）
                                    inFlight = null
                                    inFlightKey = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return task.await()     // 调用方协程被取消时抛 CancellationException，但不清 inFlight、不取消共享任务
}
```
- 请求 key 与 `inFlightKey` **相同** → 复用正在执行的任务（返回同一 Deferred）。
- 请求 key **不同** → **取消旧任务**再创建新任务（不是"等待旧任务结束"；另一用户/另一周的结果已无等待价值）。
- **清理时机**：由 Deferred 的 `invokeOnCompletion` 在任务**真正完成**（成功 / 失败 / 被异 key 取消）后清除单飞引用；守卫 `inFlight === newTask` 防旧任务结束误清新任务。
- **调用方取消 ≠ 任务取消**：页面离开只取消自己的 `await`，共享任务继续运行；同 key 再次进入仍复用同一任务，**不会并发生成**。
- **ViewModel 不再实现第二套防重**，只调用并接收状态。

**流程**：
```
1. 确定当前周：displayWeekStart = 本周一 LocalDate；sourceWeekStart = 上周一 LocalDate
   （周标识一律用 LocalDate 字符串；UI 标签 "第 N 周" 才用 IsoFields.WEEK_OF_WEEK_BASED_YEAR；
     周边界用 atStartOfDay(zoneId).toInstant() 计算，不用固定毫秒，见 §6.1）
2. 读缓存：schemaVersion 匹配 && displayWeekStart == 本周 && **sourceWeekStart == 本周一.minusWeeks(1)** && JSON 可解析 → 转换为 WeeklyRecResult 直接返回（Success 或 InsufficientData）；
   **任一不满足（含 sourceWeekStart 不一致，防字段被破坏/旧版本不一致）或 JSON 解析失败 → 删除该缓存条目（不让无效数据残留），视为 miss**
3. 读 WeeklyPlayLog（读取前自动清理 14 个本地自然日前旧数据，见 §5）：`queryWeeklyStats` **SQL 聚合**出各不同歌曲 `(songId, playCount, lastPlayedAt)`（`sessionStartedAt` ∈ [sourceStart, sourceEnd) 的过滤在 SQL WHERE 中，周归属以会话开始时间为准）
4. distinctSongCount < 2 → 写缓存 InsufficientData(validPlayCount, distinctSongCount) → 返回 InsufficientData
5. 对数据周全部不同歌曲算 preliminaryScore → 水合歌手信息（≤ 100 首拉全部，> 100 首取 Top 20，见 §6.1；每批 ≤ 50）
6. selectSeeds → 多样化 Top 8 种子（同 primaryArtistId ≤ 2）
7. 对每颗种子调 getSimilarSongs（每颗取 10）：
   - 并发 ≤ 4（Semaphore），单请求超时 8s；部分种子失败 → 跳过继续
8. rankCandidates → 聚合、去重、剔除数据周已听、primaryArtistId 上限（3→5）、排序
9. 候选 = 0 → Failure（不缓存）；否则 getSongDetail 取最终详情：
   **全部失败 → Failure（不缓存）；部分失败 → 丢弃失败歌曲，保留成功歌曲继续**
   → **恢复推荐顺序**：`detailById = details.associateBy { it.id }`，再 `rankedCandidateIds.mapNotNull { detailById[it] }`（详情接口返回乱序不影响最终列表顺序）
   → 转 `CachedSong` 轻量字段
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
- 播放日志写入 / 读取时**内部**清理 14 个本地自然日前数据（§5 的 Room `deleteOlderThan`，cutoff 按 ZoneId 计算，不依赖 Cleaner）。
- 账号切换 → **保留旧账号数据**（不同 userId 缓存 key 天然隔离，无混用；隐私策略只在**退出登录**时清除）；写缓存前再次校验身份（见 §7 步骤 10）。
- 生成中重复进入 → UseCase 单飞（Mutex 只保护任务查找，await 在锁外；同 key 复用、异 key 取消旧任务），不重复请求。

## 11. 缓存策略与清理

### 11.1 key 约定（每用户固定 key，不累积）

- **播放日志**：Room 表 `weekly_play_event`，**按 `userId` 区分用户**（无 SharedPreferences key；同一用户数据单份，不按周累积）。
- **周推荐缓存**：SharedPreferences key `weekly_rec:{userId}`，**单 key，每用户仅一份**。生成新一周推荐时**直接覆盖旧缓存**，**不按周新增 key**，避免长期使用后缓存 key 无限增长。

### 11.2 播放日志：14 天清理（Room，由 WeeklyPlayLog 内部自管理）

- `WeeklyPlayLog` 在每次 `record()`（`insertAndPrune` 事务内）、`read()`（`queryWeeklyStats` 前）执行 Room `deleteOlderThan`，删除 `sessionStartedAt < cutoff` 的旧条目；单条 SQL，原子执行。
- **cutoff 按 14 个本地自然日计算**（`atStartOfDay(zoneId).minusDays(14)`，见 §5），与周边界一致使用 ZoneId，夏令时切换周不差一小时；**不使用固定 336 小时**。
- 保留期由实现保证，不只是文档约定；**不回调 Cleaner**（避免递归与多余 I/O）。

### 11.3 周推荐缓存：无效即删

- `schemaVersion` 不匹配、JSON 解析失败、字段缺失等**任何无效情况 → 删除该缓存条目**，视为 miss 重新生成，不让无效数据一直残留。

### 11.4 轻量缓存字段

- `Success` 缓存 `CachedSong(songId, name, artists, cover)` 轻量字段（足够渲染列表、可离线展示），外加 `seedCount`；`InsufficientData` 缓存 `validPlayCount` / `distinctSongCount` 统计——**都是 UI 还原文案所需的最小字段**。
- **不持久化完整 `Song` 大对象**；点播时才批量拉详情转完整 `Song`。

### 11.5 统一清理方法

```kotlin
// WeeklyCacheCleaner —— **suspend**：内部调用 Room suspend DAO，调用方须等待
suspend fun cleanupWeeklyCache(userId: Long, now: Long)
```

职责：
1. 删除 14 天以前的播放日志条目（Room 表 `weekly_play_event` 中该用户）——**只能调用公开方法 `WeeklyPlayLog.pruneExpired(userId, now)`**（内部走 Room DAO `deleteOlderThan`，见 §5）；**绝不直接操作底层 Storage**、**绝不调用 `WeeklyPlayLog.read()`**（避免 read → cleaner → read 循环与绕过日志锁）；
2. 删除无效 / schemaVersion 过期 / 解析失败的周推荐缓存（`weekly_rec:{userId}`）；
3. 保证每个用户只存在一份周推荐结果（覆盖式写入 + 上面两条清理保证）。

**调用时机（应用级事件触发，不挂到每次 record/read）**：
- **打开推荐页面**：进入 MyScreen 触发一次完整清理；
- **退出登录（隐私清除，suspend 等待成功）**：播放日志（Room）与周推荐缓存（SharedPreferences）均为持久化，须**等待数据库删除成功后再完成退出**，不启动无管理的后台协程。新增独立方法：
  ```kotlin
  suspend fun clearWeeklyUserData(userId: Long) {
      weeklyPlayLogStorage.deleteAllByUser(userId)      // Room：DELETE FROM weekly_play_event WHERE userId = :userId
      weeklyRecommendationStorage.remove(userId)        // SharedPreferences：删 weekly_rec:{userId}
  }
  ```
  由退出登录流程在账号态切换前调用并 await；原"移除两个 weekly key"的同步逻辑已过时并取消。
- **账号切换**：**保留旧账号数据**（不同 userId key 天然隔离，无混用；隐私策略只在**退出登录**时清除，切换不清）；旧账号在途生成任务因 **key 变化**由 UseCase 单飞取消（见 §7）；
- **App 启动维护**：启动时清理一次，兜底过期数据。
- 周推荐生成流程读缓存时命中无效缓存即删（§7 步骤 2，属 Store 自身职责，**不计入** Cleaner 调用点）。

### 11.6 存储占用说明

- **存储拆分**：播放日志在 **Room 数据库**（表级索引 + SQL 范围查询，见 §5）；周推荐缓存为**单 key SharedPreferences**（每用户一条 JSON，写入频率极低，无 O(n) 整表读改写问题）。
- 两者均为**本地存储**，不长期占运行内存；定期清理 + 覆盖式写入保证长期使用后数据不持续累积。
- **SQLite 删除后文件不立即缩小**：逻辑数据已删除，空间由 SQLite 后续复用，磁盘文件不一定马上变小——属正常现象，不代表缓存持续累积；**不在每次清理后 VACUUM**（开销大），仅当数据库异常膨胀时考虑低频维护。

### 11.7 序列化格式（明确 JSON 方案）

- **播放日志在 Room，无需序列化**；**周推荐缓存**用 **kotlinx.serialization**（项目当前用 Gson——AppCache / Retrofit；**Gson 无法原生序列化 sealed interface**，需手写 RuntimeTypeAdapter 判别符，故周缓存引入 kotlinx.serialization：新依赖 Kotlin `plugin.serialization` 插件 + `kotlinx-serialization-json`）。
- **所有持久化到缓存的对象都标注 `@Serializable`**（`WeeklyRecCache`、`CachedSong`、`Success`、`InsufficientData` 及其嵌套字段，见 §4）。
- **存储封装分离**：`WeeklyPlayLogStorage`（Room，播放日志）与 `WeeklyRecommendationStorage`（SharedPreferences，周推荐缓存）为两个独立封装，职责不混用。
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

- `WeeklyPlayLogTest`（Room 内存库）：
  - **播放层合格判定**（独立谓词）：`>= 30s` / `>= 50%`（含恰好 30 秒计入）
  - **`durationMs = 0` 时不除零**，只采用 30 秒规则
  - **跨会话重复播放可累计**（同歌多次播放 → `queryWeeklyStats` 的 `playCount` 递增）
  - **同一 playbackSessionId 重复回调去重**（唯一索引 `INSERT OR IGNORE`，只一行；`insert` 返回 -1）
  - 14 天清理（`deleteOlderThan`，**cutoff 按 14 个本地自然日 + ZoneId 计算**，含夏令时用例）
  - **并发 insert 去重正确**（唯一索引保证，无 read-modify-write 覆盖）
  - **每用户 2000 条硬上限**：`insertAndPrune` 超限用 `deleteOldest` 删该用户最旧；`insert` 返回 -1 时跳过数量检查
  - **周日开始播放、周一达到有效门槛 → 按 sessionStartedAt 归属上周**
  - **`queryWeeklyStats` 聚合正确**：`playCount = COUNT(*)`、`lastPlayedAt = MAX(sessionStartedAt)`，直接产出 selectSeeds 输入
  - **`pruneExpired(userId, now)` 公开入口**：只清理指定 userId 的 14 个本地自然日前数据
  - **`deleteAllByUser(userId)`**：只删除该用户全部行（退出登录用）
- `WeeklyRecommendationAlgorithmTest`（纯 JVM）：
  - `selectSeeds`：播放次数加权、最近播放加权（**全程 Double，`coerceIn(0.0, 1.0)`**）、同 `primaryArtistId` ≤ 2、种子封顶 8、**空输入 → 空列表**（不产生 UI 状态）
  - `rankCandidates`：种子权重影响得分（高频种子贡献更大；**`maxSeedScore` 为 0 → `seedWeight = 0.0` 防除零**）、共同推荐加分、相似排名影响、剔除已听、`primaryArtistId` 上限 **3 → 5**（不含 10）、候选 < 30 返回实际数量、**所有候选都已听过 → 空**、**同分歌曲排序稳定**、合作歌曲只占第一位歌手配额
  - **非法边过滤**：`candidateSongId <= 0` / `simRank` 越界 / `primaryArtistId` 无效 / `candidateSongId == seedSongId` 被剔除
  - **重复边去重**：同 `seedSongId + candidateSongId` 不重复加分；`coOccurrence` 按**不同种子数**统计
- `GenerateWeeklyRecommendationUseCaseTest`：
  - **不同歌曲数 < 2（即使播放 20 次同一首）→ InsufficientData**
  - 部分种子接口失败 → 继续生成
  - 候选不足 30 → 实际数量
  - **部分歌曲详情失败 → 丢弃失败歌曲，剩余正常展示并缓存；全部失败 → Failure**
  - **详情返回乱序 → 用 `associateBy` + `mapNotNull` 恢复推荐顺序**（列表顺序 = 推荐排序，与详情接口返回顺序无关）
  - 生成期间重复进入（同 key）→ 复用同一任务，只生成一次
  - **不同 key（另一用户 / 另一周）→ 取消旧任务，再创建新任务**
  - **任务真正完成（成功 / 失败 / 被异 key 取消）由 `invokeOnCompletion` 清除 inFlight**（守卫 `inFlight === newTask`；旧任务结束不清新任务）
  - **调用方 await 被取消（页面离开）→ inFlight 不清空、共享任务继续运行**；同 key 重新进入复用同一任务，**不并发生成**
  - **Mutex 不长时间占用**：锁只保护任务查找/创建，await 在锁外（锁竞争微秒级）
  - **Scope 由应用级注入**：调用方取消自己的 job 不影响共享的 inFlight 任务
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
  - **suspend** `cleanupWeeklyCache` 删除 14 个本地自然日前播放日志（**唯一入口：`WeeklyPlayLog.pruneExpired(userId, now)`，不直接操作 Storage、不调用 `read()`**）
  - 删除无效 / 版本过期的周推荐缓存
  - 清理后每用户周推荐 key 恰好一份
  - 退出登录 → **suspend `clearWeeklyUserData(userId)` 等待 Room 删除成功后再完成退出**：Room 中该用户记录为空 且 `weekly_rec:{userId}` 不存在
  - **账号切换 → 不清除旧账号数据**（保留，key 隔离）；App 启动 → 触发点清理正确
  - **不产生递归**：Cleaner 清理日志时不会触发 `WeeklyPlayLog.read()`

## 13. 核心流程（最终）

```
播放达到有效条件后记录事件（>=30s 或 >=50% 且防除零，合格判定在播放层；落库已合格事件，不含 playedMs/durationMs；Room 唯一索引 (userId, playbackSessionId) INSERT OR IGNORE 去重、跨会话按行累计；insertAndPrune 事务内 deleteOlderThan 14 个本地自然日前，不回调 Cleaner；每用户 2000 上限，超限 deleteOldest）
→ 本周首次打开页面（触发 Cleaner 应用级清理；Cleaner 清日志只能走 WeeklyPlayLog.pruneExpired(userId, now) 唯一入口；退出登录走 suspend clearWeeklyUserData）
→ 读取上一个完整自然周的播放记录（queryWeeklyStats SQL 聚合出 (songId, playCount, lastPlayedAt)；周归属按 sessionStartedAt；周边界用 atStartOfDay(zoneId).toInstant()，不用固定毫秒；周一日期作周键）
→ 先算初步分（次数 0.7 + 新鲜度 0.3，全程 Double：toDouble() / coerceIn(0.0, 1.0)，seedWeight 防除零）→ 水合歌手信息（≤100 首拉全部，>100 首取 Top 20）
→ 选择多样化 Top 8 种子（同歌手 ≤ 2）
→ 并发获取相似歌曲（≤ 4 并发、单请求超时；UseCase 按 GenerationKey 单飞：应用级注入 Scope，Mutex 只保护任务查找，await 在锁外，异 key 取消旧任务，单飞引用由 invokeOnCompletion 在任务真正完成时清理）
→ 候选过滤非法边 + 去重（seedSongId+candidateSongId）→ 种子加权聚合、剔除上周已听、primaryArtistId 上限（3→5）
→ 获取歌曲详情（部分失败丢弃，全部失败 Failure）→ associateBy + mapNotNull 恢复推荐顺序 → 转轻量 CachedSong
→ 写缓存前校验身份（含 sourceWeekStart 一致）→ 覆盖式缓存（单 key、schemaVersion + 周一日期键 + sealed 结果 + kotlinx.serialization，持久化类全 @Serializable，存储封装分离；无效缓存即删）
→ UseCase 返回 WeeklyRecResult → ViewModel 映射 UI 状态 → 展示实际生成数量
```

## 14. 明确不做（YAGNI）

- 不真实创建网易云歌单（不写入账号）
- 不做"本周最爱"独立区块
- 不做服务端/云同步推荐
- 不做手动"换一批"按钮

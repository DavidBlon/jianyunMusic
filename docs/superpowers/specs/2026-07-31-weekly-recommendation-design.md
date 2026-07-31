# 每周推荐歌单 —— 设计文档（修订 v2）

日期：2026-07-31
状态：已获用户批准（含全部修订意见）

## 1. 目标

在"我的"页面新增**每周推荐歌单**入口：根据**上一个完整自然周的播放数据**，通过**相似歌曲聚合算法**生成 App 内虚拟歌单，本周首次打开页面时生成并缓存，每周一自动切换到新一周。

## 2. 已确认的关键决策

| 决策点 | 选择 |
|--------|------|
| 歌单形式 | **App 内虚拟歌单**（不写入网易云账号） |
| 算法主干 | **相似歌曲聚合** |
| 数据窗口 | **上一个完整自然周**（上周一 00:00 → 本周一 00:00），本周首次打开时生成 |
| 架构 | **方案 A**：独立周播放日志 + 纯 Kotlin 算法 + **UseCase 编排**（算法不碰网络） |
| 有效播放 | 播放 > 30 秒 **或** 进度 > 50%（先达者）；正常重复播放累计，仅去重同一会话重复回调 |
| 并发与防重 | 相似接口并发 ≤ 4、单请求超时；生成任务单飞（Job/Mutex），重复进入不重复生成 |

## 3. 组件划分

均遵循项目现有惯例（`NeteaseApp.instance` 单例访问、分层清晰）。

| 组件 | 层 | 职责 |
|------|-----|------|
| `WeeklyPlayLog` | data | 记录**有效播放**事件 `(songId, 时间戳, 播放时长, 歌曲总长)`，按用户区分，保留最近 14 天 |
| `WeeklyRecommendationAlgorithm` | 纯 Kotlin | **仅**两个纯函数：`selectSeeds`（选种子）+ `rankCandidates`（聚合排序）；**不调用任何网络接口** |
| `GenerateWeeklyRecommendationUseCase` | domain | 编排：读日志 → 过滤数据周 → 水合歌手信息 → 调相似接口（并发+超时）→ 跑算法 → 取详情 → 写缓存；单飞 |
| `NeteaseApi.getSimilarSongs` | api | 新增"相似歌曲"接口（真实路径实现时真机验证） |
| `WeeklyRecommendationStore` | data | 缓存（含 `schemaVersion`、`sourceWeek`、`displayWeek`），处理损坏/账号切换/跨年 |
| `MainViewModel.weeklyRecState` | viewmodel | 暴露 `sealed` UI 状态，随 `loadMyData()` 触发加载，持有生成 Job 实现单飞 |
| `WeeklyRecommendationRow` + `WeeklyRecommendationScreen` | ui | "我的"页入口卡片 + 歌曲列表页 |

## 4. 数据模型

```kotlin
// 有效播放事件（AppCache，key: "weekly_play_log:{userId}"）
// 写入门槛：playedMs > 30_000 或 playedMs / durationMs > 0.50（先达者）
// 正常重复播放每次记一条（用于统计播放次数）；同一播放会话的重复回调只记一条
data class PlayEvent(
    val songId: Long,
    val timestamp: Long,
    val playedMs: Long,      // 实际播放时长
    val durationMs: Long     // 歌曲总时长（未知时为 0，此时只看 playedMs 阈值）
)

// 周推荐缓存（key: "weekly_rec:{userId}"）
data class WeeklyRecCache(
    val schemaVersion: Int,  // 缓存结构版本；升级后旧缓存作废，重新生成
    val sourceWeek: String,  // "2026-W30"：用哪一周的播放数据
    val displayWeek: String, // "2026-W31"：展示在哪一周
    val songs: List<Song>,
    val generatedAt: Long
)

// UI 状态：sealed，天然排除非法组合
sealed interface WeeklyRecUiState {
    data object Loading : WeeklyRecUiState
    data class Success(
        val songs: List<Song>,
        val seedCount: Int,          // 实际用于生成种子的歌数（≤ 8）
        val sourceWeekLabel: String, // 如 "上周 · 第 30 周"
        val displayWeekLabel: String // 如 "第 31 周"
    ) : WeeklyRecUiState
    data object InsufficientData : WeeklyRecUiState   // 数据周有效播放 < 2 首
    data class Error(val message: String?) : WeeklyRecUiState
}
```

## 5. 有效播放记录（WeeklyPlayLog）

- **写入条件**：播放进度首次跨过 `playedMs > 30 秒` **或** `playedMs / durationMs > 50%`（先达者）。
- **去重边界**：只去除**同一次播放会话**中因播放器重复回调产生的重复事件（同一会话跨过阈值时只写一条）。**不同会话对同一首歌的正常重复播放都各自记一条**，以累计播放次数。
- **捕获点**：播放进度观察处（PlayerViewModel 进度回调），以「当前播放会话」跟踪避免重复回调重复写入。
- **保留期**：仅保留最近 14 天，避免 SharedPreferences 膨胀。

## 6. 推荐算法（纯 Kotlin，两个纯函数）

### 6.1 `selectSeeds(weekPlays, songInfos) → List<Seed>`

输入数据周的播放日志（已聚合）与歌曲详情（含歌手，由 UseCase 水合）。

**种子打分**：

| 因子 | 公式 | 说明 |
|------|------|------|
| 播放次数加权 | `playCountScore = log2(1 + playCount)` | 对数压缩，避免单曲循环霸榜 |
| 最近播放加权 | `recencyScore = (lastPlayedAt - weekStart) / (weekEnd - weekStart)`，截断到 [0,1] | 越靠近周结束分越高 |
| 种子总分 | `seedScore = 0.7 * playCountScore + 0.3 * recencyScore` | 次数为主、新鲜度为辅 |

**选择规则**：按 `seedScore` 降序贪心选取，**同一歌手最多选 2 首**（避免种子全来自同一歌手），共取 **Top 8**。歌手上限不满足时跳过该歌继续。

### 6.2 `rankCandidates(candidates, listenedSongIds) → List<SongId>`

输入所有种子拉回的相似歌曲候选（含 `simRank`、歌手）与**数据周已听歌曲 id 集合**。**不发起任何网络请求**。

**候选打分**：

| 因子 | 公式 | 说明 |
|------|------|------|
| 相似排名 | 单颗种子贡献 `simContribution = (SIM_LIMIT - simRank + 1)`（SIM_LIMIT=10，rank 从 1 起） | 越靠前分越高 |
| 共同推荐 | `coOccurrence` = 推荐该歌的种子数；`coScore = 3 * coOccurrence` | 被多颗种子共同推荐加分 |
| 候选总分 | `totalScore = coScore + Σ simContribution` | 两者叠加 |

**处理步骤**：
1. 剔除 `listenedSongIds`（数据周已听歌曲）。
2. 同歌手上限 3 首；若结果 < 30 逐级放宽 **3 → 5 → 10**。
3. 目标 30 首（软上限）；**不足 30 时返回实际数量，不凑数**。
4. **同分稳定排序**：按 `coOccurrence` 降序 → `Σ simContribution` 降序 → `Σ simRank` 升序 → `songId` 升序（最终确定性平局决胜，保证多次运行结果一致）。

## 7. 生成编排（GenerateWeeklyRecommendationUseCase）

**单飞**：持有进行中的协程 Job；重复触发时返回同一 Job / 直接等待，不并发生成。账号切换时取消在途生成。

```
1. 确定当前周 W = 本周一 00:00 起（设备本地时区，ISO 周）
   displayWeek = "yyyy-Www"(W)；sourceWeek = W-1（上一完整自然周）
2. 读 WeeklyPlayLog，过滤 events ∈ [sourceWeekStart, sourceWeekEnd)
3. 有效播放 < 2 首 → InsufficientData
4. getSongDetail 水合播放次数最高的前 15 首歌的歌手信息
5. selectSeeds → 多样化 Top 8 种子（同歌手 ≤ 2）
6. 对每颗种子调 getSimilarSongs（每颗取 10）：
   - 并发限制 ≤ 4（Semaphore），单请求超时 8s
   - 部分种子失败 → 跳过继续，不整体失败
7. rankCandidates → 聚合、去重、剔除数据周已听、同歌手限制、排序
8. 候选 = 0 → Error；否则 getSongDetail 取详情
9. 写缓存 WeeklyRecCache(schemaVersion, sourceWeek, displayWeek, songs, now)
10. 返回 Success(songs, 实际 seedCount, ...)
```

## 8. 更新机制（固定周一 · 用上周数据）

- **周边界**：每周一 00:00（设备本地时区，`java.time.WeekFields.ISO`）。
- **触发**：本周（displayWeek）首次打开页面时生成。缓存有效判定：`cache.schemaVersion == 当前` 且 `cache.displayWeek == 本周` 且 JSON 可解析。
- 一周内反复打开 → 命中缓存，零请求。
- **跨年**：ISO 周带年份（"2026-W30"），`WeekFields.ISO` 正确处理跨年（如 2025-W52 → 2026-W01）。
- **时区**：周边界统一用设备默认时区；换时区导致周计算变化属可接受行为，缓存按 displayWeek 自然过期。

## 9. UI

### "我的"页面（MyScreen）

在"设置"下方、歌单列表上方插入"每周推荐"卡片，按 sealed 状态渲染：

| 状态 | 卡片展示 |
|------|---------|
| Loading | 卡片转圈 |
| Success | 封面（第一首推荐歌封面）+ "每周推荐" + "第 31 周 · 已根据上周 8 首有效播放生成 · **M 首**"（M 为**实际**数量，不固定写 30） |
| InsufficientData | "上周听歌太少，多听几首下周再来"（不可点击） |
| Error | "生成失败，点击重试" |

### 歌曲列表页（WeeklyRecommendationScreen）

- 新增路由 `weekly`；歌曲列表复用歌单详情页行样式，支持点击播放（`setQueue`）。
- 遵循 `glassSurface` 玻璃拟态与 `miniPlayerSafeBottomPadding` 惯例。

## 10. 错误处理

- 部分种子相似接口失败 → 跳过继续，有多少生成多少。
- 候选 = 0（全部失败 / 全部被剔除）或详情拉取失败 → `Error`。
- 候选 > 0 但 < 30 → `Success` 展示实际数量。
- `InsufficientData`（数据周有效播放 < 2 首）与 `Error` 是独立状态，不复用。
- 缓存损坏 / schemaVersion 不匹配 → 视为缓存 miss，重新生成。
- 账号切换 → 不同 userId 缓存 key 天然隔离；在途生成随登录态取消。
- 生成中重复进入 → Job 单飞，不重复请求。

## 11. 缓存与账号切换

- key：`weekly_rec:{userId}`、`weekly_play_log:{userId}`。
- 常量 `schemaVersion`；升级后旧缓存作废。
- 登出 → `AppCache.clearUserData()` 同时清理两个 key。
- 账号切换 = 不同 key，自动隔离。

## 12. 测试

- `WeeklyPlayLogTest`：
  - 有效播放门槛（>30s / >50%）
  - **正常重复播放可累计**（多次播放同一首歌 → playCount 递增）
  - **同一次播放的重复回调被去重**（同一会话只记一条）
  - 14 天清理
- `WeeklyRecommendationAlgorithmTest`（纯 JVM）：
  - `selectSeeds`：播放次数加权、最近播放加权、同歌手 ≤ 2、种子封顶 8、空 → InsufficientData
  - `rankCandidates`：共同推荐加分、相似排名影响、剔除已听、同歌手 3→5→10 放宽、候选 < 30 返回实际数量、**所有候选都已听过 → 空**、**同分歌曲排序稳定**
- `GenerateWeeklyRecommendationUseCaseTest`：
  - **部分种子接口失败 → 继续生成**
  - **候选不足 30 → 实际数量**
  - **生成期间重复进入 → 只生成一次**（单飞）
  - **生成期间切换账号 → 在途取消 / 互不影响**
- `WeeklyRecommendationStoreTest`：
  - **周日 23:59 与周一 00:00 边界**
  - **ISO 周跨年**
  - schemaVersion 不匹配 → 失效
  - 缓存损坏 → 视为 miss
  - 账号切换 → 不同 key

## 13. 核心流程（最终）

```
播放达到有效条件后记录事件（>30s 或 >50%，同一会话去重、跨会话累计）
→ 本周首次打开页面
→ 读取上一个完整自然周的播放记录（上周一 00:00 → 本周一 00:00）
→ 选择多样化 Top 8 种子（同歌手 ≤ 2）
→ 并发获取相似歌曲（≤ 4 并发、单请求超时、单飞防重）
→ 聚合、去重、剔除上周已听歌曲、限制同歌手数量（3→5→10）
→ 获取歌曲详情
→ 缓存结果（schemaVersion + sourceWeek + displayWeek）
→ 展示实际生成数量
```

## 14. 明确不做（YAGNI）

- 不真实创建网易云歌单（不写入账号）
- 不做"本周最爱"独立区块
- 不做服务端/云同步推荐
- 不做手动"换一批"按钮

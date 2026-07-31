# 每周推荐歌单 —— 设计文档

日期：2026-07-31
状态：已获用户批准

## 1. 目标

在"我的"页面新增**每周推荐歌单**入口：根据用户**本周实际播放的歌曲**，通过**相似歌曲聚合算法**动态生成一个 App 内虚拟歌单，每周一自动更新。

## 2. 已确认的关键决策

| 决策点 | 选择 |
|--------|------|
| 歌单形式 | **App 内虚拟歌单**（不写入网易云账号，无副作用） |
| 算法主干 | **相似歌曲聚合**（本周常听歌曲 → 网易云相似歌曲接口 → 聚合排序） |
| 更新时机 | **固定周一自动更新**（跨周首次打开时重新生成，一周内命中缓存） |
| 架构 | **方案 A**：独立周播放日志 + 纯 Kotlin 推荐算法（不改动现有播放历史） |

## 3. 组件划分

均遵循项目现有惯例（`NeteaseApp.instance` 单例访问、reducer/repository 分层）。

| 组件 | 层 | 职责 |
|------|-----|------|
| `WeeklyPlayLog` | data | 播放达到**有效播放门槛**后记录 `(songId, 时间戳, 播放时长, 歌曲总长)`，按用户区分，仅保留最近 14 天 |
| `WeeklyRecommendationAlgorithm` | 纯 Kotlin | 本周听歌记录 → 推荐歌曲 id 列表；无 Android 依赖，可单元测试 |
| `NeteaseApi.getSimilarSongs` | api | 新增网易云"相似歌曲"接口调用 |
| `WeeklyRecommendationStore` | data | 缓存生成结果 + 所属 ISO 周次，判断是否过期 |
| `MainViewModel.weeklyRecState` | viewmodel | 暴露 UI 状态，随 `loadMyData()` 触发加载 |
| `WeeklyRecommendationRow` + `WeeklyRecommendationScreen` | ui | "我的"页入口卡片 + 点击进入的歌曲列表页 |

## 4. 数据模型

```kotlin
// 周播放日志条目（存在 AppCache，key: "weekly_play_log:{userId}"）
// 仅记录"有效播放"：playedMs ≥ 30 秒 或 playedMs / durationMs ≥ 30%（先达者），快速切歌不计入
data class PlayEvent(
    val songId: Long,
    val timestamp: Long,
    val playedMs: Long,      // 实际播放时长
    val durationMs: Long     // 歌曲总时长（未知时为 0，此时只看 playedMs 阈值）
)

// 周推荐缓存（key: "weekly_rec:{userId}"）
data class WeeklyRecCache(
    val isoWeek: String,          // 生成时所属 ISO 周，如 "2026-W31"
    val songs: List<Song>,
    val generatedAt: Long
)

// MainViewModel 暴露的 UI 状态
data class WeeklyRecUiState(
    val songs: List<Song> = emptyList(),
    val seedCount: Int = 0,        // 本周作为种子使用的歌曲数
    val weekLabel: String = "",    // 如 "第 31 周"
    val isLoading: Boolean = false,
    val error: String? = null,
    val notEnoughData: Boolean = false   // 本周听歌太少，不足以生成
)
```

## 5. 核心推荐算法（相似歌曲聚合）

**有效播放门槛（写入 WeeklyPlayLog 的前提）**：
- 播放进度首次跨过 `playedMs ≥ 30 秒` **或** `playedMs / durationMs ≥ 30%`（先达者）时，记一条 PlayEvent。
- 同一首歌的同一播放会话只记一次（进度跨过阈值时写入，不重复）。
- 快速切歌（只听几秒就切走）永不满足门槛，不计入日志，不污染播放次数权重。

```
1. 取本周（本周一 0 点至今）播放日志，按歌曲聚合 → (songId, 播放次数, 最近播放时间)
   （日志中的每条记录都已满足有效播放门槛）
2. 加权打分 = 播放次数权重 + 新鲜度加成（最近播过的分更高）→ 取 Top 8 作为种子歌曲
3. 对每颗种子调用相似歌曲接口（每颗 Top 10）→ 候选池（≤ 80 首）
4. 候选打分：
   - 共现度：被多颗种子共同推荐 → 分更高
   - 新鲜度：本周已听过的歌 → 直接剔除（推荐的是"新歌"）
   - 多样性：同一歌手最多保留 3 首，防止推荐扎堆同一人
5. 按总分取 Top 30，输出推荐歌单
6. 候选不足兜底阶梯（候选池 < 30 时）：
   a. 逐级放宽同歌手上限：3 → 5 → 10（先保数量，后保多样性）
   b. 仍 < 30 → 展示实际数量，不凑数（副标题如实显示 M 首）
   c. 候选 = 0（全部种子接口失败 / 全部被剔除）→ 报"生成失败"
```

参数默认值：种子数 8、每颗候选 10、歌单 30 首、同歌手上限 3。
`notEnoughData`（"数据不足"）**仅**表示"本周有效播放 < 2 首"；候选池偏小是另一个场景，按上述兜底阶梯处理，不复用"数据不足"。

## 6. 数据流

```
打开"我的"页 → loadMyData() 触发 loadWeeklyRecommendation()
   │
   ├─ 读缓存：cache.isoWeek == 本周ISO周 且 songs 非空 → 直接展示，零网络请求
   │
   └─ 否则（首次 / 已跨周）→ 重新生成：
       1. 读 WeeklyPlayLog，过滤出本周记录（条目均已满足有效播放门槛，快速切歌不计入）
       2. 有效播放不足阈值（< 2 首）→ notEnoughData，显示提示，结束
       3. 跑推荐算法 → 候选不足时按兜底阶梯处理（见 §5）→ 得到最终推荐
       4. getSongDetail 拉详情 → 存 WeeklyRecCache(isoWeek=本周) → 展示
```

## 7. 更新机制（固定周一）

- 周边界 = 每周一 0 点（ISO 周）。缓存记录生成时的 ISO 周次。
- 打开页面时比较 `本周 isoWeek != cache.isoWeek` → 跨周才重新生成。
- 一周内反复打开页面都直接命中缓存，不消耗请求。
- 跨周后首次打开自动重新生成。

## 8. UI

### "我的"页面（MyScreen）

在"设置"条目下方、歌单列表上方插入"每周推荐"入口卡片：

| 状态 | 卡片展示 |
|------|---------|
| 正常 | 封面（第一首推荐歌封面）+ "每周推荐" + "第 31 周 · 已根据本周 N 首有效播放生成 · M 首"（M 为实际数量，不足 30 时如实显示，不凑数） |
| 数据不足 | "本周听歌太少，多听几首下周再来"（不可点击；本周有效播放 < 2 首） |
| 加载中 | 卡片显示转圈 |
| 生成失败 | "生成失败，点击重试"（候选池为 0 或详情拉取失败） |

### 歌曲列表页（WeeklyRecommendationScreen）

- 新增路由 `weekly`。
- 歌曲列表复用歌单详情页的行样式，支持点击播放（`playerViewModel.setQueue`）。
- 遵循 `glassSurface` 玻璃拟态风格与 `miniPlayerSafeBottomPadding` 布局惯例。

## 9. 错误处理

- 单颗种子的相似歌曲接口失败 → 跳过该种子继续，不整体失败。
- 候选池为 0（全部种子失败 / 全部被剔除）或详情拉取失败 → 显示"生成失败"，可点击重试。
- 候选池 > 0 但不足 30 → 展示实际数量，不报错、不凑数（见 §5 兜底阶梯）。
- "生成失败"与"数据不足"（本周有效播放 < 2 首）是两个独立状态，不复用。
- 生成中重复进入 → `isLoading` 去重，不重复请求。
- 登出 → 连同 `weekly_play_log`、`weekly_rec` 一起清理（加入 `AppCache.clearUserData`）。

## 10. 测试

- `WeeklyRecommendationAlgorithmTest`（纯 JVM）：
  - 空日志 → notEnoughData
  - 播放次数多的歌曲权重更高
  - 本周已听过的候选被剔除
  - 同一歌手最多保留 3 首
  - 种子数量封顶
  - 候选不足兜底：同歌手上限逐级放宽（3 → 5 → 10）生效
  - 候选不足 30 → 返回实际数量
  - 候选为 0 → 标记生成失败
- `WeeklyRecommendationStoreTest`：跨周过期判断（isoWeek 比较）
- `WeeklyPlayLogTest`：
  - 有效播放门槛：playedMs < 30s 且 < 30% 不记录；跨过阈值恰好记录一次
  - 同一播放会话不重复记录
  - 14 天清理、重复歌曲记录去重

## 11. 明确不做（YAGNI）

- 不真实创建网易云歌单（不写入账号）
- 不做"本周最爱"独立区块（聚焦推荐本身）
- 不做服务端/云同步推荐（纯本地生成）
- 不做手动"换一批"按钮（固定周一自动更新，后续可加）

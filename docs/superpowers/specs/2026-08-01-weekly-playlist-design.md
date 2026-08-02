# 设计：每周推荐改造为歌单「每周推荐」

日期：2026-08-01
状态：已与用户确认（方案 A）

## 1. 目标与背景

用户反馈现有的「每周推荐」卡片 + 独立详情页（`WeeklyRecommendationScreen`，路由 `weekly`）不好用，
希望它像歌单一样：在「我的」页歌单列表里以一行「每周推荐」出现，点开复用标准的歌单详情页。

用户已确认的决策：
1. **完全移除** 现有卡片（`WeeklyRecommendationCard`）和独立详情页（`WeeklyRecommendationScreen` + `Routes.WEEKLY`），用歌单行替代。
2. 歌单行 **始终显示**（Loading/数据不足/失败也显示，0 首），位于 **「我的」页歌单列表最顶部**。
3. 数据不足/生成失败时点开详情页显示 **空态提示文案**（无重试按钮，重进页面即重试）。
4. 详情页头部 **完全标准歌单样式**，副标题「网易云音乐 · N 首歌」，**不显示周标签**（第 X 周）和生成说明。

每周生成的整套数据逻辑（播放记录、相似歌曲算法、缓存、single-flight、logout 清理）**全部保留不动**，
仅作为这个歌单的数据源。

## 2. 歌单标识

- 新增共享常量 `const val WEEKLY_PLAYLIST_ID = -1L`，放在 `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt`。
- 网易云歌单 id 恒为正数，`-1L` 永不冲突。
- MyScreen（ui）与 MainViewModel（viewmodel）都引用该常量。

## 3. 「我的」页歌单行

`WeeklyRecUi.kt` 新增纯函数：

```kotlin
fun weeklyRow(state: WeeklyRecUiState): Playlist = when (state) {
    is WeeklyRecUiState.Success -> Playlist(
        id = WEEKLY_PLAYLIST_ID,
        name = "每周推荐",
        cover = state.songs.firstOrNull()?.cover,
        trackCount = state.songs.size
    )
    else -> Playlist(
        id = WEEKLY_PLAYLIST_ID,
        name = "每周推荐",
        trackCount = 0
    )
}
```

（依赖 `com.ncm.app.data.model.Playlist`。）

MyScreen 改动：
- 歌单列表最顶部（现有 `WeeklyRecommendationCard` 所在 item 位置）渲染一行
  `MyPlaylistItem(playlist = weeklyRow(weeklyRecState), onClick = { onPlaylistClick(WEEKLY_PLAYLIST_ID) })`，
  始终显示、始终可点。
- 删除 `WeeklyRecommendationCard` 私有 composable 及其 `item {}` 块。
- 删除 `onWeeklyClick: () -> Unit` 参数。
- `LaunchedEffect` 保留三连调用：`cleanupWeeklyCacheOnPageOpen()` → `loadMyData()` → `loadWeeklyRecommendation()`。

## 4. 详情页（复用标准 PlaylistDetailScreen）

路由不变：`Routes.playlistDetail(WEEKLY_PLAYLIST_ID)` → `playlist/-1` → `PlaylistDetailScreen(playlistId = -1)`。

`MainViewModel.loadPlaylistDetail(id)` 顶部加分支：

```kotlin
if (id == WEEKLY_PLAYLIST_ID) {
    loadWeeklyPlaylistDetail()
    return
}
// ...现有歌单逻辑原样保留...
```

新增私有方法 `loadWeeklyPlaylistDetail()`：
1. 若 `_playlistState.value.isLoading && loadedPlaylistId == WEEKLY_PLAYLIST_ID` 则 return（防重复）。
2. `viewModelScope.launch`：
   - 调用 `loadWeeklyRecommendation()`（single-flight 防重；Success/Insufficient 时为空操作，Error 时重跑）。
   - 等待 `weeklyRecState` 落定：`_weeklyRecState.filter { it !is WeeklyRecUiState.Loading }.first()`。
   - 从落定状态取 `count`（Success → songs.size，其余 → 0）与 `cover`（Success → 首曲 cover）。
   - 设置 `_playlistState.value = PlaylistDetailUiState(playlist = PlaylistMeta(id = WEEKLY_PLAYLIST_ID, name = "每周推荐", cover = cover, trackCount = count), isLoading = true, loadedPlaylistId = WEEKLY_PLAYLIST_ID)`。
   - `val songs = hydrateWeeklyDetailSongsNow().orEmpty()`（复用现有方法：`_weeklyDetailSongs` 缓存优先，否则 `getSongDetail`）。
   - 计算空态文案 `error`：
     - `InsufficientData` → `"本周听歌数据不足，多听几首下周再来"`
     - `Error` → `rec.message`
     - `Success` 但 `songs.isEmpty()` → `"歌曲加载失败，请重试"`
     - `Success` 且 `songs` 非空 → `null`
   - 设置最终 `_playlistState.value = PlaylistDetailUiState(playlist = <同上>, songs = songs, isLoading = false, error = <上面计算值>, loadedPlaylistId = WEEKLY_PLAYLIST_ID, isFullyLoaded = true)`。

播放全部 / 搜索 / 行点击：走 PlaylistDetailScreen 现有逻辑，NavGraph 的 playlistDetail composable 不动
（`onSongClick` 用 `mainViewModel.playlistState.value.songs` 设队列，对每周歌单同样成立）。

## 5. 空态渲染（PlaylistDetailScreen 小改动）

现有 `PlaylistDetailScreen` 在 `songs.isEmpty() && !isLoading` 时渲染空白。补一个空态文本块，改后的主体分支：

- `state.isLoading && visibleSongs.isNotEmpty()` → 顶部 `LinearProgressIndicator`（现有）
- `state.isLoading && visibleSongs.isEmpty()` → 居中 `CircularProgressIndicator`（现有）
- `visibleSongs.isEmpty()` → 居中文本 `Text(state.error ?: "暂无歌曲")`（**新增**）
- 否则 → `itemsIndexed(visibleSongs)`（现有）

普通空歌单顺带显示「暂无歌曲」（现有行为是空白页，属无害改进）；每周歌单显示第 4 节的不足/失败/加载失败文案。

## 6. 移除旧 UI + 死代码

- 删除文件 `app/src/main/java/com/ncm/app/ui/screens/weekly/WeeklyRecommendationScreen.kt`。
- `NavGraph.kt`：
  - 删除 `import com.ncm.app.ui.screens.weekly.WeeklyRecommendationScreen`。
  - 删除 `Routes.WEEKLY = "weekly"` 常量。
  - 删除 `composable(Routes.WEEKLY)` 块。
  - 删除 MyScreen 调用点的 `onWeeklyClick`。
- `WeeklyRecUi.kt`：
  - 删除 `WeeklyRecUiState.Success.displayWeekLabel` 字段与 `Success.seedCount` 字段（`seedCount` 仅被已删除的 `successSubtitle` 消费）。`WeeklyRecUiState.Success` 变为 `Success(songs)`。
  - 删除 `WeeklyRecUiMapper.displayWeekLabel(LocalDate)` 与 `successSubtitle(Int, Int)`。
  - `toUiState(result, zoneId = ZoneId.systemDefault())` 简化为 `toUiState(result)`，删除未使用的 `zoneId` 参数（该参数是先前记录的 Minor 死参数）；Success 分支构造为 `Success(result.songs)`。
  - 清理因此不再使用的 import（`LocalDate`、`ZoneId`、`IsoFields`）。
  - 新增 `WEEKLY_PLAYLIST_ID` 与 `weeklyRow`。
  - 注：`WeeklyRecResult.Success.seedCount`（domain result 层）保留不动，仍是 use case 的输出。

## 7. 测试

- `WeeklyRecUiMapperTest.kt`：
  - 保留 `toUiState` 的 Success / InsufficientData / Failure 三个分支测试。
  - 删除 `displayWeekLabel` / `successSubtitle` 相关测试（`weekLabelFormatUsesIsoWeek`、`successSubtitleFormat`、第 53 周用例）。
  - 新增 `weeklyRow` 测试：
    - Success → id=-1、name="每周推荐"、cover=首曲 cover、trackCount=首曲数。
    - Loading / InsufficientData / Error → id=-1、name="每周推荐"、trackCount=0。
- 每周播放记录 / 算法 / 缓存 / 协调器 / single-flight 等既有测试全部不动，全量回归。
- 不新增 MainViewModel 单元测试（与本项目现有约定一致；`loadWeeklyPlaylistDetail` 为协程 + Flow 逻辑）。

## 8. README 更新（GC #13 精确字符串）

功能列表第 27 行（当前为「每周推荐：按上一个完整自然周的播放记录生成相似歌曲推荐（我的页面入口）。」）改为：

```
- 每周推荐：按上一个完整自然周的播放记录生成相似歌曲推荐，以「每周推荐」歌单形式展示在「我的」页歌单列表。
```

质量门禁测试覆盖列表第 79 行不变（「播放合格判定、播放记录去重与裁剪、推荐生成 single-flight、缓存命中零请求、退出登录清理顺序」仍成立）。

## 9. 涉及文件

| 文件 | 改动 |
|---|---|
| `domain/weekly/WeeklyRecUi.kt` | 删死代码、新增常量 + `weeklyRow` |
| `ui/screens/my/MyScreen.kt` | 删卡片/参数、顶部加歌单行 |
| `viewmodel/MainViewModel.kt` | `loadPlaylistDetail` 分支 + `loadWeeklyPlaylistDetail()` |
| `ui/navigation/NavGraph.kt` | 删路由/导入/onWeeklyClick |
| `ui/screens/playlist/PlaylistDetailScreen.kt` | 加空态文本块 |
| `ui/screens/weekly/WeeklyRecommendationScreen.kt` | 删除文件 |
| `test/.../WeeklyRecUiMapperTest.kt` | 删旧测试、加 `weeklyRow` 测试 |
| `README.md` | 第 27 行改文案 |

## 10. 非目标（明确不做）

- 不把每周歌曲持久化为真实本地歌单表（避免与 `weekly_rec` 缓存重复存储）。
- 不改每周生成算法 / 缓存键 / single-flight / logout 清理逻辑。
- 不在详情页加重试按钮（重进页面即重试）。
- 不显示周标签与生成说明。

# 每周推荐改造为歌单「每周推荐」实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把每周推荐从独立卡片 + 独立详情页，改造成「我的」页歌单列表里的一行「每周推荐」，点开复用标准歌单详情页（PlaylistDetailScreen）。

**Architecture:** 本地合成歌单。保留每周生成的整套数据逻辑（播放记录、相似歌曲算法、缓存、single-flight、logout 清理）作为数据源；UI 层用一个哨兵 id `WEEKLY_PLAYLIST_ID = -1L` 合成歌单行与歌单详情。`MyScreen` 歌单列表顶部渲染 `MyPlaylistItem(weeklyRow(...))`；点开走现有 `Routes.playlistDetail(-1)` → `PlaylistDetailScreen` → `MainViewModel.loadPlaylistDetail(-1)` 分支到新的 `loadWeeklyPlaylistDetail()`（生成落定 + 水合 + 空态文案）。最终删除旧卡片、旧页面、旧路由与死代码。

**Tech Stack:** Kotlin 2.0.0、AGP 8.13.2、Jetpack Compose + Material 3、StateFlow、`.\gradlew.bat --no-daemon`。

**设计文档（spec，用户已确认）：** `docs/superpowers/specs/2026-08-01-weekly-playlist-design.md`

## Global Constraints

- **GC #12 构建命令**：一律 `.\gradlew.bat --no-daemon`（本机 Git Bash 下等价 `./gradlew.bat --no-daemon`）。项目无 version catalog；minSdk 26 / compileSdk 34 / Kotlin 2.0.0 / AGP 8.13.2。不新增依赖。
- **GC #5**：新代码不用 `runCatching`。
- **GC #11**：每个任务只碰任务「Files」节列出的文件。`docs/superpowers/plans/` 与 `docs/superpowers/specs/` **保持 untracked，绝不提交**。`.superpowers/` 已被 gitignore。
- **GC #13 精确 UI 字符串**（复制不得改写）：
  - 歌单名：`每周推荐`
  - 空态-数据不足：`本周听歌数据不足，多听几首下周再来`
  - 空态-水合失败：`歌曲加载失败，请重试`
  - 空态-普通空歌单：`暂无歌曲`
  - 详情头副标题用现有模板 `网易云音乐 · $count 首歌`（PlaylistDetailScreen 内已有，不动）
- **哨兵 id**：`const val WEEKLY_PLAYLIST_ID = -1L`（网易云歌单 id 恒为正数，永不冲突）。
- **不显示周标签**（第 X 周）与「根据上周 N 首生成」说明；详情页无重试按钮（重进页面即重试）。
- 每周数据逻辑（`WeeklyPlayLog`、`WeeklyRecCache`、`GenerateWeeklyRecommendationUseCase`、`WeeklyCacheCleaner`、`WeeklyLogoutCoordinator`、`MusicRepository.getSimilarSongs/parseSimilarSongs`）**一律不动**。

**基线测试数：** 122（全绿）。Task 1 新增 2 个测试 → 124；Task 5 删除 2 个测试 → 回到 122。

---

### Task 1: 新增 `WEEKLY_PLAYLIST_ID` 常量与 `weeklyRow` 纯函数

**Files:**
- Modify: `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt`
- Test: `app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecUiMapperTest.kt`

**Interfaces:**
- Produces（后续 Task 2/3 依赖）：
  - `const val WEEKLY_PLAYLIST_ID: Long = -1L`（包级，包 `com.ncm.app.domain.weekly`）
  - `fun weeklyRow(state: WeeklyRecUiState): Playlist`（包级，返回 `com.ncm.app.data.model.Playlist`）

本任务为**纯新增**：不删除旧字段/旧函数，旧卡片与旧页面仍可编译。只依赖已有的 `WeeklyRecUiState`/`CachedSong`/`Playlist`。

- [ ] **Step 1: 给测试文件追加 2 个失败测试**

在 `WeeklyRecUiMapperTest.kt` 文件末尾（`successSubtitleFormat` 之后）追加：

```kotlin

    @Test
    fun weeklyRowSuccessUsesFirstSongCoverAndCount() {
        val state = WeeklyRecUiState.Success(
            songs = listOf(
                CachedSong(songId = 1, name = "A", artists = listOf("X"), cover = "http://cover/1"),
                CachedSong(songId = 2, name = "B", artists = listOf("Y"), cover = null)
            )
        )
        val row = weeklyRow(state)
        assertEquals(WEEKLY_PLAYLIST_ID, row.id)
        assertEquals("每周推荐", row.name)
        assertEquals("http://cover/1", row.cover)
        assertEquals(2, row.trackCount)
    }

    @Test
    fun weeklyRowNonSuccessShowsZeroCountAndNoCover() {
        val rows = listOf(
            weeklyRow(WeeklyRecUiState.Loading),
            weeklyRow(WeeklyRecUiState.InsufficientData(validPlayCount = 1, distinctSongCount = 1)),
            weeklyRow(WeeklyRecUiState.Error("boom"))
        )
        for (row in rows) {
            assertEquals(WEEKLY_PLAYLIST_ID, row.id)
            assertEquals("每周推荐", row.name)
            assertEquals(0, row.trackCount)
        }
    }
```

（不需要新增 import：`WeeklyRecUiState`/`CachedSong`/`weeklyRow`/`WEEKLY_PLAYLIST_ID` 同包；`row.id` 等不出现 `Playlist` 类型名。）

- [ ] **Step 2: 运行测试验证失败**

```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.domain.weekly.WeeklyRecUiMapperTest"
```

Expected: FAIL，编译错误 `Unresolved reference 'weeklyRow'` 与 `'WEEKLY_PLAYLIST_ID'`。

- [ ] **Step 3: 实现常量与函数**

`WeeklyRecUi.kt` 顶部（`package` 之后、`sealed class WeeklyRecUiState` 之前）插入 import 与常量：

```kotlin
import com.ncm.app.data.model.Playlist

const val WEEKLY_PLAYLIST_ID = -1L
```

文件末尾追加：

```kotlin

/** 每周推荐以标准歌单行的形式展示（id 为哨兵值，永不与真实歌单冲突）。 */
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

- [ ] **Step 4: 运行测试验证通过**

```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.domain.weekly.WeeklyRecUiMapperTest"
```

Expected: BUILD SUCCESSFUL，7/7 通过（原 5 + 新 2）。

- [ ] **Step 5: 全量回归 + 提交**

```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL，**124** tests，0 failures。

```bash
git add app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecUiMapperTest.kt
git commit -m "feat(weekly): add weekly playlist row mapping"
```

---

### Task 2: 「我的」页歌单列表顶部加「每周推荐」行，移除旧卡片

**Files:**
- Modify: `app/src/main/java/com/ncm/app/ui/screens/my/MyScreen.kt`
- Modify: `app/src/main/java/com/ncm/app/ui/navigation/NavGraph.kt`（仅 MyScreen 调用点）

**Interfaces:**
- Consumes: `weeklyRow(state: WeeklyRecUiState): Playlist`、`WEEKLY_PLAYLIST_ID`（Task 1）
- 本任务后：`Routes.WEEKLY` 路由与 `WeeklyRecommendationScreen` 仍在但**无人导航到**（Task 5 拆除），保持可编译。

- [ ] **Step 1: 改 MyScreen imports**

删除 4 个因卡片移除而失效的 import（按文件现状逐行删除）：

```kotlin
import androidx.compose.material.icons.Icons
```
```kotlin
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import com.ncm.app.domain.weekly.WeeklyRecUiMapper
import com.ncm.app.domain.weekly.WeeklyRecUiState
```

把最后两行替换为：

```kotlin
import com.ncm.app.domain.weekly.WEEKLY_PLAYLIST_ID
import com.ncm.app.domain.weekly.weeklyRow
```

（说明：MyScreen 中其余 `Icons.*` 用法均全限定，裸 `Icons` import 不再需要；`WeeklyRecUiState` 类型名不再在代码中出现。）

- [ ] **Step 2: 删 `onWeeklyClick` 参数**

`MyScreen` 函数签名删除一行：

```kotlin
    onWeeklyClick: () -> Unit,
```

- [ ] **Step 3: 卡片 item 替换为歌单行 item**

把这段（现状第 121-128 行）：

```kotlin
        item {
            WeeklyRecommendationCard(
                state = weeklyRecState,
                onClick = onWeeklyClick,
                onRetry = { viewModel.loadWeeklyRecommendation() }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
```

替换为：

```kotlin
        item {
            MyPlaylistItem(
                playlist = weeklyRow(weeklyRecState),
                onClick = { onPlaylistClick(WEEKLY_PLAYLIST_ID) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
```

- [ ] **Step 4: 删除 `WeeklyRecommendationCard` 私有 composable**

删除从 `@Composable\nprivate fun WeeklyRecommendationCard(` 到文件尾 `}`（现状第 720-831 行）的整段。`MyPlaylistItem`（第 694-718 行）保留。

- [ ] **Step 5: 删 NavGraph 的 MyScreen 调用点 `onWeeklyClick`**

删除 NavGraph.kt 中 MyScreen 调用块里的：

```kotlin
                onWeeklyClick = {
                    navController.navigate(Routes.WEEKLY)
                },
```

（`Routes.WEEKLY` 常量与其 composable 本任务不动。）

- [ ] **Step 6: 编译验证**

```bash
.\gradlew.bat --no-daemon :app:assembleDebug
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 全量回归 + 提交**

```bash
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL，**124** tests，0 failures。

```bash
git add app/src/main/java/com/ncm/app/ui/screens/my/MyScreen.kt app/src/main/java/com/ncm/app/ui/navigation/NavGraph.kt
git commit -m "feat(weekly): show weekly recommendation as a playlist row on My page"
```

---

### Task 3: `MainViewModel.loadPlaylistDetail` 加每周分支 + `loadWeeklyPlaylistDetail()`

**Files:**
- Modify: `app/src/main/java/com/ncm/app/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: `WEEKLY_PLAYLIST_ID`（Task 1）；已有的 `weeklyRecState`、`hydrateWeeklyDetailSongsNow()`（均不动）。
- Produces: 私有 `loadWeeklyPlaylistDetail()` —— 复用现有 `PlaylistDetailUiState`/`PlaylistMeta`，向 `_playlistState` 写入每周详情。

- [ ] **Step 1: 加 imports**

在 `import com.ncm.app.domain.weekly.WeeklyRecUiState`（现状第 16 行）之后加：

```kotlin
import com.ncm.app.domain.weekly.WEEKLY_PLAYLIST_ID
```

在 `import kotlinx.coroutines.flow.StateFlow`（现状第 24 行）之后加：

```kotlin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
```

- [ ] **Step 2: `loadPlaylistDetail` 顶部加分支**

把（现状第 214 行起）：

```kotlin
    fun loadPlaylistDetail(id: Long, force: Boolean = false) {
        if (!force) {
```

改为：

```kotlin
    fun loadPlaylistDetail(id: Long, force: Boolean = false) {
        if (id == WEEKLY_PLAYLIST_ID) {
            loadWeeklyPlaylistDetail()
            return
        }
        if (!force) {
```

其余原逻辑（playlistCache、getPlaylistTracks 等）逐字节保留。

- [ ] **Step 3: 新增 `loadWeeklyPlaylistDetail()`**

在 `loadPlaylistDetail` 方法结束的 `}` 之后、`fun loadArtistDetail` 之前插入：

```kotlin

    /** 每周推荐以标准歌单详情呈现：生成落定后水合歌单，无数据/失败时给出空态文案。 */
    private fun loadWeeklyPlaylistDetail() {
        if (_playlistState.value.isLoading && _playlistState.value.loadedPlaylistId == WEEKLY_PLAYLIST_ID) return
        viewModelScope.launch {
            loadWeeklyRecommendation()
            _weeklyRecState.filter { it !is WeeklyRecUiState.Loading }.first()
            val rec = _weeklyRecState.value
            val count = (rec as? WeeklyRecUiState.Success)?.songs?.size ?: 0
            val cover = (rec as? WeeklyRecUiState.Success)?.songs?.firstOrNull()?.cover
            val meta = PlaylistMeta(
                id = WEEKLY_PLAYLIST_ID,
                name = "每周推荐",
                cover = cover,
                trackCount = count
            )
            _playlistState.value = PlaylistDetailUiState(
                playlist = meta,
                isLoading = true,
                loadedPlaylistId = WEEKLY_PLAYLIST_ID
            )
            val songs = hydrateWeeklyDetailSongsNow().orEmpty()
            val error = when (rec) {
                is WeeklyRecUiState.InsufficientData -> "本周听歌数据不足，多听几首下周再来"
                is WeeklyRecUiState.Error -> rec.message
                is WeeklyRecUiState.Success -> if (songs.isEmpty()) "歌曲加载失败，请重试" else null
                WeeklyRecUiState.Loading -> null
            }
            _playlistState.value = PlaylistDetailUiState(
                playlist = meta,
                songs = songs,
                isLoading = false,
                error = error,
                loadedPlaylistId = WEEKLY_PLAYLIST_ID,
                isFullyLoaded = true
            )
        }
    }
```

（依赖：`PlaylistMeta`/`PlaylistDetailUiState` 已由 `import com.ncm.app.data.model.*` 与同包提供；`hydrateWeeklyDetailSongsNow()` 为现有 `suspend fun`。）

- [ ] **Step 4: 编译 + 全量回归**

```bash
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: 均 BUILD SUCCESSFUL；**124** tests，0 failures。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/ncm/app/viewmodel/MainViewModel.kt
git commit -m "feat(weekly): load weekly playlist detail through the standard playlist branch"
```

---

### Task 4: PlaylistDetailScreen 空态渲染

**Files:**
- Modify: `app/src/main/java/com/ncm/app/ui/screens/playlist/PlaylistDetailScreen.kt`

**Interfaces:**
- Consumes: 既有 `PlaylistDetailUiState.error`（Task 3 的每周分支会写入它）。
- 行为：`songs.isEmpty() && !isLoading` 时显示 `state.error ?: "暂无歌曲"` 居中文本。普通空歌单也受益（原为空白页）。

- [ ] **Step 1: 主体分支加空态**

把（现状第 129-152 行）：

```kotlin
            if (state.isLoading && visibleSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Green500)
                    }
                }
            } else {
                itemsIndexed(
                    items = visibleSongs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    SongListItem(
                        index = index + 1,
                        song = song,
                        onClick = { onSongClick(song.id) },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
```

替换为：

```kotlin
            if (state.isLoading && visibleSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Green500)
                    }
                }
            } else if (visibleSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.error ?: "暂无歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = visibleSongs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    SongListItem(
                        index = index + 1,
                        song = song,
                        onClick = { onSongClick(song.id) },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
```

（所需 `Box`/`fillMaxWidth`/`padding`/`Alignment`/`MaterialTheme`/`TextTertiary` 均已由现有 wildcard import 覆盖，无需加 import。）

- [ ] **Step 2: 编译 + 全量回归**

```bash
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: 均 BUILD SUCCESSFUL；**124** tests，0 failures。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/ncm/app/ui/screens/playlist/PlaylistDetailScreen.kt
git commit -m "feat(weekly): show empty state in playlist detail screen"
```

---

### Task 5: 拆除旧页面与死代码，更新 README

**Files:**
- Delete: `app/src/main/java/com/ncm/app/ui/screens/weekly/WeeklyRecommendationScreen.kt`
- Modify: `app/src/main/java/com/ncm/app/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt`
- Modify: `app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecUiMapperTest.kt`
- Modify: `README.md`

**前置：** Task 2 已移除卡片/参数，Task 3 已接管详情加载，本任务拆除剩余引用与死代码。

- [ ] **Step 1: 删除独立页面文件**

```bash
git rm app/src/main/java/com/ncm/app/ui/screens/weekly/WeeklyRecommendationScreen.kt
```

- [ ] **Step 2: NavGraph 清理**

删除 `import com.ncm.app.ui.screens.weekly.WeeklyRecommendationScreen`（现状第 21 行）。

删除 `Routes` 对象中的一行：

```kotlin
    const val WEEKLY = "weekly"
```

删除整个 composable 块（现状第 177-184 行）：

```kotlin
        composable(Routes.WEEKLY) {
            WeeklyRecommendationScreen(
                onBack = { navController.popBackStack() },
                onOpenPlayer = onOpenPlayer,
                playerViewModel = playerViewModel,
                viewModel = mainViewModel
            )
        }
```

- [ ] **Step 3: `WeeklyRecUi.kt` 删除死代码**

整个文件替换为（`displayWeekLabel`/`successSubtitle`/`Success` 冗余字段/`toUiState` 的 `zoneId` 参数全部移除，`LocalDate`/`ZoneId`/`IsoFields` import 一并清除）：

```kotlin
package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Playlist

const val WEEKLY_PLAYLIST_ID = -1L

sealed class WeeklyRecUiState {
    object Loading : WeeklyRecUiState()
    data class Success(val songs: List<CachedSong>) : WeeklyRecUiState()
    data class InsufficientData(val validPlayCount: Int, val distinctSongCount: Int) : WeeklyRecUiState()
    data class Error(val message: String) : WeeklyRecUiState()
}

object WeeklyRecUiMapper {
    fun toUiState(result: WeeklyRecResult): WeeklyRecUiState = when (result) {
        is WeeklyRecResult.Success -> WeeklyRecUiState.Success(songs = result.songs)
        is WeeklyRecResult.InsufficientData -> WeeklyRecUiState.InsufficientData(
            validPlayCount = result.validPlayCount,
            distinctSongCount = result.distinctSongCount
        )
        is WeeklyRecResult.Failure -> WeeklyRecUiState.Error(result.message ?: "获取每周推荐失败，请稍后重试")
    }
}

/** 每周推荐以标准歌单行的形式展示（id 为哨兵值，永不与真实歌单冲突）。 */
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

（注：`WeeklyRecResult.Success.seedCount`/`displayWeekStart` 在 `WeeklyRecModels.kt` 的 domain result 层，**保留不动**。）

- [ ] **Step 4: `WeeklyRecUiMapperTest.kt` 更新**

整个文件替换为（删 `weekLabelFormatUsesIsoWeek`/`successSubtitleFormat`，改 `toUiState` 三测试去掉 `zoneId` 与已删字段断言，保留 Task 1 的两个 `weeklyRow` 测试）：

```kotlin
package com.ncm.app.domain.weekly

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyRecUiMapperTest {

    @Test
    fun successMapsToUiState() {
        val result = WeeklyRecResult.Success(
            songs = listOf(CachedSong(songId = 1, name = "A", artists = listOf("X"), cover = null)),
            seedCount = 8,
            displayWeekStart = LocalDate.of(2026, 7, 27)
        )
        val ui = WeeklyRecUiMapper.toUiState(result)
        assertTrue(ui is WeeklyRecUiState.Success)
        ui as WeeklyRecUiState.Success
        assertEquals(1, ui.songs.size)
    }

    @Test
    fun insufficientDataMapsToUiState() {
        val ui = WeeklyRecUiMapper.toUiState(
            WeeklyRecResult.InsufficientData(validPlayCount = 3, distinctSongCount = 1)
        )
        assertTrue(ui is WeeklyRecUiState.InsufficientData)
        ui as WeeklyRecUiState.InsufficientData
        assertEquals(3, ui.validPlayCount)
        assertEquals(1, ui.distinctSongCount)
    }

    @Test
    fun failureMapsToErrorWithFallbackMessage() {
        val ui = WeeklyRecUiMapper.toUiState(WeeklyRecResult.Failure(null))
        assertTrue(ui is WeeklyRecUiState.Error)
        assertEquals("获取每周推荐失败，请稍后重试", (ui as WeeklyRecUiState.Error).message)

        val withMessage = WeeklyRecUiMapper.toUiState(WeeklyRecResult.Failure("自定义"))
        assertEquals("自定义", (withMessage as WeeklyRecUiState.Error).message)
    }

    @Test
    fun weeklyRowSuccessUsesFirstSongCoverAndCount() {
        val state = WeeklyRecUiState.Success(
            songs = listOf(
                CachedSong(songId = 1, name = "A", artists = listOf("X"), cover = "http://cover/1"),
                CachedSong(songId = 2, name = "B", artists = listOf("Y"), cover = null)
            )
        )
        val row = weeklyRow(state)
        assertEquals(WEEKLY_PLAYLIST_ID, row.id)
        assertEquals("每周推荐", row.name)
        assertEquals("http://cover/1", row.cover)
        assertEquals(2, row.trackCount)
    }

    @Test
    fun weeklyRowNonSuccessShowsZeroCountAndNoCover() {
        val rows = listOf(
            weeklyRow(WeeklyRecUiState.Loading),
            weeklyRow(WeeklyRecUiState.InsufficientData(validPlayCount = 1, distinctSongCount = 1)),
            weeklyRow(WeeklyRecUiState.Error("boom"))
        )
        for (row in rows) {
            assertEquals(WEEKLY_PLAYLIST_ID, row.id)
            assertEquals("每周推荐", row.name)
            assertEquals(0, row.trackCount)
        }
    }
}
```

- [ ] **Step 5: README 更新功能描述**

`README.md` 第 27 行：

```markdown
- 每周推荐：按上一个完整自然周的播放记录生成相似歌曲推荐（我的页面入口）。
```

改为：

```markdown
- 每周推荐：按上一个完整自然周的播放记录生成相似歌曲推荐，以「每周推荐」歌单形式展示在「我的」页歌单列表。
```

（第 79 行质量门禁测试覆盖列表**不改**。）

- [ ] **Step 6: 编译 + 全量回归**

```bash
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

Expected: 均 BUILD SUCCESSFUL；**122** tests，0 failures。

- [ ] **Step 7: 确认 git status 干净 + 提交**

```bash
git status --short
```

Expected: 仅 `?? docs/superpowers/plans/` 与 `?? docs/superpowers/specs/`（保持 untracked）；无其他改动。

```bash
git add app/src/main/java/com/ncm/app/ui/navigation/NavGraph.kt app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecUiMapperTest.kt README.md
git commit -m "refactor(weekly): remove standalone weekly page and dead code"
```

（`WeeklyRecommendationScreen.kt` 已在 Step 1 由 `git rm` 暂存。）

---

## Self-Review 记录

**1. Spec 覆盖：**
- 哨兵 id + `weeklyRow` → Task 1
- 我的页歌单行（最顶部、始终显示、可点）→ Task 2
- 详情复用标准 PlaylistDetailScreen + 路由 `playlist/-1` + `loadPlaylistDetail` 分支 + 空态文案 → Task 3、Task 4
- 不显示周标签 / 无重试按钮 / 头部标准样式 → Task 3（meta 名「每周推荐」、PlaylistDetailScreen 头部模板不动）
- 移除旧卡片/页面/路由/死代码 → Task 2（卡片）、Task 5（页面/路由/死代码）
- 测试：weeklyRow 新增、旧 mapper 测试清理 → Task 1、Task 5
- README 第 27 行 → Task 5

**2. 占位符扫描：** 无 TBD/TODO；每个改码步骤均给出完整代码与预期输出。

**3. 类型一致性：**
- `weeklyRow` 返回 `Playlist`、参数 `WeeklyRecUiState` — Task 1 定义，Task 2 消费，一致。
- `WEEKLY_PLAYLIST_ID = -1L` — Task 1 定义，Task 2/3 消费，一致。
- `loadWeeklyPlaylistDetail()` 私有，写 `_playlistState.value`（`PlaylistDetailUiState`）— Task 3 定义，Task 4 渲染其 `error`，一致。
- `PlaylistMeta(id, name, cover, trackCount)` 现有字段，一致。
- 测试数：基线 122 → Task 1 后 124 → Task 5 后 122，逐步核对。

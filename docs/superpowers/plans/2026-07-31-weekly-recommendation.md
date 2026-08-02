# 每周推荐（Weekly Recommendation）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「我的」页面增加一个「每周推荐」虚拟歌单：基于**上一个完整自然周**（上周一 00:00 → 本周一 00:00，设备本地时区）的真实播放记录，用相似歌曲聚合算法生成 30 首左右推荐曲目，每周重新生成、周内缓存命中零请求。

**Architecture:** 播放层在每次曲目切换时开启一个"播放会话"，用 `PlaySessionAccumulator` 累计真实播放毫秒数，达到有效播放阈值后把一条去重事件写入 Room 表 `weekly_play_event`（唯一索引 `(userId, playbackSessionId)`，`INSERT OR IGNORE`）。「每周推荐」由 `GenerateWeeklyRecommendationUseCase` 按自然周聚合上周听歌统计 → 挑选种子 → 调 `/api/simi/song` 拉相似歌曲（含 Plan A/B 双路径）→ 打分去重 → 缓存到 `SharedPreferences`（kotlinx.serialization，单键 `weekly_rec:{userId}`）。页面打开先清缓存再读缓存，命中即零请求。

**Tech Stack:** Kotlin 2.0.0、Jetpack Compose + Material 3、Room 2.6.1（**项目首次引入**，KSP `2.0.0-1.0.24`）、kotlinx-serialization-json 1.7.3（**新增**，项目其余地方用 Gson）、kotlinx-coroutines（single-flight + `withTimeout`）、Robolectric 4.14.1（**新增**，Room 内存库单测）、kotlinx-coroutines-test 1.8.1（**新增**）、JUnit4。

## Global Constraints

从设计文档 `docs/superpowers/specs/2026-07-31-weekly-recommendation-design.md` 复制的全局约束（每个任务都隐式包含本节）：

1. **自然周**：设备本地时区 `ZoneId.systemDefault()`；周一为 ISO 周一；本周开始 = `LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))`；数据源周 = 显示周减一周。
2. **播放记录去重**：`playbackSessionId = "${song.id}:${sessionStartedAt}"`（确定性、去重安全），唯一索引 `(userId, playbackSessionId)`，`INSERT OR IGNORE`。
3. **`accumulatedPlayedMs` 是真实累计播放时长，不是进度条位置**：`delta = currentPosition - previousPosition`，仅当 `isPlaying && !isSeeking && delta in 0..3000` 才累计；`MAX_REASONABLE_POSITION_DELTA = 3000L`。
4. **有效播放判定**：`accumulatedPlayedMs >= 30_000 || (durationMs > 0 && accumulatedPlayedMs.toDouble() / durationMs >= 0.5)`；同一会话只触发一次。
5. **网络/协程代码禁止 `runCatching`**；每个 `withTimeout` 块内 `TimeoutCancellationException` 必须排在 `CancellationException` 之前（`TimeoutCancellationException` 是 `CancellationException` 子类）；`CancellationException` 一律重抛。
6. **Room 写入**统一用 `database.withTransaction { }`（`androidx.room.withTransaction`，room-ktx），不用裸 `@Transaction` 注解。
7. **缓存单键** `weekly_rec:{userId}`；schema 版本不匹配或解析失败 → 删除该 key（`apply()`）。
8. **Single-flight**：同一账号同一显示周同时只跑一个生成任务；不同 key 取消旧任务；调用方取消 ≠ 后台任务取消。
9. **退出登录严格顺序**：① `session.invalidate()`（sessionGeneration +1）→ ② `useCase.cancelGenerationForUser(userId)`（cancelAndJoin 等待）→ ③ `cleaner.clearWeeklyUserData(userId)`（Room 删除 + prefs `commit()` 重试一次，返回 `ClearWeeklyDataResult`，失败只记日志 + 弹 Toast，不阻塞退出）。
10. **Room 迁移**：本项目无既有 Room，DB 从 version 1 开始，`exportSchema = false`；以后改表必须写 MIGRATION，**禁止** `fallbackToDestructiveMigration()`。
11. **不触碰与本功能无关的改动**：不要修改 `data/update/AppUpdateChecker.kt`、`.adb-diagnose/`、`.codegraph/`、`RhythmAudioProcessor.kt`（git status 中已有的其他未提交改动，保持原样）。
12. 版本：minSdk 26（`java.time` 可用）、compileSdk 34、targetSdk 34、Kotlin 2.0.0、AGP 8.13.2、Java 17；构建命令一律 `.\gradlew.bat --no-daemon`；**没有 gradle version catalog**，直接写版本号。
13. UI 文案（精确字符串）：卡片标题 `每周推荐`；加载 `正在根据本周听歌记录生成…`；数据不足 `听歌数据不足，多听几首下周再来`；副标题 `根据上周 {seed} 首常听歌曲生成 · {songCount} 首`；错误默认 `获取每周推荐失败，请稍后重试`；清理失败 Toast `部分本地数据清理失败`；详情页加载 Toast `正在加载歌曲…` / `歌曲加载失败，请重试`。

---

## 文件结构

**新建（main）：**
- `app/src/main/java/com/ncm/app/data/weekly/WeeklyPlayLog.kt` — 实体、统计 POJO、DAO、数据库、端口、外观门面
- `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecModels.kt` — SimilarSong / WeeklyRecResult / CachedSong / WeeklyRecCache / WeeklyRecCacheResult / `Song.toCachedSong()`
- `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecommendationSource.kt` — 网络源接口
- `app/src/main/java/com/ncm/app/data/weekly/WeeklyRecommendationStore.kt` — 缓存存储 + WeeklyRecCachePort
- `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecommendationAlgorithm.kt` — 算法（Seed/CandidateEdge/selectSeeds/rankCandidates）
- `app/src/main/java/com/ncm/app/playback/PlaySessionAccumulator.kt` — 播放累计器
- `app/src/main/java/com/ncm/app/data/weekly/WeeklyCacheCleaner.kt` — 清理器 + ClearWeeklyDataResult + WeeklyLogoutCoordinator
- `app/src/main/java/com/ncm/app/domain/weekly/GenerateWeeklyRecommendationUseCase.kt` — 用例（single-flight + 编排）+ GenerationKey + weekBounds
- `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt` — WeeklyRecUiState + WeeklyRecUiMapper
- `app/src/main/java/com/ncm/app/ui/screens/weekly/WeeklyRecommendationScreen.kt` — 每周推荐详情页

**新建（test）：**
- `app/src/test/java/com/ncm/app/data/weekly/WeeklyPlayLogTest.kt`（Robolectric）
- `app/src/test/java/com/ncm/app/data/repository/MusicRepositorySimilarSongsTest.kt`（纯 JVM）
- `app/src/test/java/com/ncm/app/data/weekly/WeeklyRecommendationStoreTest.kt`（Robolectric）
- `app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecommendationAlgorithmTest.kt`（纯 JVM）
- `app/src/test/java/com/ncm/app/playback/PlaySessionAccumulatorTest.kt`（纯 JVM）
- `app/src/test/java/com/ncm/app/data/weekly/WeeklyCacheCleanerTest.kt`（Robolectric）
- `app/src/test/java/com/ncm/app/data/weekly/WeeklyLogoutCoordinatorTest.kt`（纯 JVM）
- `app/src/test/java/com/ncm/app/data/SessionGenerationTest.kt`（Robolectric）
- `app/src/test/java/com/ncm/app/domain/weekly/GenerateWeeklyRecommendationUseCaseTest.kt`（纯 JVM + runTest）
- `app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecUiMapperTest.kt`（纯 JVM）

**修改（main）：**
- `build.gradle.kts` — 根插件块加 KSP + serialization
- `app/build.gradle.kts` — 插件、testOptions、依赖
- `app/src/main/java/com/ncm/app/data/api/NeteaseApi.kt` — 加 `getSimilarSongs`
- `app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt` — 实现 `WeeklyRecommendationSource`、`parseSimilarSongs`
- `app/src/main/java/com/ncm/app/data/SessionManager.kt` — 加 `sessionGeneration` / `invalidate()` / saveLoginInfo 递增
- `app/src/main/java/com/ncm/app/NeteaseApp.kt` — 周推荐 DI + `applicationScope` + 启动清理
- `app/src/main/java/com/ncm/app/playback/AppPlayer.kt` — 会话身份 + accumulator
- `app/src/main/java/com/ncm/app/viewmodel/PlayerViewModel.kt` — 播放会话钩子 + 有效播放记录
- `app/src/main/java/com/ncm/app/viewmodel/MainViewModel.kt` — 周推荐状态 + 加载 + 退出登录严格顺序
- `app/src/main/java/com/ncm/app/ui/screens/my/MyScreen.kt` — 卡片 + LaunchedEffect + onWeeklyClick 参数
- `app/src/main/java/com/ncm/app/ui/navigation/NavGraph.kt` — `Routes.WEEKLY` + 路由 + MyScreen 接线
- `app/src/main/java/com/ncm/app/MainActivity.kt` — 退出清理失败 Toast
- `README.md` — 功能与测试覆盖段落

---

### Task 1: Gradle 依赖（Room + KSP + serialization + Robolectric + coroutines-test）

**Files:**
- Modify: `build.gradle.kts`（根，第 1-5 行 plugins 块）
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: 无（构建基础设施）
- Produces: `app/build.gradle.kts` 内可用的 KSP / serialization 插件与 Room / coroutines-test / Robolectric 依赖；供 Task 2-8 使用。

- [ ] **Step 1: 修改根 `build.gradle.kts`，加入两个新插件（`apply false`）**

把整个文件改成：

```kotlin
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false
}
```

- [ ] **Step 2: 修改 `app/build.gradle.kts`**

插件块（第 4-8 行）改成：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}
```

在 `android { }` 块末尾（第 119 行 `buildFeatures { ... }` 之后、`}` 之前）加入：

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

在 `dependencies { }` 块内（第 161 行 Gson 注释之后）追加：

```kotlin
    // Weekly recommendation: Room + serialization
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Weekly recommendation: unit-test support
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
```

- [ ] **Step 3: 验证构建配置可解析**

Run: `.\gradlew.bat --no-daemon :app:assembleDebug`
Expected: BUILD SUCCESSFUL（无编译产物变更，因为还没有引用新依赖；KSP/插件被解析并加载即可）

- [ ] **Step 4: 提交**

```bash
git add build.gradle.kts app/build.gradle.kts
git commit -m "feat(weekly): add Room, kotlinx-serialization, Robolectric and coroutines-test dependencies"
```

---

### Task 2: Room 播放记录数据层（WeeklyPlayLog）

**Files:**
- Create: `app/src/main/java/com/ncm/app/data/weekly/WeeklyPlayLog.kt`
- Test: `app/src/test/java/com/ncm/app/data/weekly/WeeklyPlayLogTest.kt`

**Interfaces:**
- Consumes: 无（独立数据层；Task 1 的 Room/KSP 已就绪）
- Produces:
  - `PlayEventEntity(userId: Long, songId: Long, playbackSessionId: String, sessionStartedAt: Long, id: Long = 0)`（Room @Entity，表 `weekly_play_event`）
  - `WeeklySongStat(songId: Long, playCount: Int, lastPlayedAt: Long)`
  - `WeeklyPlayLogDao`（7 个方法，见下方代码）
  - `WeeklyDatabase`（@Database version 1，`exportSchema = false`，`get(context)` 单例）
  - `WeeklyPlayLogPort`：
    ```kotlin
    interface WeeklyPlayLogPort {
        suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat>
        suspend fun pruneExpired(userId: Long, now: Long)
        suspend fun pruneAllUsersExpired(now: Long)
        suspend fun deleteAllByUser(userId: Long): Long
    }
    ```
  - `WeeklyPlayLog(database: WeeklyDatabase, zoneId: ZoneId = ZoneId.systemDefault(), nowMs: () -> Long = { System.currentTimeMillis() }) : WeeklyPlayLogPort`
    - `suspend fun record(event: PlayEventEntity)` — 事务内 insert+容量裁剪+过期清理
    - 常量 `MAX_EVENTS_PER_USER = 2000`、`CUTOFF_NATURAL_DAYS = 14`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/ncm/app/data/weekly/WeeklyPlayLogTest.kt`：

```kotlin
package com.ncm.app.data.weekly

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyPlayLogTest {

    private val zone = ZoneId.of("UTC")
    private val fixedNow = 1_750_000_000_000L
    private val dayMs = 86_400_000L

    private lateinit var db: WeeklyDatabase
    private lateinit var log: WeeklyPlayLog

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, WeeklyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        log = WeeklyPlayLog(db, zoneId = zone, nowMs = { fixedNow })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(
        userId: Long = 1L,
        songId: Long,
        sessionStart: Long,
        sessionId: String
    ) = PlayEventEntity(
        userId = userId,
        songId = songId,
        playbackSessionId = sessionId,
        sessionStartedAt = sessionStart
    )

    @Test
    fun record_dedupsByPlaybackSessionId() = runBlocking {
        val e = event(songId = 10, sessionStart = fixedNow - dayMs, sessionId = "10:abc")
        log.record(e)
        log.record(e)  // same (userId, playbackSessionId) → INSERT OR IGNORE
        assertEquals(1, db.weeklyPlayLogDao().countByUser(1L))
    }

    @Test
    fun record_appliesPerUserEventCap() = runBlocking {
        repeat(WeeklyPlayLog.MAX_EVENTS_PER_USER + 1) { i ->
            log.record(
                event(
                    songId = 1,
                    sessionStart = fixedNow - i * 1_000L,
                    sessionId = "s$i"
                )
            )
        }
        assertEquals(WeeklyPlayLog.MAX_EVENTS_PER_USER.toLong(), db.weeklyPlayLogDao().countByUser(1L).toLong())
        val all = log.read(1L, 0L, Long.MAX_VALUE)
        assertEquals(1, all.size)                      // all same song → grouped
        assertEquals(WeeklyPlayLog.MAX_EVENTS_PER_USER, all[0].playCount)
    }

    @Test
    fun record_prunesEventsOlderThanFourteenNaturalDays() = runBlocking {
        val old = event(songId = 1, sessionStart = fixedNow - 15 * dayMs, sessionId = "old")
        log.record(old)
        val fresh = event(songId = 2, sessionStart = fixedNow - dayMs, sessionId = "fresh")
        log.record(fresh)   // record always prunes on insert
        assertEquals(1, db.weeklyPlayLogDao().countByUser(1L))
    }

    @Test
    fun read_groupsStatsWithinWindow() = runBlocking {
        val start = fixedNow - 7 * dayMs
        val end = fixedNow
        log.record(event(songId = 10, sessionStart = start + 1_000, sessionId = "a1"))
        log.record(event(songId = 10, sessionStart = start + 2_000, sessionId = "a2"))
        log.record(event(songId = 10, sessionStart = start + 3_000, sessionId = "a3"))
        log.record(event(songId = 20, sessionStart = start + 4_000, sessionId = "b1"))
        val stats = log.read(1L, start, end)
        assertEquals(2, stats.size)
        val a = stats.first { it.songId == 10L }
        val b = stats.first { it.songId == 20L }
        assertEquals(3, a.playCount)
        assertEquals(start + 3_000, a.lastPlayedAt)
        assertEquals(1, b.playCount)
    }

    @Test
    fun read_excludesEventsOutsideWindow() = runBlocking {
        val start = fixedNow - 7 * dayMs
        val end = fixedNow - 3 * dayMs
        log.record(event(songId = 10, sessionStart = start + 1_000, sessionId = "inside"))
        log.record(event(songId = 20, sessionStart = fixedNow - 10 * dayMs, sessionId = "before"))
        log.record(event(songId = 30, sessionStart = fixedNow, sessionId = "after"))
        val stats = log.read(1L, start, end)
        assertEquals(listOf(10L), stats.map { it.songId })
    }

    @Test
    fun pruneExpired_removesOnlyOldEvents() = runBlocking {
        log.record(event(songId = 10, sessionStart = fixedNow - 15 * dayMs, sessionId = "old"))
        log.record(event(songId = 20, sessionStart = fixedNow - dayMs, sessionId = "new"))
        log.pruneExpired(1L, fixedNow)
        val remaining = log.read(1L, 0L, Long.MAX_VALUE)
        assertEquals(listOf(20L), remaining.map { it.songId })
    }

    @Test
    fun deleteAllByUser_returnsDeletedCountAndLeavesOtherUsers() = runBlocking {
        log.record(event(userId = 1, songId = 10, sessionStart = fixedNow - dayMs, sessionId = "u1"))
        log.record(event(userId = 2, songId = 20, sessionStart = fixedNow - dayMs, sessionId = "u2"))
        val deleted = log.deleteAllByUser(1L)
        assertEquals(1L, deleted)
        assertEquals(0L, db.weeklyPlayLogDao().countByUser(1L).toLong())
        assertEquals(1L, db.weeklyPlayLogDao().countByUser(2L).toLong())
    }

    @Test
    fun pruneAllUsersExpired_removesAcrossAllUsers() = runBlocking {
        log.record(event(userId = 1, songId = 10, sessionStart = fixedNow - 15 * dayMs, sessionId = "a"))
        log.record(event(userId = 2, songId = 20, sessionStart = fixedNow - dayMs, sessionId = "b"))
        log.pruneAllUsersExpired(fixedNow)
        assertEquals(0L, db.weeklyPlayLogDao().countByUser(1L).toLong())
        assertEquals(1L, db.weeklyPlayLogDao().countByUser(2L).toLong())
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.weekly.WeeklyPlayLogTest"`
Expected: FAIL — 编译错误 `unresolved reference: WeeklyPlayLog` / `WeeklyDatabase` 不存在。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/ncm/app/data/weekly/WeeklyPlayLog.kt`：

```kotlin
package com.ncm.app.data.weekly

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.time.Instant
import java.time.ZoneId

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
    val playbackSessionId: String,
    val sessionStartedAt: Long
)

data class WeeklySongStat(
    val songId: Long,
    val playCount: Int,
    val lastPlayedAt: Long
)

@Dao
interface WeeklyPlayLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PlayEventEntity): Long

    @Query("DELETE FROM weekly_play_event WHERE userId = :userId AND sessionStartedAt < :cutoff")
    suspend fun deleteOlderThan(userId: Long, cutoff: Long): Int

    @Query("DELETE FROM weekly_play_event WHERE userId = :userId ORDER BY sessionStartedAt ASC LIMIT :limit")
    suspend fun deleteOldest(userId: Long, limit: Int): Int

    @Query("SELECT COUNT(*) FROM weekly_play_event WHERE userId = :userId")
    suspend fun countByUser(userId: Long): Int

    @Query(
        "SELECT songId, COUNT(*) AS playCount, MAX(sessionStartedAt) AS lastPlayedAt " +
            "FROM weekly_play_event " +
            "WHERE userId = :userId AND sessionStartedAt >= :start AND sessionStartedAt < :end " +
            "GROUP BY songId"
    )
    suspend fun queryWeeklyStats(userId: Long, start: Long, end: Long): List<WeeklySongStat>

    @Query("DELETE FROM weekly_play_event WHERE userId = :userId")
    suspend fun deleteAllByUser(userId: Long): Long

    @Query("DELETE FROM weekly_play_event WHERE sessionStartedAt < :cutoff")
    suspend fun deleteAllUsersOlderThan(cutoff: Long): Int
}

@Database(entities = [PlayEventEntity::class], version = 1, exportSchema = false)
abstract class WeeklyDatabase : RoomDatabase() {
    abstract fun weeklyPlayLogDao(): WeeklyPlayLogDao

    companion object {
        @Volatile
        private var instance: WeeklyDatabase? = null

        fun get(context: Context): WeeklyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WeeklyDatabase::class.java,
                    "weekly_play.db"
                ).build().also { instance = it }
            }
        }
    }
}

interface WeeklyPlayLogPort {
    suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat>
    suspend fun pruneExpired(userId: Long, now: Long)
    suspend fun pruneAllUsersExpired(now: Long)
    suspend fun deleteAllByUser(userId: Long): Long
}

class WeeklyPlayLog(
    private val database: WeeklyDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : WeeklyPlayLogPort {

    private val dao = database.weeklyPlayLogDao()

    /** 记录一次有效播放；插入去重、按用户 2000 条封顶、顺带清理 14 个本地自然日前旧数据。 */
    suspend fun record(event: PlayEventEntity) {
        database.withTransaction {
            val inserted = dao.insert(event)
            if (inserted >= 0) {
                val count = dao.countByUser(event.userId)
                if (count > MAX_EVENTS_PER_USER) {
                    dao.deleteOldest(event.userId, count - MAX_EVENTS_PER_USER)
                }
            }
            dao.deleteOlderThan(event.userId, cutoffFor(nowMs()))
        }
    }

    override suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat> =
        dao.queryWeeklyStats(userId, startMs, endMs)

    override suspend fun pruneExpired(userId: Long, now: Long) {
        dao.deleteOlderThan(userId, cutoffFor(now))
    }

    override suspend fun pruneAllUsersExpired(now: Long) {
        dao.deleteAllUsersOlderThan(cutoffFor(now))
    }

    override suspend fun deleteAllByUser(userId: Long): Long = dao.deleteAllByUser(userId)

    /** 14 个本地自然日前的 00:00（含当天其余时段）。 */
    private fun cutoffFor(now: Long): Long {
        val zone = zoneId
        return Instant.ofEpochMilli(now)
            .atZone(zone)
            .toLocalDate()
            .minusDays(CUTOFF_NATURAL_DAYS)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        const val MAX_EVENTS_PER_USER = 2000
        const val CUTOFF_NATURAL_DAYS = 14
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.weekly.WeeklyPlayLogTest"`
Expected: PASS（8 个用例）。注意：Robolectric 首次运行会从 Maven 下载 android-all jar，需联网。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/ncm/app/data/weekly/WeeklyPlayLog.kt app/src/test/java/com/ncm/app/data/weekly/WeeklyPlayLogTest.kt
git commit -m "feat(weekly): add Room-backed weekly play log with dedup, cap and natural-day pruning"
```

---

### Task 3: 相似歌曲接口 + 数据源接口（NeteaseApi / MusicRepository / parseSimilarSongs）

**Files:**
- Modify: `app/src/main/java/com/ncm/app/data/api/NeteaseApi.kt`（`getSongDetail` 之后，第 46 行）
- Modify: `app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt`（第 32-37 行类声明 + 第 97-101 行 `getSongDetail` 之后 + 文件末尾）
- Create: `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecModels.kt`（只含 `SimilarSong`）
- Create: `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecommendationSource.kt`
- Test: `app/src/test/java/com/ncm/app/data/repository/MusicRepositorySimilarSongsTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `NeteaseApi.getSimilarSongs(songId: Long): JsonObject` — `@GET("api/simi/song")`
  - `WeeklyRecommendationSource`：
    ```kotlin
    interface WeeklyRecommendationSource {
        suspend fun getSimilarSongs(songId: Long): List<SimilarSong>      // 失败时抛异常，调用方处理
        suspend fun getSongDetails(ids: List<Long>): List<Song>           // 失败时抛异常，调用方处理
    }
    ```
  - `MusicRepository : WeeklyRecommendationSource`，新增 `getSimilarSongsResult(songId: Long): Result<List<SimilarSong>>`（safeCall 包装）、`getSongDetails(ids)`（delegate 到既有 `getSongDetail` + `getOrThrow()`）
  - 文件级 `internal fun parseSimilarSongs(root: JsonObject): List<SimilarSong>`（纯解析，Gson 自包含，可单测）
  - `SimilarSong(id: Long, name: String, artists: List<ArtistBrief>)`

> 说明：`/api/simi/song?id={id}` 的真实 JSON 结构已在 WebSearch 确认模式（`{"songs":[{id,name,artists:[...],...}],...}`），但字段名以真机为准。本任务代码使用 Plan A 字段（`songs[].id/name/artists[].id/name`）；若真机验证发现字段名不同，按 Step 5 调整 `parseSimilarSongs` 并重跑测试。UseCase（Task 8）内置 Plan B 兜底（候选缺艺人信息时用 `getSongDetails` 水合），因此本任务不会阻塞整条流水线。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/ncm/app/data/repository/MusicRepositorySimilarSongsTest.kt`：

```kotlin
package com.ncm.app.data.repository

import com.google.gson.JsonParser
import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.domain.weekly.SimilarSong
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicRepositorySimilarSongsTest {

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun parseSimilarSongs_readsSongsWithArtists() {
        val root = json(
            """{"songs":[{"id":186016,"name":"晴天","artists":[{"id":6452,"name":"周杰伦"}]},{"id":2,"name":"B","artists":[{"id":20,"name":"Y"}]}]}"""
        )
        val result = parseSimilarSongs(root)
        assertEquals(
            listOf(
                SimilarSong(186016, "晴天", listOf(ArtistBrief(6452, "周杰伦"))),
                SimilarSong(2, "B", listOf(ArtistBrief(20, "Y")))
            ),
            result
        )
    }

    @Test
    fun parseSimilarSongs_skipsInvalidEntriesAndHandlesMissingArtists() {
        val root = json(
            """{"songs":[{"id":0,"name":"bad"},{"name":"noname"},{"id":3,"name":"C","artists":[]},{"id":4,"name":"D","artists":null}]}"""
        )
        val result = parseSimilarSongs(root)
        assertEquals(listOf(SimilarSong(3, "C", emptyList()), SimilarSong(4, "D", emptyList())), result)
    }

    @Test
    fun parseSimilarSongs_returnsEmptyForNonArraySongs() {
        assertEquals(emptyList<SimilarSong>(), parseSimilarSongs(json("""{"songs":{}}""")))
        assertEquals(emptyList<SimilarSong>(), parseSimilarSongs(json("""{"message":"unauthorized"}""")))
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.repository.MusicRepositorySimilarSongsTest"`
Expected: FAIL — `unresolved reference: parseSimilarSongs` / `SimilarSong`。

- [ ] **Step 3: 写实现**

**(a)** 创建 `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecModels.kt`：

```kotlin
package com.ncm.app.domain.weekly

import com.ncm.app.data.model.ArtistBrief

/** 相似歌曲接口返回的一首候选曲目（Plan A 直接携带艺人；Plan B 由 UseCase 补全）。 */
data class SimilarSong(
    val id: Long,
    val name: String,
    val artists: List<ArtistBrief>
)
```

**(b)** 创建 `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecommendationSource.kt`：

```kotlin
package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Song

/** 每周推荐用例的数据源。失败一律抛异常（含 CancellationException），由调用方处理。 */
interface WeeklyRecommendationSource {
    suspend fun getSimilarSongs(songId: Long): List<SimilarSong>
    suspend fun getSongDetails(ids: List<Long>): List<Song>
}
```

**(c)** `NeteaseApi.kt` — 在 `getSongDetail` 方法（第 46 行）之后加：

```kotlin
    @GET("api/simi/song")
    suspend fun getSimilarSongs(
        @Query("id") songId: Long
    ): JsonObject
```

**(d)** `MusicRepository.kt`：
- 第 37 行类声明 `class MusicRepository(...) {` 改成 `class MusicRepository(...) : WeeklyRecommendationSource {`
- 文件 import 区（第 9 行 `JsonParser` 附近）加三行：
  ```kotlin
  import android.util.Log
  import com.ncm.app.domain.weekly.SimilarSong
  import com.ncm.app.domain.weekly.WeeklyRecommendationSource
  ```
- 在 `getSongDetail`（第 97-101 行）之后加：

```kotlin
    override suspend fun getSimilarSongs(songId: Long): List<SimilarSong> =
        getSimilarSongsResult(songId).getOrThrow()

    suspend fun getSimilarSongsResult(songId: Long): Result<List<SimilarSong>> = safeCall {
        val root = api.getSimilarSongs(songId)
        if (BuildConfig.DEBUG) Log.d("SimiDebug", "similar songs root: $root")
        parseSimilarSongs(root)
    }

    override suspend fun getSongDetails(ids: List<Long>): List<Song> =
        getSongDetail(ids).getOrThrow()
```

- 文件末尾（第 933 行私有 JSON helper 之后）追加文件级函数：

```kotlin
/**
 * 解析 /api/simi/song 返回的相似歌曲（自包含、不依赖 MusicRepository 私有 helper）。
 * 字段名以真机抓包为准；若与真实响应不符，按 Task 12 QA 调整此处并重跑
 * MusicRepositorySimilarSongsTest。
 */
internal fun parseSimilarSongs(root: JsonObject): List<SimilarSong> {
    val songs = root.get("songs")
    if (songs == null || !songs.isJsonArray) return emptyList()
    return songs.asJsonArray.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val obj = element.asJsonObject
        val id = obj.get("id")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asJsonPrimitive?.asLong ?: 0L
        val name = obj.get("name")
            ?.takeIf { it.isJsonPrimitive }
            ?.asJsonPrimitive?.asString.orEmpty()
        val artists = (obj.get("artists")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray ?: JsonArray())
            .mapNotNull { artistElement ->
                if (!artistElement.isJsonObject) return@mapNotNull null
                val artist = artistElement.asJsonObject
                val artistId = artist.get("id")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asJsonPrimitive?.asLong ?: 0L
                val artistName = artist.get("name")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asJsonPrimitive?.asString.orEmpty()
                if (artistId > 0 && artistName.isNotBlank()) ArtistBrief(artistId, artistName) else null
            }
        SimilarSong(id, name, artists).takeIf { it.id > 0 && it.name.isNotBlank() }
    }
}
```

> `JsonArray` 已在第 6 行 import；`Log` 的 import 已在 (d) 中显式加入。

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.repository.MusicRepositorySimilarSongsTest"`
Expected: PASS（3 个用例）。随后跑全量测试确认无回归：`.\gradlew.bat --no-daemon :app:testDebugUnitTest`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/ncm/app/data/api/NeteaseApi.kt app/src/main/java/com/ncm/app/data/repository/MusicRepository.kt app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecModels.kt app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecommendationSource.kt app/src/test/java/com/ncm/app/data/repository/MusicRepositorySimilarSongsTest.kt
git commit -m "feat(weekly): add similar-songs endpoint and WeeklyRecommendationSource"
```

---

### Task 4: 每周推荐缓存存储（WeeklyRecommendationStore + 序列化模型）

**Files:**
- Modify: `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecModels.kt`（追加 WeeklyRecResult / CachedSong / WeeklyRecCache / WeeklyRecCacheResult / `Song.toCachedSong()`）
- Create: `app/src/main/java/com/ncm/app/data/weekly/WeeklyRecommendationStore.kt`
- Test: `app/src/test/java/com/ncm/app/data/weekly/WeeklyRecommendationStoreTest.kt`

**Interfaces:**
- Consumes: Task 2 无直接依赖；`SimilarSong`（Task 3）同文件。
- Produces:
  - `WeeklyRecResult`（非序列化，领域结果）：
    ```kotlin
    sealed class WeeklyRecResult {
        data class Success(val songs: List<CachedSong>, val seedCount: Int, val displayWeekStart: LocalDate) : WeeklyRecResult()
        data class InsufficientData(val validPlayCount: Int, val distinctSongCount: Int) : WeeklyRecResult()
        data class Failure(val message: String? = null) : WeeklyRecResult()
    }
    ```
  - `CachedSong(songId: Long, name: String, artists: List<String>, cover: String?)`（@Serializable）
  - `WeeklyRecCache(schemaVersion: Int, sourceWeekStart: String, displayWeekStart: String, result: WeeklyRecCacheResult, generatedAt: Long)`（@Serializable）
  - `WeeklyRecCacheResult`（@Serializable sealed）：`@SerialName("success") Success(songs, seedCount)` / `@SerialName("insufficient_data") InsufficientData(validPlayCount, distinctSongCount)`
  - `internal fun Song.toCachedSong(): CachedSong`
  - `WeeklyRecCachePort`：
    ```kotlin
    interface WeeklyRecCachePort {
        fun get(userId: Long, displayWeekStart: LocalDate, sourceWeekStart: LocalDate): WeeklyRecResult?
        fun putSuccess(userId: Long, sourceWeekStart: LocalDate, displayWeekStart: LocalDate, songs: List<CachedSong>, seedCount: Int, generatedAt: Long)
        fun putInsufficientData(userId: Long, sourceWeekStart: LocalDate, displayWeekStart: LocalDate, validPlayCount: Int, distinctSongCount: Int, generatedAt: Long)
        fun removeInvalidIfPresent(userId: Long): Boolean
        suspend fun removeForUserDurable(userId: Long): Boolean
    }
    ```
  - `WeeklyRecommendationStore(prefs: SharedPreferences) : WeeklyRecCachePort`，常量 `SCHEMA_VERSION = 1`、`KEY_PREFIX = "weekly_rec:"`、`PREF_NAME = "ncm_weekly_rec"`；`get` 命中校验 schema 与两周，不匹配/解析失败 → 删除并返回 null。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/ncm/app/data/weekly/WeeklyRecommendationStoreTest.kt`：

```kotlin
package com.ncm.app.data.weekly

import android.content.Context
import com.ncm.app.domain.weekly.CachedSong
import com.ncm.app.domain.weekly.WeeklyRecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyRecommendationStoreTest {

    private val sourceWeek = LocalDate.of(2026, 7, 20)
    private val displayWeek = LocalDate.of(2026, 7, 27)
    private val songs = listOf(CachedSong(1, "A", listOf("X"), null))

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: WeeklyRecommendationStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("weekly_store_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = WeeklyRecommendationStore(prefs)
    }

    private fun key(userId: Long) = "${WeeklyRecommendationStore.KEY_PREFIX}$userId"

    @Test
    fun putSuccess_thenGet_returnsSameResult() {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 2, generatedAt = 123L)
        val result = store.get(1L, displayWeek, sourceWeek)
        assertNotNull(result)
        result as WeeklyRecResult.Success
        assertEquals(songs, result.songs)
        assertEquals(2, result.seedCount)
        assertEquals(displayWeek, result.displayWeekStart)
    }

    @Test
    fun putInsufficientData_thenGet_returnsInsufficientData() {
        store.putInsufficientData(1L, sourceWeek, displayWeek, validPlayCount = 3, distinctSongCount = 1, generatedAt = 123L)
        val result = store.get(1L, displayWeek, sourceWeek)
        result as WeeklyRecResult.InsufficientData
        assertEquals(3, result.validPlayCount)
        assertEquals(1, result.distinctSongCount)
    }

    @Test
    fun get_missingKey_returnsNull() {
        assertNull(store.get(1L, displayWeek, sourceWeek))
    }

    @Test
    fun get_weekMismatch_treatsAsMissAndRemoves() {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        val otherWeek = LocalDate.of(2026, 8, 3)
        assertNull(store.get(1L, otherWeek, sourceWeek.minusWeeks(1)))
        assertNull(prefs.getString(key(1L), null))
    }

    @Test
    fun get_schemaMismatch_treatsAsMissAndRemoves() {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        // 篡改成 schema 2，模拟未来版本
        val raw = prefs.getString(key(1L), null)!!
        val tampered = raw.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        prefs.edit().putString(key(1L), tampered).commit()
        assertNull(store.get(1L, displayWeek, sourceWeek))
        assertNull(prefs.getString(key(1L), null))
    }

    @Test
    fun get_corruptJson_treatsAsMissAndRemoves() {
        prefs.edit().putString(key(1L), "{not-json").commit()
        assertNull(store.get(1L, displayWeek, sourceWeek))
        assertNull(prefs.getString(key(1L), null))
    }

    @Test
    fun get_toleratesUnknownKeys() {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        val raw = prefs.getString(key(1L), null)!!
        val extended = raw.replace("{\"schemaVersion\"", "{\"futureField\":true,\"schemaVersion\"")
        prefs.edit().putString(key(1L), extended).commit()
        assertNotNull(store.get(1L, displayWeek, sourceWeek))
    }

    @Test
    fun sameKey_overwritesWithNewestWeek() {
        store.putSuccess(1L, sourceWeek, displayWeek, listOf(CachedSong(1, "A", listOf("X"), null)), seedCount = 1, generatedAt = 111L)
        val nextWeek = displayWeek.plusWeeks(1)
        store.putSuccess(1L, nextWeek.minusWeeks(1), nextWeek, listOf(CachedSong(2, "B", listOf("Y"), null)), seedCount = 1, generatedAt = 222L)
        val result = store.get(1L, nextWeek, nextWeek.minusWeeks(1))
        result as WeeklyRecResult.Success
        assertEquals(listOf(CachedSong(2, "B", listOf("Y"), null)), result.songs)
    }

    @Test
    fun removeInvalidIfPresent_removesCorruptButKeepsValid() {
        prefs.edit().putString(key(1L), "{bad").commit()
        assertTrue(store.removeInvalidIfPresent(1L))
        assertNull(prefs.getString(key(1L), null))

        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        assertTrue(!store.removeInvalidIfPresent(1L))
        assertNotNull(prefs.getString(key(1L), null))
    }

    @Test
    fun removeForUserDurable_removesAndCommits() = runBlocking {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 123L)
        assertTrue(store.removeForUserDurable(1L))
        assertNull(prefs.getString(key(1L), null))
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.weekly.WeeklyRecommendationStoreTest"`
Expected: FAIL — `unresolved reference: WeeklyRecommendationStore` / `CachedSong`。

- [ ] **Step 3: 写实现**

**(a)** 在 `WeeklyRecModels.kt` 末尾追加（保留 Task 3 的 `SimilarSong`；新增 import `com.ncm.app.data.model.Song` 与序列化注解）：

```kotlin
package com.ncm.app.domain.weekly

import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.Song
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** 相似歌曲接口返回的一首候选曲目（Plan A 直接携带艺人；Plan B 由 UseCase 补全）。 */
data class SimilarSong(
    val id: Long,
    val name: String,
    val artists: List<ArtistBrief>
)

/** 每周推荐生成结果（非持久化领域对象）。 */
sealed class WeeklyRecResult {
    data class Success(
        val songs: List<CachedSong>,
        val seedCount: Int,
        val displayWeekStart: LocalDate
    ) : WeeklyRecResult()

    data class InsufficientData(
        val validPlayCount: Int,
        val distinctSongCount: Int
    ) : WeeklyRecResult()

    data class Failure(val message: String? = null) : WeeklyRecResult()
}

/** 缓存歌单条目（仅存列表页需要的最小字段）。 */
@Serializable
data class CachedSong(
    val songId: Long,
    val name: String,
    val artists: List<String>,
    val cover: String?
)

internal fun Song.toCachedSong(): CachedSong = CachedSong(
    songId = id,
    name = name,
    artists = artists.orEmpty().map { it.name },
    cover = album?.picUrl
)

/** SharedPreferences 持久化模型（result 用 resultType 判别器区分两种结果）。 */
@Serializable
data class WeeklyRecCache(
    val schemaVersion: Int,
    val sourceWeekStart: String,
    val displayWeekStart: String,
    val result: WeeklyRecCacheResult,
    val generatedAt: Long
)

@Serializable
sealed class WeeklyRecCacheResult {
    @Serializable
    @SerialName("success")
    data class Success(val songs: List<CachedSong>, val seedCount: Int) : WeeklyRecCacheResult()

    @Serializable
    @SerialName("insufficient_data")
    data class InsufficientData(val validPlayCount: Int, val distinctSongCount: Int) : WeeklyRecCacheResult()
}
```

**(b)** 创建 `app/src/main/java/com/ncm/app/data/weekly/WeeklyRecommendationStore.kt`：

```kotlin
package com.ncm.app.data.weekly

import android.content.SharedPreferences
import com.ncm.app.domain.weekly.CachedSong
import com.ncm.app.domain.weekly.WeeklyRecCache
import com.ncm.app.domain.weekly.WeeklyRecCacheResult
import com.ncm.app.domain.weekly.WeeklyRecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

interface WeeklyRecCachePort {
    fun get(userId: Long, displayWeekStart: LocalDate, sourceWeekStart: LocalDate): WeeklyRecResult?
    fun putSuccess(
        userId: Long,
        sourceWeekStart: LocalDate,
        displayWeekStart: LocalDate,
        songs: List<CachedSong>,
        seedCount: Int,
        generatedAt: Long
    )
    fun putInsufficientData(
        userId: Long,
        sourceWeekStart: LocalDate,
        displayWeekStart: LocalDate,
        validPlayCount: Int,
        distinctSongCount: Int,
        generatedAt: Long
    )
    fun removeInvalidIfPresent(userId: Long): Boolean
    suspend fun removeForUserDurable(userId: Long): Boolean
}

/**
 * 每周推荐缓存。单键 `weekly_rec:{userId}`；周内命中即零请求。
 * 解析/校验失败时删除缓存（apply()）；退出登录用 removeForUserDurable（commit() 带一次重试）。
 */
class WeeklyRecommendationStore(
    private val prefs: SharedPreferences
) : WeeklyRecCachePort {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "resultType"
    }

    override fun get(
        userId: Long,
        displayWeekStart: LocalDate,
        sourceWeekStart: LocalDate
    ): WeeklyRecResult? {
        val key = keyFor(userId)
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val cache = json.decodeFromString<WeeklyRecCache>(raw)
            if (cache.schemaVersion != SCHEMA_VERSION ||
                cache.displayWeekStart != displayWeekStart.toString() ||
                cache.sourceWeekStart != sourceWeekStart.toString()
            ) {
                prefs.edit().remove(key).apply()
                null
            } else {
                when (val result = cache.result) {
                    is WeeklyRecCacheResult.Success -> WeeklyRecResult.Success(
                        songs = result.songs,
                        seedCount = result.seedCount,
                        displayWeekStart = displayWeekStart
                    )
                    is WeeklyRecCacheResult.InsufficientData -> WeeklyRecResult.InsufficientData(
                        validPlayCount = result.validPlayCount,
                        distinctSongCount = result.distinctSongCount
                    )
                }
            }
        } catch (e: Exception) {
            // JSON 解码失败 → 视为缓存损坏，删除。纯同步 prefs 操作，无协程取消语义。
            prefs.edit().remove(key).apply()
            null
        }
    }

    override fun putSuccess(
        userId: Long,
        sourceWeekStart: LocalDate,
        displayWeekStart: LocalDate,
        songs: List<CachedSong>,
        seedCount: Int,
        generatedAt: Long
    ) {
        val cache = WeeklyRecCache(
            schemaVersion = SCHEMA_VERSION,
            sourceWeekStart = sourceWeekStart.toString(),
            displayWeekStart = displayWeekStart.toString(),
            result = WeeklyRecCacheResult.Success(songs = songs, seedCount = seedCount),
            generatedAt = generatedAt
        )
        prefs.edit().putString(keyFor(userId), json.encodeToString(cache)).apply()
    }

    override fun putInsufficientData(
        userId: Long,
        sourceWeekStart: LocalDate,
        displayWeekStart: LocalDate,
        validPlayCount: Int,
        distinctSongCount: Int,
        generatedAt: Long
    ) {
        val cache = WeeklyRecCache(
            schemaVersion = SCHEMA_VERSION,
            sourceWeekStart = sourceWeekStart.toString(),
            displayWeekStart = displayWeekStart.toString(),
            result = WeeklyRecCacheResult.InsufficientData(
                validPlayCount = validPlayCount,
                distinctSongCount = distinctSongCount
            ),
            generatedAt = generatedAt
        )
        prefs.edit().putString(keyFor(userId), json.encodeToString(cache)).apply()
    }

    override fun removeInvalidIfPresent(userId: Long): Boolean {
        val key = keyFor(userId)
        val raw = prefs.getString(key, null) ?: return false
        val invalid = try {
            val cache = json.decodeFromString<WeeklyRecCache>(raw)
            cache.schemaVersion != SCHEMA_VERSION
        } catch (e: Exception) {
            true
        }
        if (invalid) prefs.edit().remove(key).apply()
        return invalid
    }

    override suspend fun removeForUserDurable(userId: Long): Boolean = withContext(Dispatchers.IO) {
        var attempts = 0
        var success = false
        while (attempts < 2 && !success) {
            success = prefs.edit().remove(keyFor(userId)).commit()
            attempts++
        }
        success
    }

    private fun keyFor(userId: Long): String = "$KEY_PREFIX$userId"

    companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_PREFIX = "weekly_rec:"
        const val PREF_NAME = "ncm_weekly_rec"
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.weekly.WeeklyRecommendationStoreTest"`
Expected: PASS（10 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecModels.kt app/src/main/java/com/ncm/app/data/weekly/WeeklyRecommendationStore.kt app/src/test/java/com/ncm/app/data/weekly/WeeklyRecommendationStoreTest.kt
git commit -m "feat(weekly): add kotlinx-serialization backed weekly recommendation cache store"
```

---

### Task 5: 每周推荐算法（WeeklyRecommendationAlgorithm）

**Files:**
- Create: `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecommendationAlgorithm.kt`
- Test: `app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecommendationAlgorithmTest.kt`

**Interfaces:**
- Consumes: `WeeklySongStat`（Task 2）、`Song`/`ArtistBrief`（既有）
- Produces:
  - `data class Seed(songId: Long, score: Double, primaryArtistId: Long)`
  - `data class CandidateEdge(seedSongId: Long, candidateSongId: Long, seedScore: Double, simRank: Int, primaryArtistId: Long)`
  - `object WeeklyRecommendationAlgorithm`：
    - `fun preliminaryScore(playCount: Int, lastPlayedAt: Long, startMs: Long, endMs: Long): Double`
    - `fun selectSeeds(weekSongs: List<WeeklySongStat>, hydrated: Map<Long, Song>, startMs: Long, endMs: Long): List<Seed>`
    - `fun rankCandidates(edges: List<CandidateEdge>, listenedSongIds: Set<Long>): List<Long>`
    - 常量：`SEED_LIMIT=8`、`SEED_ARTIST_LIMIT=2`、`SIM_LIMIT=10`、`TARGET_COUNT=30`、`ARTIST_LIMIT_FIRST_PASS=3`、`ARTIST_LIMIT_SECOND_PASS=5`、`SCORE_PLAY_WEIGHT=0.7`、`SCORE_RECENCY_WEIGHT=0.3`
  - 关键规则：`preliminaryScore = 0.7*log2(1+playCount) + 0.3*recency`（全 Double）；recency = `((lastPlayedAt-startMs)/(endMs-startMs)).coerceIn(0.0,1.0)`；选种贪心按分数降序，同 primaryArtistId ≤ 2，无艺人歌曲用 songId 作桶（不互相冲突）；候选去重按 `(seedSongId, candidateSongId)` 保留最小 simRank；`coScore = 3*(coOccurrence-1)`；稳定排序 `coOccurrence desc → simScore desc → ΣsimRank asc → songId asc`；艺人限制两趟（3 → 结果 <30 时用 5 重选，不追加）。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecommendationAlgorithmTest.kt`：

```kotlin
package com.ncm.app.domain.weekly

import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.Song
import com.ncm.app.data.weekly.WeeklySongStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyRecommendationAlgorithmTest {

    private val start = 1_700_000_000_000L
    private val end = 1_750_000_000_000L

    private fun song(id: Long, artistId: Long, artistName: String = "Artist$artistId"): Song =
        Song(id = id, name = "Song$id", artists = listOf(ArtistBrief(artistId, artistName)))

    private fun songNoArtist(id: Long): Song = Song(id = id, name = "Song$id", artists = null)

    private fun stat(songId: Long, playCount: Int, lastPlayedAt: Long = end): WeeklySongStat =
        WeeklySongStat(songId, playCount, lastPlayedAt)

    private fun edge(
        seed: Long,
        candidate: Long,
        rank: Int,
        artistId: Long = 20,
        seedScore: Double = 1.0
    ): CandidateEdge = CandidateEdge(seed, candidate, seedScore, rank, artistId)

    // ---- preliminaryScore ----

    @Test
    fun preliminaryScore_playCountDominates() {
        val high = WeeklyRecommendationAlgorithm.preliminaryScore(10, end, start, end)
        val low = WeeklyRecommendationAlgorithm.preliminaryScore(1, end, start, end)
        assertTrue(high > low)
    }

    @Test
    fun preliminaryScore_recencyBoostsLaterListens() {
        val recent = WeeklyRecommendationAlgorithm.preliminaryScore(1, end, start, end)
        val early = WeeklyRecommendationAlgorithm.preliminaryScore(1, start, start, end)
        assertTrue(recent > early)
        assertEquals(1.0, recent, 1e-9)
    }

    @Test
    fun preliminaryScore_usesDoublesWithoutIntegerTruncation() {
        val one = WeeklyRecommendationAlgorithm.preliminaryScore(1, start, start, end)
        assertTrue(one > 0.6 && one < 0.8)     // 0.7*log2(2) = 0.7
        val two = WeeklyRecommendationAlgorithm.preliminaryScore(2, start, start, end)
        assertTrue(two > 1.0 && two < 1.2)     // 0.7*log2(3) ≈ 1.1097
    }

    @Test
    fun preliminaryScore_clampsRecencyToUnitRange() {
        val before = WeeklyRecommendationAlgorithm.preliminaryScore(0, start - 1_000, start, end)
        val after = WeeklyRecommendationAlgorithm.preliminaryScore(0, end + 1_000, start, end)
        assertEquals(0.0, before, 1e-9)
        assertEquals(0.3, after, 1e-9)
    }

    // ---- selectSeeds ----

    @Test
    fun selectSeeds_picksTopSongsByScoreUpToLimit() {
        val weekSongs = (1..10).map { stat(it.toLong(), playCount = 11 - it) }
        val hydrated = (1L..10L).associateWith { song(it, artistId = it) }
        val seeds = WeeklyRecommendationAlgorithm.selectSeeds(weekSongs, hydrated, start, end)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), seeds.map { it.songId })
    }

    @Test
    fun selectSeeds_limitsTwoSeedsPerPrimaryArtist() {
        val weekSongs = listOf(stat(1, 10), stat(2, 9), stat(3, 8), stat(4, 7))
        val hydrated = mapOf(
            1L to song(1, 100), 2L to song(2, 100), 3L to song(3, 100), 4L to song(4, 200)
        )
        val seeds = WeeklyRecommendationAlgorithm.selectSeeds(weekSongs, hydrated, start, end)
        assertEquals(listOf(1L, 2L, 4L), seeds.map { it.songId })   // 3 skipped: artist 100 at limit
    }

    @Test
    fun selectSeeds_noArtistSongsUseOwnBucketAndDoNotCollide() {
        val weekSongs = listOf(stat(1, 10), stat(2, 9), stat(3, 8))
        val hydrated = mapOf(1L to song(1, 100))   // only song 1 has artist info
        val seeds = WeeklyRecommendationAlgorithm.selectSeeds(weekSongs, hydrated, start, end)
        assertEquals(listOf(1L, 2L, 3L), seeds.map { it.songId })
    }

    // ---- rankCandidates ----

    @Test
    fun rankCandidates_filtersInvalidEdges() {
        val edges = listOf(
            edge(1, 100, rank = 0),                  // simRank out of 1..10
            edge(1, 101, rank = 11),
            edge(1, 0, rank = 1),                    // invalid candidate id
            edge(1, 1, rank = 1),                    // candidate == seed
            edge(1, 102, rank = 1, artistId = 0),    // no artist
            edge(1, 103, rank = 1, artistId = 20)    // valid
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(103L), result)
    }

    @Test
    fun rankCandidates_dedupsDuplicateEdgesBeforeScoring() {
        val edges = listOf(
            edge(1, 100, rank = 2),
            edge(1, 100, rank = 2),                  // duplicate pair must collapse to one
            edge(1, 200, rank = 1)
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        // With dedup: 100 co1 simScore 9; 200 co1 simScore 10 → [200, 100]
        assertEquals(listOf(200L, 100L), result)
    }

    @Test
    fun rankCandidates_excludesListenedSongs() {
        val edges = listOf(
            edge(1, 100, rank = 1),
            edge(1, 200, rank = 1)
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, setOf(100L))
        assertEquals(listOf(200L), result)
    }

    @Test
    fun rankCandidates_weightsSeedsByRelativeScore() {
        val edges = listOf(
            edge(1, 100, rank = 1, seedScore = 1.0),   // weight 1.0 → simScore 10
            edge(2, 200, rank = 1, seedScore = 0.5)    // weight 0.5 → simScore 5
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(100L, 200L), result)
    }

    @Test
    fun rankCandidates_zeroSeedScoresFallsBackToCoOccurrence() {
        val edges = listOf(
            edge(1, 100, rank = 1, seedScore = 0.0),
            edge(1, 200, rank = 1, seedScore = 0.0),
            edge(2, 200, rank = 1, seedScore = 0.0)    // 200 co2 → coScore 3
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(200L, 100L), result)
    }

    @Test
    fun rankCandidates_coScoreTableIsThreeTimesCoOccurrenceMinusOne() {
        // coOccurrence 2 → +3. 100 reached from 2 seeds (co2), 200 from 1 seed (co1).
        // Equal simScore per seed → 100 wins via coScore.
        val edges = listOf(
            edge(1, 100, rank = 1),
            edge(2, 100, rank = 1),
            edge(1, 200, rank = 1)
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(100L, 200L), result)
    }

    @Test
    fun rankCandidates_tieBreaksBySongIdAscending() {
        val edges = listOf(
            edge(1, 200, rank = 1, seedScore = 1.0),
            edge(1, 100, rank = 1, seedScore = 1.0)
        )
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(listOf(100L, 200L), result)
    }

    @Test
    fun rankCandidates_secondPassRelaxesArtistLimitWhenShortOfTarget() {
        val edges = mutableListOf<CandidateEdge>()
        for (artist in 1L..6L) {
            for (rank in 1..7) {
                edges += edge(1, artist * 100 + rank, rank = rank, artistId = artist)
            }
        }
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        // First pass (limit 3): 6*3 = 18 < 30 → second pass (limit 5): 6*5 = 30.
        assertEquals(30, result.size)
        val perArtist = result.groupingBy { it / 100 }.eachCount()
        assertEquals(6, perArtist.size)
        assertEquals(5, perArtist.values.maxOrNull())
    }

    @Test
    fun rankCandidates_returnsAllWhenPoolSmallerThanTarget() {
        val edges = (1..5).map { edge(1, it.toLong() + 100, rank = it, artistId = 1) }
        val result = WeeklyRecommendationAlgorithm.rankCandidates(edges, emptySet())
        assertEquals(5, result.size)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.domain.weekly.WeeklyRecommendationAlgorithmTest"`
Expected: FAIL — `unresolved reference: WeeklyRecommendationAlgorithm` / `Seed` / `CandidateEdge`。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecommendationAlgorithm.kt`：

```kotlin
package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Song
import com.ncm.app.data.weekly.WeeklySongStat
import kotlin.math.ln

/** 选中的种子歌曲。score 为 preliminaryScore，primaryArtistId 用于艺人配额。 */
data class Seed(
    val songId: Long,
    val score: Double,
    val primaryArtistId: Long
)

/** 一条"种子→候选"相似边。 */
data class CandidateEdge(
    val seedSongId: Long,
    val candidateSongId: Long,
    val seedScore: Double,
    val simRank: Int,
    val primaryArtistId: Long
)

private data class ScoredCandidate(
    val candidateSongId: Long,
    val simScore: Double,
    val coOccurrence: Int,
    val sumSimRank: Int,
    val primaryArtistId: Long
)

object WeeklyRecommendationAlgorithm {

    const val SEED_LIMIT = 8
    const val SEED_ARTIST_LIMIT = 2
    const val SIM_LIMIT = 10
    const val TARGET_COUNT = 30
    const val ARTIST_LIMIT_FIRST_PASS = 3
    const val ARTIST_LIMIT_SECOND_PASS = 5
    const val SCORE_PLAY_WEIGHT = 0.7
    const val SCORE_RECENCY_WEIGHT = 0.3

    private const val CO_OCCURRENCE_WEIGHT = 3.0

    /** 0.7*log2(1+playCount) + 0.3*recency，全部 Double 参与，防止整型截断。 */
    fun preliminaryScore(playCount: Int, lastPlayedAt: Long, startMs: Long, endMs: Long): Double {
        val playTerm = SCORE_PLAY_WEIGHT * ln(1.0 + playCount) / ln(2.0)
        val durationMs = (endMs - startMs).toDouble()
        val recency = if (durationMs <= 0.0) {
            0.0
        } else {
            ((lastPlayedAt - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
        }
        return playTerm + SCORE_RECENCY_WEIGHT * recency
    }

    /** 贪心按分数降序选最多 8 个种子；同 primaryArtistId ≤ 2；无艺人歌曲用 songId 当桶（不互斥）。 */
    fun selectSeeds(
        weekSongs: List<WeeklySongStat>,
        hydrated: Map<Long, Song>,
        startMs: Long,
        endMs: Long
    ): List<Seed> {
        val selected = mutableListOf<Seed>()
        val artistCount = mutableMapOf<Long, Int>()
        val sorted = weekSongs
            .map { stat ->
                stat to preliminaryScore(stat.playCount, stat.lastPlayedAt, startMs, endMs)
            }
            .sortedByDescending { it.second }
        for ((stat, score) in sorted) {
            if (selected.size >= SEED_LIMIT) break
            val artistId = hydrated[stat.songId]?.primaryArtistId() ?: 0L
            val bucket = if (artistId > 0) artistId else stat.songId
            if ((artistCount[bucket] ?: 0) >= SEED_ARTIST_LIMIT) continue
            selected += Seed(stat.songId, score, artistId)
            artistCount[bucket] = (artistCount[bucket] ?: 0) + 1
        }
        return selected
    }

    /**
     * 过滤非法边 → 按 (seed, candidate) 去重保留最小 simRank → 剔除已听歌曲 →
     * 累加 simScore（种子权重归一化）与 coScore = 3*(coOccurrence-1) →
     * 稳定排序 → 两趟艺人限制（3 → 不足 30 用 5 重选，不追加）。
     */
    fun rankCandidates(edges: List<CandidateEdge>, listenedSongIds: Set<Long>): List<Long> {
        val valid = edges.filter { edge ->
            edge.candidateSongId > 0 &&
                edge.candidateSongId != edge.seedSongId &&
                edge.simRank in 1..SIM_LIMIT &&
                edge.primaryArtistId > 0
        }
        val deduped = valid
            .groupBy { it.seedSongId to it.candidateSongId }
            .mapNotNull { (_, group) -> group.minByOrNull { it.simRank } }
            .filter { it.candidateSongId !in listenedSongIds }
        if (deduped.isEmpty()) return emptyList()

        val maxSeedScore = deduped.maxOfOrNull { it.seedScore } ?: 0.0
        val accumulators = LinkedHashMap<Long, CandidateAccumulator>()
        for (edge in deduped) {
            val seedWeight = if (maxSeedScore > 0.0) edge.seedScore / maxSeedScore else 0.0
            val contribution = seedWeight * (SIM_LIMIT - edge.simRank + 1)
            val acc = accumulators.getOrPut(edge.candidateSongId) {
                CandidateAccumulator(edge.candidateSongId, edge.primaryArtistId)
            }
            acc.simScore += contribution
            acc.coOccurrence += 1
            acc.sumSimRank += edge.simRank
        }
        val scored = accumulators.values.map { acc ->
            ScoredCandidate(
                candidateSongId = acc.candidateSongId,
                simScore = acc.simScore,
                coOccurrence = acc.coOccurrence,
                sumSimRank = acc.sumSimRank,
                primaryArtistId = acc.primaryArtistId
            )
        }
        val sorted = scored.sortedWith(
            compareByDescending<ScoredCandidate> { it.coOccurrence }
                .thenByDescending { it.simScore }
                .thenBy { it.sumSimRank }
                .thenBy { it.candidateSongId }
        )
        val firstPass = selectWithArtistLimit(sorted, ARTIST_LIMIT_FIRST_PASS, TARGET_COUNT)
        val result = if (firstPass.size < TARGET_COUNT) {
            selectWithArtistLimit(sorted, ARTIST_LIMIT_SECOND_PASS, TARGET_COUNT)
        } else {
            firstPass
        }
        return result.map { it.candidateSongId }
    }

    private fun selectWithArtistLimit(
        sorted: List<ScoredCandidate>,
        artistLimit: Int,
        target: Int
    ): List<ScoredCandidate> {
        val result = mutableListOf<ScoredCandidate>()
        val artistCount = mutableMapOf<Long, Int>()
        for (candidate in sorted) {
            if (result.size >= target) break
            val bucket = if (candidate.primaryArtistId > 0) candidate.primaryArtistId else candidate.candidateSongId
            if ((artistCount[bucket] ?: 0) >= artistLimit) continue
            result += candidate
            artistCount[bucket] = (artistCount[bucket] ?: 0) + 1
        }
        return result
    }

    private data class CandidateAccumulator(
        val candidateSongId: Long,
        val primaryArtistId: Long
    ) {
        var simScore: Double = 0.0
        var coOccurrence: Int = 0
        var sumSimRank: Int = 0
    }

    private fun Song.primaryArtistId(): Long = artists?.firstOrNull()?.id ?: 0L
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.domain.weekly.WeeklyRecommendationAlgorithmTest"`
Expected: PASS（15 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecommendationAlgorithm.kt app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecommendationAlgorithmTest.kt
git commit -m "feat(weekly): add seed selection and candidate ranking algorithm"
```

---

### Task 6: 播放会话累计器（PlaySessionAccumulator）

**Files:**
- Create: `app/src/main/java/com/ncm/app/playback/PlaySessionAccumulator.kt`
- Test: `app/src/test/java/com/ncm/app/playback/PlaySessionAccumulatorTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  ```kotlin
  class PlaySessionAccumulator {
      fun beginSession()
      val sessionStartedAt: Long
      fun onSeekStarted()
      fun track(positionMs: Long, isPlaying: Boolean)
      fun consumeQualification(durationMs: Long): Boolean
      fun currentAccumulatedPlayedMs(): Long
      companion object {
          const val MIN_QUALIFICATION_PLAYED_MS = 30_000L
          const val QUALIFICATION_RATIO = 0.5
          const val MAX_REASONABLE_POSITION_DELTA = 3_000L
      }
  }
  ```

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/ncm/app/playback/PlaySessionAccumulatorTest.kt`：

```kotlin
package com.ncm.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySessionAccumulatorTest {

    @Test
    fun freshSessionStartsEmpty() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        assertEquals(0L, acc.currentAccumulatedPlayedMs())
        assertFalse(acc.consumeQualification(180_000))
    }

    @Test
    fun accumulatesPlayedMillisWhilePlaying() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(1_000, isPlaying = true)
        acc.track(3_000, isPlaying = true)
        assertEquals(3_000L, acc.currentAccumulatedPlayedMs())
    }

    @Test
    fun pausedGapIsNotCounted() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(16_000, isPlaying = true)    // 16s
        acc.track(16_000, isPlaying = false)   // pause: baseline dropped
        acc.track(32_000, isPlaying = true)    // resume 16s later: no accumulation for the gap
        acc.track(36_000, isPlaying = true)
        assertEquals(20_000L, acc.currentAccumulatedPlayedMs())
    }

    @Test
    fun seekDoesNotCountThePositionJump() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(5_000, isPlaying = true)
        acc.onSeekStarted()
        acc.track(90_000, isPlaying = true)    // jump right after seek
        acc.track(91_000, isPlaying = true)
        assertEquals(6_000L, acc.currentAccumulatedPlayedMs())
    }

    @Test
    fun bufferingJumpOverThresholdIsNotCounted() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(10_000, isPlaying = true)
        acc.track(60_000, isPlaying = true)    // buffered jump of 50s without a seek
        acc.track(61_000, isPlaying = true)
        assertEquals(11_000L, acc.currentAccumulatedPlayedMs())
    }

    @Test
    fun qualifiesAfterThirtySeconds() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        var position = 0L
        acc.track(position, isPlaying = true)
        while (position < 30_000) {
            position += 500
            acc.track(position, isPlaying = true)
        }
        assertTrue(acc.consumeQualification(300_000))
        assertFalse(acc.consumeQualification(300_000))   // fires once per session
    }

    @Test
    fun qualifiesByRatioWithoutThirtySeconds() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(2_000, isPlaying = true)
        acc.track(4_000, isPlaying = true)
        // duration 8000 → ratio 0.5
        assertTrue(acc.consumeQualification(8_000))
    }

    @Test
    fun doesNotQualifyWhenDurationUnknownAndUnderThirtySeconds() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(2_000, isPlaying = true)
        acc.track(4_000, isPlaying = true)
        assertFalse(acc.consumeQualification(0))
        assertFalse(acc.consumeQualification(-1L))
    }

    @Test
    fun beginSessionResetsState() {
        val acc = PlaySessionAccumulator()
        acc.beginSession()
        acc.track(0, isPlaying = true)
        acc.track(40_000, isPlaying = true)
        assertTrue(acc.consumeQualification(120_000))
        val previousSessionStart = acc.sessionStartedAt
        acc.beginSession()
        assertEquals(0L, acc.currentAccumulatedPlayedMs())
        assertFalse(acc.consumeQualification(120_000))
        assertNotEquals(previousSessionStart, acc.sessionStartedAt)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.playback.PlaySessionAccumulatorTest"`
Expected: FAIL — `unresolved reference: PlaySessionAccumulator`。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/ncm/app/playback/PlaySessionAccumulator.kt`：

```kotlin
package com.ncm.app.playback

/**
 * 单个播放会话的累计器：累计"真实播放毫秒数"（非进度条位置），
 * 处理暂停、拖动、缓冲跳变，并判定一次有效播放。
 *
 * - beginSession() 重置全部状态并记录会话开始时间。
 * - track(positionMs, isPlaying) 按增量累计；暂停会丢弃基线，跳变超过
 *   MAX_REASONABLE_POSITION_DELTA 不累计（缓冲导致的位置跳变）。
 * - onSeekStarted() 后首次 track 只重建基线、不累计（拖动不算播放）。
 * - consumeQualification() 每次会话最多返回 true 一次。
 */
class PlaySessionAccumulator {

    private var accumulatedPlayedMs = 0L
    private var sessionStartedAtValue = 0L
    private var previousPositionMs = -1L
    private var isSeeking = false
    private var qualificationTriggered = false

    val sessionStartedAt: Long get() = sessionStartedAtValue

    fun beginSession() {
        accumulatedPlayedMs = 0L
        sessionStartedAtValue = System.currentTimeMillis()
        previousPositionMs = -1L
        isSeeking = false
        qualificationTriggered = false
    }

    fun onSeekStarted() {
        isSeeking = true
    }

    fun track(positionMs: Long, isPlaying: Boolean) {
        if (!isPlaying) {
            previousPositionMs = -1L
            return
        }
        val previous = previousPositionMs
        previousPositionMs = positionMs
        if (previous < 0) return
        if (isSeeking) {
            isSeeking = false
            return
        }
        val delta = positionMs - previous
        if (delta in 0..MAX_REASONABLE_POSITION_DELTA) {
            accumulatedPlayedMs += delta
        }
    }

    /** 有效播放判定：满 30 秒，或播放进度达到时长的一半；每会话只触发一次。 */
    fun consumeQualification(durationMs: Long): Boolean {
        if (qualificationTriggered) return false
        val qualifies = accumulatedPlayedMs >= MIN_QUALIFICATION_PLAYED_MS ||
            (durationMs > 0 && accumulatedPlayedMs.toDouble() / durationMs >= QUALIFICATION_RATIO)
        if (qualifies) qualificationTriggered = true
        return qualifies
    }

    fun currentAccumulatedPlayedMs(): Long = accumulatedPlayedMs

    companion object {
        const val MIN_QUALIFICATION_PLAYED_MS = 30_000L
        const val QUALIFICATION_RATIO = 0.5
        const val MAX_REASONABLE_POSITION_DELTA = 3_000L
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.playback.PlaySessionAccumulatorTest"`
Expected: PASS（10 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/ncm/app/playback/PlaySessionAccumulator.kt app/src/test/java/com/ncm/app/playback/PlaySessionAccumulatorTest.kt
git commit -m "feat(weekly): add play session accumulator for valid-play qualification"
```

---

### Task 7: sessionGeneration + 每周数据清理（Cleaner / LogoutCoordinator）

**Files:**
- Modify: `app/src/main/java/com/ncm/app/data/SessionManager.kt`
- Create: `app/src/main/java/com/ncm/app/data/weekly/WeeklyCacheCleaner.kt`
- Test: `app/src/test/java/com/ncm/app/data/SessionGenerationTest.kt`（Robolectric）
- Test: `app/src/test/java/com/ncm/app/data/weekly/WeeklyCacheCleanerTest.kt`（Robolectric）
- Test: `app/src/test/java/com/ncm/app/data/weekly/WeeklyLogoutCoordinatorTest.kt`（纯 JVM）

**Interfaces:**
- Consumes: `WeeklyPlayLogPort`（Task 2）、`WeeklyRecCachePort`（Task 4）
- Produces:
  - `SessionManager.sessionGeneration: Int`（get prefs `session_generation` 默认 0，`private set`）、`fun invalidate()`、`saveLoginInfo` 末尾 `sessionGeneration++`
  - `data class ClearWeeklyDataResult(roomCleared: Boolean, recommendationCacheCleared: Boolean) { val success: Boolean get() = roomCleared && recommendationCacheCleared }`
  - `WeeklyCacheCleaner(weeklyPlayLog: WeeklyPlayLogPort, store: WeeklyRecCachePort, nowMs: () -> Long = { System.currentTimeMillis() })`：
    - `suspend fun cleanupOnPageOpen(userId: Long, now: Long = nowMs())` — 只 prune + removeInvalid，**不 read**
    - `suspend fun cleanupOnAppStart(userId: Long, now: Long = nowMs())` — 全局 prune + 当前账号 removeInvalid
    - `suspend fun clearWeeklyUserData(userId: Long, now: Long = nowMs()): ClearWeeklyDataResult` — 删除全部 + 缓存 commit 重试；`CancellationException` 重抛，其余 Exception 记日志返回 false
  - `WeeklyLogoutCoordinator(invalidateSession: () -> Unit, cancelInFlight: suspend () -> Unit, cleaner: WeeklyCacheCleaner)`：
    - `suspend fun execute(userId: Long, now: Long = System.currentTimeMillis()): ClearWeeklyDataResult` — 严格顺序：invalidate → cancel → clear

- [ ] **Step 1: 修改 SessionManager 并加 `sessionGeneration`**

`SessionManager.kt`（第 34-38 行 `playbackQuality` 之后）加：

```kotlin
    /** 会话版本号：登录/退出时单调递增，用于让过期生成任务放弃写缓存。 */
    var sessionGeneration: Int
        get() = prefs.getInt("session_generation", 0)
        private set(value) = prefs.edit().putInt("session_generation", value).apply()

    fun invalidate() {
        sessionGeneration++
    }
```

`saveLoginInfo`（第 46-51 行）末尾加一行：

```kotlin
        this.vipType = vipType
        sessionGeneration++
```

- [ ] **Step 2: 写失败测试（SessionGeneration）**

创建 `app/src/test/java/com/ncm/app/data/SessionGenerationTest.kt`：

```kotlin
package com.ncm.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionGenerationTest {

    private fun newManager(): SessionManager = SessionManager(RuntimeEnvironment.getApplication())

    @Test
    fun initialGenerationIsZero() {
        assertEquals(0, newManager().sessionGeneration)
    }

    @Test
    fun invalidateIncrementsGeneration() {
        val manager = newManager()
        manager.invalidate()
        assertEquals(1, manager.sessionGeneration)
        manager.invalidate()
        assertEquals(2, manager.sessionGeneration)
    }

    @Test
    fun saveLoginInfoIncrementsGeneration() {
        val manager = newManager()
        manager.saveLoginInfo(1L, "n", null, 0)
        assertEquals(1, manager.sessionGeneration)
    }
}
```

- [ ] **Step 3: 运行失败（SessionGeneration）**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.SessionGenerationTest"`
Expected: FAIL — `sessionGeneration` 未定义。

- [ ] **Step 4: 写失败测试（Cleaner / Coordinator）**

创建 `app/src/test/java/com/ncm/app/data/weekly/WeeklyCacheCleanerTest.kt`：

```kotlin
package com.ncm.app.data.weekly

import android.content.Context
import com.ncm.app.domain.weekly.CachedSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyCacheCleanerTest {

    private class FakeLogPort : WeeklyPlayLogPort {
        var pruneExpiredCalls = 0
        var pruneAllCalls = 0
        var readCount = 0
        var deletedUsers = mutableListOf<Long>()
        var failDelete = false
        override suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat> {
            readCount++
            return emptyList()
        }
        override suspend fun pruneExpired(userId: Long, now: Long) { pruneExpiredCalls++ }
        override suspend fun pruneAllUsersExpired(now: Long) { pruneAllCalls++ }
        override suspend fun deleteAllByUser(userId: Long): Long {
            deletedUsers += userId
            if (failDelete) throw IOException("room unavailable")
            return 3
        }
    }

    private val sourceWeek = LocalDate.of(2026, 7, 20)
    private val displayWeek = LocalDate.of(2026, 7, 27)
    private val songs = listOf(CachedSong(1, "A", listOf("X"), null))
    private val fixedNow = 1_750_000_000_000L

    private lateinit var logPort: FakeLogPort
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: WeeklyRecommendationStore
    private lateinit var cleaner: WeeklyCacheCleaner

    @Before
    fun setUp() {
        logPort = FakeLogPort()
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("weekly_cleaner_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = WeeklyRecommendationStore(prefs)
        cleaner = WeeklyCacheCleaner(logPort, store)
    }

    @Test
    fun cleanupOnPageOpen_prunesAndRemovesCorruptCache() = runBlocking {
        prefs.edit().putString("weekly_rec:1", "{corrupt").commit()
        cleaner.cleanupOnPageOpen(1L, now = fixedNow)
        assertEquals(1, logPort.pruneExpiredCalls)
        assertEquals(0, logPort.readCount)                       // 打开页面不得读播放记录
        assertNull(prefs.getString("weekly_rec:1", null))
    }

    @Test
    fun cleanupOnPageOpen_keepsValidCache() = runBlocking {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 1L)
        cleaner.cleanupOnPageOpen(1L, now = fixedNow)
        assertNotNull(prefs.getString("weekly_rec:1", null))
    }

    @Test
    fun cleanupOnAppStart_prunesAllAndRemovesInvalidForUser() = runBlocking {
        prefs.edit().putString("weekly_rec:5", "{bad").commit()
        cleaner.cleanupOnAppStart(5L, now = fixedNow)
        assertEquals(1, logPort.pruneAllCalls)
        assertNull(prefs.getString("weekly_rec:5", null))
    }

    @Test
    fun clearWeeklyUserData_successClearsRoomAndCache() = runBlocking {
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 1L)
        val result = cleaner.clearWeeklyUserData(1L)
        assertTrue(result.success)
        assertEquals(listOf(1L), logPort.deletedUsers)
        assertNull(prefs.getString("weekly_rec:1", null))
    }

    @Test
    fun clearWeeklyUserData_roomFailureReportedButCacheStillCleared() = runBlocking {
        logPort.failDelete = true
        store.putSuccess(1L, sourceWeek, displayWeek, songs, seedCount = 1, generatedAt = 1L)
        val result = cleaner.clearWeeklyUserData(1L)
        assertFalse(result.success)
        assertFalse(result.roomCleared)
        assertTrue(result.recommendationCacheCleared)
        assertNull(prefs.getString("weekly_rec:1", null))
    }

    @Test
    fun clearWeeklyUserData_neverReadsPlayLog() = runBlocking {
        cleaner.clearWeeklyUserData(1L)
        assertEquals(0, logPort.readCount)
    }
}
```

创建 `app/src/test/java/com/ncm/app/data/weekly/WeeklyLogoutCoordinatorTest.kt`：

```kotlin
package com.ncm.app.data.weekly

import com.ncm.app.domain.weekly.CachedSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class WeeklyLogoutCoordinatorTest {

    private class FakeLogPort(
        private val calls: MutableList<String>,
        var failDelete: Boolean = false
    ) : WeeklyPlayLogPort {
        override suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat> = emptyList()
        override suspend fun pruneExpired(userId: Long, now: Long) {}
        override suspend fun pruneAllUsersExpired(now: Long) {}
        override suspend fun deleteAllByUser(userId: Long): Long {
            calls += "clear-room"
            if (failDelete) throw IOException("room unavailable")
            return 2
        }
    }

    private class FakeCachePort(
        private val calls: MutableList<String>
    ) : WeeklyRecCachePort {
        var durableRemoveCalls = 0
        override fun get(userId: Long, displayWeekStart: LocalDate, sourceWeekStart: LocalDate) = null
        override fun putSuccess(userId: Long, sourceWeekStart: LocalDate, displayWeekStart: LocalDate, songs: List<CachedSong>, seedCount: Int, generatedAt: Long) {}
        override fun putInsufficientData(userId: Long, sourceWeekStart: LocalDate, displayWeekStart: LocalDate, validPlayCount: Int, distinctSongCount: Int, generatedAt: Long) {}
        override fun removeInvalidIfPresent(userId: Long): Boolean = false
        override suspend fun removeForUserDurable(userId: Long): Boolean {
            calls += "clear-cache"
            durableRemoveCalls++
            return true
        }
    }

    private val calls = mutableListOf<String>()
    private lateinit var logPort: FakeLogPort
    private lateinit var cachePort: FakeCachePort
    private lateinit var cleaner: WeeklyCacheCleaner

    @Before
    fun setUp() {
        logPort = FakeLogPort(calls)
        cachePort = FakeCachePort(calls)
        cleaner = WeeklyCacheCleaner(logPort, cachePort)
    }

    @Test
    fun execute_runsStrictOrderInvalidateThenCancelThenClear() = runBlocking {
        val coordinator = WeeklyLogoutCoordinator(
            invalidateSession = { calls += "invalidate" },
            cancelInFlight = { calls += "cancel"; delay(10) },
            cleaner = cleaner
        )
        val result = coordinator.execute(userId = 7L, now = 1_750_000_000_000L)
        assertEquals(listOf("invalidate", "cancel", "clear-room", "clear-cache"), calls)
        assertTrue(result.success)
        assertEquals(1, cachePort.durableRemoveCalls)
    }

    @Test
    fun execute_returnsFailureResultWhenRoomDeleteThrows() = runBlocking {
        logPort.failDelete = true
        val coordinator = WeeklyLogoutCoordinator(
            invalidateSession = { calls += "invalidate" },
            cancelInFlight = { calls += "cancel" },
            cleaner = cleaner
        )
        val result = coordinator.execute(userId = 7L, now = 1_750_000_000_000L)
        assertNotNull(result)
        assertTrue(!result.success)
        assertEquals(listOf("invalidate", "cancel", "clear-room", "clear-cache"), calls)
    }
}
```

- [ ] **Step 5: 运行失败（Cleaner / Coordinator）**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.weekly.WeeklyCacheCleanerTest" --tests "com.ncm.app.data.weekly.WeeklyLogoutCoordinatorTest"`
Expected: FAIL — `unresolved reference: WeeklyCacheCleaner` / `WeeklyLogoutCoordinator` / `ClearWeeklyDataResult`。

- [ ] **Step 6: 写实现**

创建 `app/src/main/java/com/ncm/app/data/weekly/WeeklyCacheCleaner.kt`：

```kotlin
package com.ncm.app.data.weekly

import android.util.Log
import kotlinx.coroutines.CancellationException

data class ClearWeeklyDataResult(
    val roomCleared: Boolean,
    val recommendationCacheCleared: Boolean
) {
    val success: Boolean get() = roomCleared && recommendationCacheCleared
}

/**
 * 每周推荐数据清理。页面打开/App 启动时清理过期与损坏数据；退出登录时清空当前账号数据。
 * 注意：cleanupOnPageOpen 只清理、不读取播放记录（"命中缓存零请求" 的前提）。
 */
class WeeklyCacheCleaner(
    private val weeklyPlayLog: WeeklyPlayLogPort,
    private val store: WeeklyRecCachePort,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun cleanupOnPageOpen(userId: Long, now: Long = nowMs()) {
        weeklyPlayLog.pruneExpired(userId, now)
        store.removeInvalidIfPresent(userId)
    }

    suspend fun cleanupOnAppStart(userId: Long, now: Long = nowMs()) {
        weeklyPlayLog.pruneAllUsersExpired(now)
        if (userId > 0) {
            store.removeInvalidIfPresent(userId)
        }
    }

    suspend fun clearWeeklyUserData(userId: Long, now: Long = nowMs()): ClearWeeklyDataResult {
        val roomCleared = try {
            weeklyPlayLog.deleteAllByUser(userId) >= 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "clearWeeklyUserData: room delete failed", e)
            false
        }
        val cacheCleared = try {
            store.removeForUserDurable(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "clearWeeklyUserData: cache delete failed", e)
            false
        }
        return ClearWeeklyDataResult(
            roomCleared = roomCleared,
            recommendationCacheCleared = cacheCleared
        )
    }

    private companion object {
        const val TAG = "WeeklyCacheCleaner"
    }
}

/**
 * 退出登录协调器：保证严格顺序 ① invalidate → ② cancelInFlight → ③ clearWeeklyUserData。
 * 通过 lambda 注入，便于单测记录调用顺序。
 */
class WeeklyLogoutCoordinator(
    private val invalidateSession: () -> Unit,
    private val cancelInFlight: suspend () -> Unit,
    private val cleaner: WeeklyCacheCleaner
) {
    suspend fun execute(userId: Long, now: Long = System.currentTimeMillis()): ClearWeeklyDataResult {
        invalidateSession()
        cancelInFlight()
        return cleaner.clearWeeklyUserData(userId, now)
    }
}
```

- [ ] **Step 7: 运行通过**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.weekly.WeeklyCacheCleanerTest" --tests "com.ncm.app.data.weekly.WeeklyLogoutCoordinatorTest" --tests "com.ncm.app.data.SessionGenerationTest"`
Expected: PASS（6 + 2 + 3 = 11 个用例）。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/ncm/app/data/SessionManager.kt app/src/main/java/com/ncm/app/data/weekly/WeeklyCacheCleaner.kt app/src/test/java/com/ncm/app/data/SessionGenerationTest.kt app/src/test/java/com/ncm/app/data/weekly/WeeklyCacheCleanerTest.kt app/src/test/java/com/ncm/app/data/weekly/WeeklyLogoutCoordinatorTest.kt
git commit -m "feat(weekly): add session generation, weekly data cleaner and logout coordinator"
```

---

### Task 8: 每周推荐生成用例（GenerateWeeklyRecommendationUseCase）

**Files:**
- Create: `app/src/main/java/com/ncm/app/domain/weekly/GenerateWeeklyRecommendationUseCase.kt`
- Test: `app/src/test/java/com/ncm/app/domain/weekly/GenerateWeeklyRecommendationUseCaseTest.kt`

**Interfaces:**
- Consumes: `WeeklyRecommendationSource`（Task 3）、`WeeklyPlayLogPort`（Task 2）、`WeeklyRecCachePort` + `WeeklyRecResult`（Task 4）、`WeeklyRecommendationAlgorithm`/`Seed`/`CandidateEdge`（Task 5）、`Song.toCachedSong()`（Task 4）、`SimilarSong`（Task 3）
- Produces:
  - `data class GenerationKey(userId: Long, displayWeekStart: LocalDate)`
  - `interface WeeklyGenerationController { suspend fun cancelGenerationForUser(userId: Long) }`
  - 文件级 `fun weekBounds(sourceWeekStart: LocalDate, displayWeekStart: LocalDate, zoneId: ZoneId): Pair<Long, Long>`
  - `class GenerateWeeklyRecommendationUseCase(source, weeklyPlayLog, store, currentUserId: () -> Long, currentSessionGeneration: () -> Int, scope: CoroutineScope, zoneIdProvider: () -> ZoneId, nowMs: () -> Long) : WeeklyGenerationController`
    - `suspend fun execute(key: GenerationKey): WeeklyRecResult`
    - `override suspend fun cancelGenerationForUser(userId: Long)`
  - 常量：`MIN_DISTINCT_SONGS=2`、`HYDRATE_ALL_LIMIT=100`、`HYDRATE_TOP_LIMIT=20`、`BATCH_SIZE=50`、`CANDIDATE_HYDRATION_LIMIT=80`、`SIMILAR_TIMEOUT_MS=8_000L`、`SIMILAR_CONCURRENCY=4`
  - 关键行为：缓存命中 → 零请求返回；数据不足写 InsufficientData 缓存；种子不足/全失败 → Failure 不写缓存；写缓存前用 `sessionGeneration` 快照校验（账号切换则放弃写）；single-flight（`Mutex` 只保护任务查/建，`invokeOnCompletion` 清理用 `scope.launch` 避免持锁挂起死锁）；每个网络块先捕 `TimeoutCancellationException` 再捕 `CancellationException`（重抛）再捕 `Exception`（跳过该种子/批次）。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/ncm/app/domain/weekly/GenerateWeeklyRecommendationUseCaseTest.kt`：

```kotlin
package com.ncm.app.domain.weekly

import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.Song
import com.ncm.app.data.weekly.WeeklyPlayLogPort
import com.ncm.app.data.weekly.WeeklySongStat
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GenerateWeeklyRecommendationUseCaseTest {

    private class FakeSource : WeeklyRecommendationSource {
        val similarResults = mutableMapOf<Long, List<SimilarSong>>()
        val similarFailures = mutableSetOf<Long>()
        val detailResults = mutableMapOf<Long, Song>()
        var similarCallCount = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getSimilarSongs(songId: Long): List<SimilarSong> {
            gate?.await()
            similarCallCount++
            if (songId in similarFailures) throw IOException("similar failed for $songId")
            return similarResults[songId].orEmpty()
        }

        override suspend fun getSongDetails(ids: List<Long>): List<Song> {
            gate?.await()
            return ids.flatMap { id -> detailResults[id]?.let { listOf(it) }.orEmpty() }
        }
    }

    private class FakeLog : WeeklyPlayLogPort {
        var stats = emptyList<WeeklySongStat>()
        var readCount = 0
        override suspend fun read(userId: Long, startMs: Long, endMs: Long): List<WeeklySongStat> {
            readCount++
            return stats
        }
        override suspend fun pruneExpired(userId: Long, now: Long) {}
        override suspend fun pruneAllUsersExpired(now: Long) {}
        override suspend fun deleteAllByUser(userId: Long): Long = 1
    }

    private class FakeStore : WeeklyRecCachePort {
        data class PutSuccess(
            val userId: Long,
            val sourceWeekStart: LocalDate,
            val displayWeekStart: LocalDate,
            val songs: List<CachedSong>,
            val seedCount: Int
        )

        var getResult: WeeklyRecResult? = null
        var lastPutSuccess: PutSuccess? = null
        val putSuccessKeys = mutableListOf<String>()
        var putInsufficient: Pair<Int, Int>? = null

        override fun get(userId: Long, displayWeekStart: LocalDate, sourceWeekStart: LocalDate): WeeklyRecResult? = getResult
        override fun putSuccess(
            userId: Long,
            sourceWeekStart: LocalDate,
            displayWeekStart: LocalDate,
            songs: List<CachedSong>,
            seedCount: Int,
            generatedAt: Long
        ) {
            lastPutSuccess = PutSuccess(userId, sourceWeekStart, displayWeekStart, songs, seedCount)
            putSuccessKeys += "$userId:$displayWeekStart"
        }
        override fun putInsufficientData(
            userId: Long,
            sourceWeekStart: LocalDate,
            displayWeekStart: LocalDate,
            validPlayCount: Int,
            distinctSongCount: Int,
            generatedAt: Long
        ) {
            putInsufficient = validPlayCount to distinctSongCount
        }
        override fun removeInvalidIfPresent(userId: Long): Boolean = false
        override suspend fun removeForUserDurable(userId: Long): Boolean = true
    }

    private val week = LocalDate.of(2026, 7, 27)
    private val previousWeek = LocalDate.of(2026, 7, 20)
    private val zone = ZoneId.of("UTC")
    private val fixedNow = 1_750_000_000_000L
    private val lastPlayed = 1_749_000_000_000L

    private lateinit var source: FakeSource
    private lateinit var log: FakeLog
    private lateinit var store: FakeStore

    @Before
    fun setUp() {
        source = FakeSource()
        log = FakeLog()
        store = FakeStore()
    }

    private fun buildUseCase(
        scope: CoroutineScope,
        userId: () -> Long = { 1L },
        sessionGen: () -> Int = { 0 }
    ) = GenerateWeeklyRecommendationUseCase(
        source = source,
        weeklyPlayLog = log,
        store = store,
        currentUserId = userId,
        currentSessionGeneration = sessionGen,
        scope = scope,
        zoneIdProvider = { zone },
        nowMs = { fixedNow }
    )

    private fun song(id: Long, artistId: Long, name: String = "Song$id"): Song =
        Song(id = id, name = name, artists = listOf(ArtistBrief(artistId, "Artist$artistId")))

    /** 一个种子 + 两个候选，候选带艺人（Plan A）。 */
    private fun happyPathFixture() {
        log.stats = listOf(WeeklySongStat(songId = 1, playCount = 5, lastPlayedAt = lastPlayed))
        source.detailResults[1] = song(1, 10)
        source.similarResults[1] = listOf(
            SimilarSong(100, "Cand1", listOf(ArtistBrief(20, "ArtistB"))),
            SimilarSong(200, "Cand2", listOf(ArtistBrief(20, "ArtistB")))
        )
        source.detailResults[100] = song(100, 20, name = "Cand1")
        source.detailResults[200] = song(200, 20, name = "Cand2")
    }

    // ---- happy path & sufficiency ----

    @Test
    fun success_generatesAndWritesCache() = runTest {
        happyPathFixture()
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Success)
        val success = result as WeeklyRecResult.Success
        assertEquals(listOf(100L, 200L), success.songs.map { it.songId })
        assertEquals(1, success.seedCount)
        assertEquals(week, success.displayWeekStart)
        assertEquals(listOf("1:$week"), store.putSuccessKeys)
        assertEquals(previousWeek, store.lastPutSuccess!!.sourceWeekStart)
        assertEquals(listOf("Cand1", "Cand2"), store.lastPutSuccess!!.songs.map { it.name })
    }

    @Test
    fun cacheHit_returnsWithoutNetworkOrLogReads() = runTest {
        store.getResult = WeeklyRecResult.Success(
            songs = listOf(CachedSong(1, "Cached", listOf("X"), null)),
            seedCount = 1,
            displayWeekStart = week
        )
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Success)
        assertEquals(0, source.similarCallCount)
        assertEquals(0, log.readCount)
        assertNull(store.lastPutSuccess)
    }

    @Test
    fun insufficientDataCached_returnsWithoutNetwork() = runTest {
        store.getResult = WeeklyRecResult.InsufficientData(validPlayCount = 3, distinctSongCount = 1)
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.InsufficientData)
        assertEquals(0, source.similarCallCount)
        assertEquals(0, log.readCount)
    }

    @Test
    fun emptyStats_returnsAndCachesInsufficientData() = runTest {
        log.stats = emptyList()
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.InsufficientData)
        assertEquals(0 to 0, store.putInsufficient)
    }

    @Test
    fun tooFewDistinctSongs_returnsAndCachesInsufficientData() = runTest {
        log.stats = listOf(WeeklySongStat(songId = 1, playCount = 20, lastPlayedAt = lastPlayed))
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.InsufficientData)
        assertEquals(20 to 1, store.putInsufficient)
    }

    // ---- failure paths ----

    @Test
    fun allSimilarRequestsFail_returnsFailureWithoutCacheWrite() = runTest {
        happyPathFixture()
        source.similarFailures += 1L
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Failure)
        assertNull(store.lastPutSuccess)
    }

    @Test
    fun partialSeedFailure_continuesWithRemainingSeeds() = runTest {
        log.stats = listOf(
            WeeklySongStat(songId = 1, playCount = 5, lastPlayedAt = lastPlayed),
            WeeklySongStat(songId = 2, playCount = 4, lastPlayedAt = lastPlayed)
        )
        source.detailResults[1] = song(1, 10)
        source.detailResults[2] = song(2, 11)
        source.similarFailures += 2L                      // seed 2 fails
        source.similarResults[1] = listOf(SimilarSong(100, "Cand1", listOf(ArtistBrief(20, "ArtistB"))))
        source.detailResults[100] = song(100, 20, name = "Cand1")

        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Success)
        assertEquals(listOf(100L), (result as WeeklyRecResult.Success).songs.map { it.songId })
    }

    @Test
    fun noCandidateEdges_returnsFailure() = runTest {
        happyPathFixture()
        source.similarResults[1] = emptyList()
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Failure)
        assertNull(store.lastPutSuccess)
    }

    @Test
    fun listenedSongsAreExcludedFromResult() = runTest {
        happyPathFixture()
        log.stats = listOf(
            WeeklySongStat(songId = 1, playCount = 5, lastPlayedAt = lastPlayed),
            WeeklySongStat(songId = 200, playCount = 1, lastPlayedAt = lastPlayed)
        )
        val result = buildUseCase(scope = this).execute(GenerationKey(1L, week))
        assertTrue(result is WeeklyRecResult.Success)
        assertEquals(listOf(100L), (result as WeeklyRecResult.Success).songs.map { it.songId })
    }

    // ---- write-back guard ----

    @Test
    fun accountSwitchDuringGeneration_doesNotWriteCache() = runTest {
        happyPathFixture()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        var sessionGen = 0
        val useCase = buildUseCase(scope = this, sessionGen = { sessionGen })
        val caller = async { useCase.execute(GenerationKey(1L, week)) }
        runCurrent()
        sessionGen = 1                                   // 模拟退出/重登
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(store.lastPutSuccess)
        assertTrue(caller.await() is WeeklyRecResult.Failure)
    }

    // ---- single-flight ----

    @Test
    fun sameKeyConcurrentCalls_shareOneGeneration() = runTest {
        happyPathFixture()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val useCase = buildUseCase(scope = this)
        val a = async { useCase.execute(GenerationKey(1L, week)) }
        val b = async { useCase.execute(GenerationKey(1L, week)) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        val ra = a.await()
        val rb = b.await()
        assertNotNull(ra)
        assertNotNull(rb)
        assertEquals(1, source.similarCallCount)          // 只跑了一次生成
        assertEquals(listOf("1:$week"), store.putSuccessKeys)
    }

    @Test
    fun differentKey_cancelsPreviousGeneration() = runTest {
        happyPathFixture()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val week2 = week.plusWeeks(1)
        val useCase = buildUseCase(scope = this)
        val old = async { useCase.execute(GenerationKey(1L, week)) }
        runCurrent()
        val new = async { useCase.execute(GenerationKey(1L, week2)) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        org.junit.Assert.assertThrows(CancellationException::class.java) { old.await() }
        assertTrue(new.await() is WeeklyRecResult.Success)
        assertEquals(listOf("1:$week2"), store.putSuccessKeys)
    }

    @Test
    fun callerCancellation_doesNotCancelBackgroundTask() = runTest {
        happyPathFixture()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val useCase = buildUseCase(scope = this)
        val caller = async { useCase.execute(GenerationKey(1L, week)) }
        runCurrent()
        caller.cancel()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNotNull(store.lastPutSuccess)              // 后台任务继续完成并写缓存
    }

    // ---- week bounds ----

    @Test
    fun weekBounds_returnsStartAndEndAtLocalMidnight() {
        val (start, end) = weekBounds(previousWeek, week, ZoneId.of("UTC"))
        assertEquals(previousWeek.atStartOfDay(zone).toInstant().toEpochMilli(), start)
        assertEquals(week.atStartOfDay(zone).toInstant().toEpochMilli(), end)
    }

    @Test
    fun weekBounds_usesLocalNaturalDaysAcrossDst() {
        val dstZone = ZoneId.of("America/New_York")
        val sourceStart = LocalDate.of(2026, 3, 2)   // 周一
        val displayStart = LocalDate.of(2026, 3, 9)  // 周一（跨 2026-03-08 春季拨快）
        val (start, end) = weekBounds(sourceStart, displayStart, dstZone)
        assertEquals(sourceStart.atStartOfDay(dstZone).toInstant().toEpochMilli(), start)
        assertEquals(displayStart.atStartOfDay(dstZone).toInstant().toEpochMilli(), end)
        assertEquals((6 * 24L + 23) * 3_600_000L, end - start)  // 拨快损失 1 小时墙钟
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.domain.weekly.GenerateWeeklyRecommendationUseCaseTest"`
Expected: FAIL — `unresolved reference: GenerateWeeklyRecommendationUseCase` / `GenerationKey` / `weekBounds`。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/ncm/app/domain/weekly/GenerateWeeklyRecommendationUseCase.kt`：

```kotlin
package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Song
import com.ncm.app.data.weekly.WeeklyPlayLogPort
import com.ncm.app.data.weekly.WeeklySongStat
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

data class GenerationKey(
    val userId: Long,
    val displayWeekStart: LocalDate
)

interface WeeklyGenerationController {
    suspend fun cancelGenerationForUser(userId: Long)
}

/** 上一自然周 [sourceWeekStart] 00:00 → 本周 [displayWeekStart] 00:00（本地时区）。 */
fun weekBounds(
    sourceWeekStart: LocalDate,
    displayWeekStart: LocalDate,
    zoneId: ZoneId
): Pair<Long, Long> {
    val start = sourceWeekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val end = displayWeekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
    return start to end
}

class GenerateWeeklyRecommendationUseCase(
    private val source: WeeklyRecommendationSource,
    private val weeklyPlayLog: WeeklyPlayLogPort,
    private val store: WeeklyRecCachePort,
    private val currentUserId: () -> Long,
    private val currentSessionGeneration: () -> Int,
    private val scope: CoroutineScope,
    private val zoneIdProvider: () -> ZoneId,
    private val nowMs: () -> Long
) : WeeklyGenerationController {

    private data class InFlightTask(
        val key: GenerationKey,
        val job: kotlinx.coroutines.Deferred<WeeklyRecResult>
    )

    private val generationMutex = Mutex()
    private var inFlight: InFlightTask? = null
    private val semaphore = Semaphore(SIMILAR_CONCURRENCY)

    /**
     * Single-flight：同一 (userId, displayWeekStart) 同时只跑一个任务。
     * Mutex 只保护任务查/建；不持锁挂起（cancel 而非 cancelAndJoin）。
     * 清理经 scope.launch 执行，避免 invokeOnCompletion 在持锁等待时死锁。
     */
    suspend fun execute(key: GenerationKey): WeeklyRecResult {
        val runningTask = generationMutex.withLock { inFlight }
        if (runningTask != null && runningTask.key == key) {
            return runningTask.job.await()
        }
        if (runningTask != null) {
            runningTask.job.cancel()
        }

        val task = generationMutex.withLock {
            val current = inFlight
            if (current != null && current.key == key) {
                return@withLock current.job
            }
            val newJob = scope.async { generate(key) }
            val newTask = InFlightTask(key, newJob)
            inFlight = newTask
            newJob.invokeOnCompletion {
                scope.launch {
                    generationMutex.withLock {
                        if (inFlight?.job === newJob) inFlight = null
                    }
                }
            }
            newJob
        }
        return task.await()
    }

    override suspend fun cancelGenerationForUser(userId: Long) {
        val task = generationMutex.withLock { inFlight }
        if (task != null && task.key.userId == userId) {
            task.job.cancelAndJoin()
        }
    }

    private suspend fun generate(key: GenerationKey): WeeklyRecResult {
        val userId = key.userId
        val displayWeekStart = key.displayWeekStart
        val generationZone = zoneIdProvider()
        val sessionGenerationSnapshot = currentSessionGeneration()
        val sourceWeekStart = displayWeekStart.minusWeeks(1)

        // 缓存命中（含数据不足缓存）→ 零请求返回。
        store.get(userId, displayWeekStart, sourceWeekStart)?.let { return it }

        val (sourceStartMs, sourceEndMs) = weekBounds(sourceWeekStart, displayWeekStart, generationZone)
        val weeklyStats = weeklyPlayLog.read(userId, sourceStartMs, sourceEndMs)

        val distinctSongCount = weeklyStats.size
        val validPlayCount = weeklyStats.sumOf { it.playCount }
        if (distinctSongCount < MIN_DISTINCT_SONGS) {
            store.putInsufficientData(
                userId = userId,
                sourceWeekStart = sourceWeekStart,
                displayWeekStart = displayWeekStart,
                validPlayCount = validPlayCount,
                distinctSongCount = distinctSongCount,
                generatedAt = nowMs()
            )
            return WeeklyRecResult.InsufficientData(validPlayCount, distinctSongCount)
        }

        val hydrated = hydrateWeeklySongs(weeklyStats, sourceStartMs, sourceEndMs)
        val seeds = WeeklyRecommendationAlgorithm.selectSeeds(weeklyStats, hydrated, sourceStartMs, sourceEndMs)
        if (seeds.isEmpty()) {
            return WeeklyRecResult.Failure("缺少可用于生成推荐的歌曲数据")
        }

        val edges = fetchCandidateEdges(seeds)
        if (edges.isEmpty()) {
            return WeeklyRecResult.Failure("获取相似歌曲失败")
        }

        val listenedSongIds = weeklyStats.map { it.songId }.toSet()
        val rankedIds = WeeklyRecommendationAlgorithm.rankCandidates(edges, listenedSongIds)
        if (rankedIds.isEmpty()) {
            return WeeklyRecResult.Failure("未生成足够相似歌曲")
        }

        val details = fetchSongDetails(rankedIds)
        val byId = details.associateBy { it.id }
        val orderedSongs = rankedIds.mapNotNull { byId[it] }
        if (orderedSongs.isEmpty()) {
            return WeeklyRecResult.Failure("歌曲信息获取失败")
        }

        // 写缓存前校验会话仍一致：防止旧任务在退出/重登后把结果写进新会话。
        if (!isGenerationStillCurrent(userId, sessionGenerationSnapshot)) {
            return WeeklyRecResult.Failure(null)
        }

        val cachedSongs = orderedSongs.map { it.toCachedSong() }
        store.putSuccess(
            userId = userId,
            sourceWeekStart = sourceWeekStart,
            displayWeekStart = displayWeekStart,
            songs = cachedSongs,
            seedCount = seeds.size,
            generatedAt = nowMs()
        )
        return WeeklyRecResult.Success(cachedSongs, seeds.size, displayWeekStart)
    }

    private suspend fun isGenerationStillCurrent(userId: Long, sessionGenerationSnapshot: Int): Boolean {
        coroutineContext.ensureActive()
        return currentUserId() == userId && currentSessionGeneration() == sessionGenerationSnapshot
    }

    /** 先算初步分，≤100 全水合，>100 只水合前 20（供选种艺人信息）。批次 ≤50。 */
    private suspend fun hydrateWeeklySongs(
        weeklyStats: List<WeeklySongStat>,
        sourceStartMs: Long,
        sourceEndMs: Long
    ): Map<Long, Song> {
        val scored = weeklyStats
            .map { stat ->
                stat to WeeklyRecommendationAlgorithm.preliminaryScore(
                    stat.playCount, stat.lastPlayedAt, sourceStartMs, sourceEndMs
                )
            }
            .sortedByDescending { it.second }
        val toHydrate = if (scored.size <= HYDRATE_ALL_LIMIT) scored else scored.take(HYDRATE_TOP_LIMIT)
        val ids = toHydrate.map { it.first.songId }
        return fetchSongsBatched(ids).associateBy { it.id }
    }

    private suspend fun fetchSongsBatched(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        return supervisorScope {
            ids.chunked(BATCH_SIZE).map { batch ->
                async {
                    try {
                        withTimeout(SIMILAR_TIMEOUT_MS) { source.getSongDetails(batch) }
                    } catch (e: TimeoutCancellationException) {
                        emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }.flatten()
    }

    /** 每个种子拉相似歌曲（并发 ≤4，单种子 8 秒超时只跳该种子）；Plan A 缺艺人 → Plan B 水合补全。 */
    private suspend fun fetchCandidateEdges(seeds: List<Seed>): List<CandidateEdge> {
        if (seeds.isEmpty()) return emptyList()
        val perSeed = supervisorScope {
            seeds.map { seed ->
                async {
                    semaphore.withPermit {
                        try {
                            val similar = withTimeout(SIMILAR_TIMEOUT_MS) { source.getSimilarSongs(seed.songId) }
                            seed to similar.take(WeeklyRecommendationAlgorithm.SIM_LIMIT)
                        } catch (e: TimeoutCancellationException) {
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        val missingArtist = perSeed.any { (_, similar) -> similar.any { it.artists.isEmpty() } }
        val artistById: Map<Long, Long> = if (missingArtist) hydrateCandidateArtists(perSeed) else emptyMap()

        return perSeed.flatMap { (seed, similar) ->
            similar.mapIndexedNotNull { index, song ->
                val primaryArtistId = song.artists.firstOrNull()?.id ?: artistById[song.id] ?: 0L
                CandidateEdge(
                    seedSongId = seed.songId,
                    candidateSongId = song.id,
                    seedScore = seed.score,
                    simRank = index + 1,
                    primaryArtistId = primaryArtistId
                )
            }
        }
    }

    /** Plan B：候选缺艺人时，最多水合 80 首以补全 primaryArtistId。 */
    private suspend fun hydrateCandidateArtists(perSeed: List<Pair<Seed, List<SimilarSong>>>): Map<Long, Long> {
        val ids = perSeed
            .flatMap { it.second.map { song -> song.id } }
            .distinct()
            .take(CANDIDATE_HYDRATION_LIMIT)
        if (ids.isEmpty()) return emptyMap()
        val songs = fetchSongsBatched(ids)
        return songs.associate { it.id to (it.artists?.firstOrNull()?.id ?: 0L) }
    }

    /** 最终候选按 id 逐个取详情（并发 ≤4），单曲失败跳过。 */
    private suspend fun fetchSongDetails(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        return supervisorScope {
            ids.map { id ->
                async {
                    semaphore.withPermit {
                        try {
                            withTimeout(SIMILAR_TIMEOUT_MS) { source.getSongDetails(listOf(id)) }
                                .firstOrNull()
                        } catch (e: TimeoutCancellationException) {
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull()
    }

    companion object {
        const val MIN_DISTINCT_SONGS = 2
        const val HYDRATE_ALL_LIMIT = 100
        const val HYDRATE_TOP_LIMIT = 20
        const val BATCH_SIZE = 50
        const val CANDIDATE_HYDRATION_LIMIT = 80
        const val SIMILAR_TIMEOUT_MS = 8_000L
        const val SIMILAR_CONCURRENCY = 4
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.domain.weekly.GenerateWeeklyRecommendationUseCaseTest"`
Expected: PASS（16 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/ncm/app/domain/weekly/GenerateWeeklyRecommendationUseCase.kt app/src/test/java/com/ncm/app/domain/weekly/GenerateWeeklyRecommendationUseCaseTest.kt
git commit -m "feat(weekly): add single-flight weekly recommendation generation use case"
```

---

### Task 9: DI + 会话状态 + MainViewModel 周推荐状态与退出登录严格顺序 + UI 映射

**Files:**
- Modify: `app/src/main/java/com/ncm/app/NeteaseApp.kt`
- Modify: `app/src/main/java/com/ncm/app/MainActivity.kt`（`LaunchedEffect(appState.isLoggedIn, ...)`，第 190-196 行）
- Modify: `app/src/main/java/com/ncm/app/viewmodel/MainViewModel.kt`
- Create: `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt`
- Test: `app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecUiMapperTest.kt`（纯 JVM）

**Interfaces:**
- Consumes: `WeeklyPlayLog`/`WeeklyDatabase`（Task 2）、`WeeklyRecommendationStore`（Task 4）、`WeeklyCacheCleaner`/`WeeklyLogoutCoordinator`（Task 7）、`GenerateWeeklyRecommendationUseCase`/`GenerationKey`（Task 8）、`repository` 已实现 `WeeklyRecommendationSource`（Task 3）
- Produces:
  - `NeteaseApp`：`applicationScope`（文件级 `val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`）、字段 `weeklyPlayLog`/`weeklyRecommendationStore`/`generateWeeklyRecommendationUseCase`/`weeklyCacheCleaner`（`private set`）、`initWeeklyRecommendation()`、onCreate 末尾启动 `applicationScope.launch { weeklyCacheCleaner.cleanupOnAppStart(session.userId) }`
  - `WeeklyRecUiState` + `WeeklyRecUiMapper`（见 WeeklyRecUi.kt，含 `displayWeekLabel`、`successSubtitle`）
  - `MainViewModel`：`weeklyRecState: StateFlow<WeeklyRecUiState>`、`weeklyDetailSongs: StateFlow<List<Song>?>`、`weeklyDetailLoading: StateFlow<Boolean>`、`logoutCleanupWarning: StateFlow<String?>`、`consumeLogoutCleanupWarning()`、`loadWeeklyRecommendation()`、`cleanupWeeklyCacheOnPageOpen()`、`suspend fun hydrateWeeklyDetailSongsNow(): List<Song>?`、logout 严格顺序（invalidate → cancel → clear，失败置 warning）
  - `MainActivity.MainApp`：退出导航时若有 `logoutCleanupWarning` 弹 Toast 并消费

- [ ] **Step 1: 写失败测试（UI 映射）**

创建 `app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecUiMapperTest.kt`：

```kotlin
package com.ncm.app.domain.weekly

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyRecUiMapperTest {

    @Test
    fun successMapsToUiStateWithWeekLabel() {
        val result = WeeklyRecResult.Success(
            songs = listOf(CachedSong(1, "A", listOf("X"), null)),
            seedCount = 8,
            displayWeekStart = LocalDate.of(2026, 7, 27)
        )
        val ui = WeeklyRecUiMapper.toUiState(result, ZoneId.of("UTC"))
        assertTrue(ui is WeeklyRecUiState.Success)
        ui as WeeklyRecUiState.Success
        assertEquals("第 31 周", ui.displayWeekLabel)
        assertEquals(8, ui.seedCount)
        assertEquals(1, ui.songs.size)
    }

    @Test
    fun insufficientDataMapsToUiState() {
        val ui = WeeklyRecUiMapper.toUiState(
            WeeklyRecResult.InsufficientData(validPlayCount = 3, distinctSongCount = 1),
            ZoneId.of("UTC")
        )
        assertTrue(ui is WeeklyRecUiState.InsufficientData)
        ui as WeeklyRecUiState.InsufficientData
        assertEquals(3, ui.validPlayCount)
        assertEquals(1, ui.distinctSongCount)
    }

    @Test
    fun failureMapsToErrorWithFallbackMessage() {
        val ui = WeeklyRecUiMapper.toUiState(WeeklyRecResult.Failure(null), ZoneId.of("UTC"))
        assertTrue(ui is WeeklyRecUiState.Error)
        assertEquals("获取每周推荐失败，请稍后重试", (ui as WeeklyRecUiState.Error).message)

        val withMessage = WeeklyRecUiMapper.toUiState(WeeklyRecResult.Failure("自定义"), ZoneId.of("UTC"))
        assertEquals("自定义", (withMessage as WeeklyRecUiState.Error).message)
    }

    @Test
    fun weekLabelFormatUsesIsoWeek() {
        assertEquals("第 31 周", WeeklyRecUiMapper.displayWeekLabel(LocalDate.of(2026, 7, 27)))
        assertEquals("第 1 周", WeeklyRecUiMapper.displayWeekLabel(LocalDate.of(2026, 12, 28)))
    }

    @Test
    fun successSubtitleFormat() {
        assertEquals("根据上周 8 首常听歌曲生成 · 26 首", WeeklyRecUiMapper.successSubtitle(8, 26))
    }
}
```

> `LocalDate.of(2026, 12, 28)` 是 ISO 2027-W01 周一的日期，因此 `WEEK_OF_WEEK_BASED_YEAR` 返回 1（2026 年 12 月 28 日所在周按 2027 周基准算为第 1 周）。如果实现使用的正是 `IsoFields.WEEK_OF_WEEK_BASED_YEAR`，该断言成立；若你选择 `ALIGNED_WEEK_OF_YEAR` 等不同字段，调整此断言以匹配实现。

- [ ] **Step 2: 运行失败（UI 映射）**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.domain.weekly.WeeklyRecUiMapperTest"`
Expected: FAIL — `unresolved reference: WeeklyRecUiState` / `WeeklyRecUiMapper`。

- [ ] **Step 3: 写实现（WeeklyRecUi + NeteaseApp DI）**

**(a)** 创建 `app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt`：

```kotlin
package com.ncm.app.domain.weekly

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

sealed class WeeklyRecUiState {
    object Loading : WeeklyRecUiState()
    data class Success(
        val songs: List<CachedSong>,
        val seedCount: Int,
        val displayWeekLabel: String
    ) : WeeklyRecUiState()
    data class InsufficientData(val validPlayCount: Int, val distinctSongCount: Int) : WeeklyRecUiState()
    data class Error(val message: String) : WeeklyRecUiState()
}

object WeeklyRecUiMapper {
    fun toUiState(result: WeeklyRecResult, zoneId: ZoneId = ZoneId.systemDefault()): WeeklyRecUiState = when (result) {
        is WeeklyRecResult.Success -> WeeklyRecUiState.Success(
            songs = result.songs,
            seedCount = result.seedCount,
            displayWeekLabel = displayWeekLabel(result.displayWeekStart)
        )
        is WeeklyRecResult.InsufficientData -> WeeklyRecUiState.InsufficientData(
            validPlayCount = result.validPlayCount,
            distinctSongCount = result.distinctSongCount
        )
        is WeeklyRecResult.Failure -> WeeklyRecUiState.Error(result.message ?: "获取每周推荐失败，请稍后重试")
    }

    /** 显示周起始日（周一）的 ISO 周号。 */
    fun displayWeekLabel(displayWeekStart: LocalDate): String =
        "第 ${displayWeekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} 周"

    fun successSubtitle(seedCount: Int, songCount: Int): String =
        "根据上周 $seedCount 首常听歌曲生成 · $songCount 首"
}
```

**(b)** `NeteaseApp.kt`：
- 顶部 import 加：
  ```kotlin
  import android.content.Context
  import com.ncm.app.data.weekly.WeeklyCacheCleaner
  import com.ncm.app.data.weekly.WeeklyPlayLog
  import com.ncm.app.data.weekly.WeeklyRecommendationStore
  import com.ncm.app.data.weekly.WeeklyDatabase
  import com.ncm.app.domain.weekly.GenerateWeeklyRecommendationUseCase
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.launch
  import java.time.ZoneId
  ```
- `class NeteaseApp : Application() {` 之前（第 19 行）加文件级：
  ```kotlin
  /** App 级协程作用域：周推荐生成/清理等后台任务用它，避免被调用方取消连带。 */
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  ```
- 类内字段（`playerAppearanceSettings` 之后）加：
  ```kotlin
      lateinit var weeklyPlayLog: WeeklyPlayLog
          private set
      lateinit var weeklyRecommendationStore: WeeklyRecommendationStore
          private set
      lateinit var generateWeeklyRecommendationUseCase: GenerateWeeklyRecommendationUseCase
          private set
      lateinit var weeklyCacheCleaner: WeeklyCacheCleaner
          private set
  ```
- `onCreate()`（第 57 行 `initNetwork()` 之后）改为：
  ```kotlin
          initNetwork()
          initWeeklyRecommendation()
          applicationScope.launch {
              weeklyCacheCleaner.cleanupOnAppStart(session.userId)
          }
  ```
- 类末尾（`initNetwork()` 之后、`companion object` 之前）加：
  ```kotlin
      private fun initWeeklyRecommendation() {
          weeklyPlayLog = WeeklyPlayLog(WeeklyDatabase.get(this))
          val weeklyPrefs = getSharedPreferences(WeeklyRecommendationStore.PREF_NAME, Context.MODE_PRIVATE)
          weeklyRecommendationStore = WeeklyRecommendationStore(weeklyPrefs)
          weeklyCacheCleaner = WeeklyCacheCleaner(weeklyPlayLog, weeklyRecommendationStore)
          generateWeeklyRecommendationUseCase = GenerateWeeklyRecommendationUseCase(
              source = repository,
              weeklyPlayLog = weeklyPlayLog,
              store = weeklyRecommendationStore,
              currentUserId = { session.userId },
              currentSessionGeneration = { session.sessionGeneration },
              scope = applicationScope,
              zoneIdProvider = { ZoneId.systemDefault() },
              nowMs = { System.currentTimeMillis() }
          )
      }
  ```

- [ ] **Step 4: 运行通过（UI 映射）**

Run: `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.domain.weekly.WeeklyRecUiMapperTest"`
Expected: PASS（5 个用例）。若 `weekLabelFormatUsesIsoWeek` 断言与实现不一致（周号字段选择不同），修正测试断言使之一致（实现与测试二选一保持确定即可）。

- [ ] **Step 5: 写实现（MainViewModel + MainActivity）**

**(a)** `MainViewModel.kt`：
- import 区加：
  ```kotlin
  import android.util.Log
  import com.ncm.app.data.weekly.WeeklyCacheCleaner
  import com.ncm.app.data.weekly.WeeklyLogoutCoordinator
  import com.ncm.app.domain.weekly.GenerationKey
  import com.ncm.app.domain.weekly.GenerateWeeklyRecommendationUseCase
  import com.ncm.app.domain.weekly.WeeklyRecUiMapper
  import com.ncm.app.domain.weekly.WeeklyRecUiState
  import java.time.DayOfWeek
  import java.time.LocalDate
  import java.time.ZoneId
  import java.time.temporal.TemporalAdjusters
  ```
- `_myState` 声明之后（第 91 行附近）加状态与依赖：
  ```kotlin
      private val _weeklyRecState = MutableStateFlow<WeeklyRecUiState>(WeeklyRecUiState.Loading)
      val weeklyRecState: StateFlow<WeeklyRecUiState> = _weeklyRecState

      private val _weeklyDetailSongs = MutableStateFlow<List<Song>?>(null)
      val weeklyDetailSongs: StateFlow<List<Song>?> = _weeklyDetailSongs

      private val _weeklyDetailLoading = MutableStateFlow(false)
      val weeklyDetailLoading: StateFlow<Boolean> = _weeklyDetailLoading

      private val _logoutCleanupWarning = MutableStateFlow<String?>(null)
      val logoutCleanupWarning: StateFlow<String?> = _logoutCleanupWarning

      private val generateWeeklyRecommendationUseCase: GenerateWeeklyRecommendationUseCase
          get() = NeteaseApp.instance.generateWeeklyRecommendationUseCase
      private val weeklyCacheCleaner: WeeklyCacheCleaner
          get() = NeteaseApp.instance.weeklyCacheCleaner

      private val logoutCoordinator by lazy {
          WeeklyLogoutCoordinator(
              invalidateSession = { session.invalidate() },
              cancelInFlight = { generateWeeklyRecommendationUseCase.cancelGenerationForUser(session.userId) },
              cleaner = weeklyCacheCleaner
          )
      }

      fun consumeLogoutCleanupWarning() {
          _logoutCleanupWarning.value = null
      }
  ```
- 在 `loadMyData`（第 392 行）之前加三个方法：
  ```kotlin
      fun loadWeeklyRecommendation() {
          if (_weeklyRecState.value is WeeklyRecUiState.Success ||
              _weeklyRecState.value is WeeklyRecUiState.InsufficientData
          ) return
          val userId = session.userId
          if (userId <= 0 || !session.isLoggedIn) {
              _weeklyRecState.value = WeeklyRecUiState.Loading
              return
          }
          viewModelScope.launch {
              val zoneId = ZoneId.systemDefault()
              val displayWeekStart = LocalDate.now(zoneId)
                  .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
              val result = generateWeeklyRecommendationUseCase.execute(
                  GenerationKey(userId = userId, displayWeekStart = displayWeekStart)
              )
              _weeklyRecState.value = WeeklyRecUiMapper.toUiState(result)
          }
      }

      fun cleanupWeeklyCacheOnPageOpen() {
          val userId = session.userId
          if (userId <= 0) return
          viewModelScope.launch {
              weeklyCacheCleaner.cleanupOnPageOpen(userId)
          }
      }

      /** 详情页水合：返回整份歌单的完整 Song 列表（含封面）。已加载或加载中直接返回。 */
      suspend fun hydrateWeeklyDetailSongsNow(): List<Song>? {
          _weeklyDetailSongs.value?.takeIf { it.isNotEmpty() }?.let { return it }
          if (_weeklyDetailLoading.value) return null
          _weeklyDetailLoading.value = true
          return try {
              val state = _weeklyRecState.value
              val ids = (state as? WeeklyRecUiState.Success)?.songs?.map { it.songId }.orEmpty()
              if (ids.isEmpty()) {
                  _weeklyDetailSongs.value = null
                  null
              } else {
                  val loaded = repo.getSongDetail(ids).getOrNull().orEmpty()
                  if (loaded.isEmpty()) {
                      _weeklyDetailSongs.value = null
                      null
                  } else {
                      _weeklyDetailSongs.value = loaded
                      loaded
                  }
              }
          } finally {
              _weeklyDetailLoading.value = false
          }
      }
  ```
- **替换整个 `logout()`（第 571-588 行）**为严格顺序版本：
  ```kotlin
      fun logout() {
          viewModelScope.launch {
              qrPollingJob?.cancel()
              val userId = session.userId
              // 严格顺序：① invalidate → ② cancelInFlight → ③ clearWeeklyUserData
              val cleanupResult = logoutCoordinator.execute(userId)
              if (!cleanupResult.success) {
                  Log.e(
                      "MainViewModel",
                      "weekly data cleanup failed: room=${cleanupResult.roomCleared} cache=${cleanupResult.recommendationCacheCleared}"
                  )
                  _logoutCleanupWarning.value = "部分本地数据清理失败"
              }
              _appState.value = AppUiState(isLoggedIn = false)
              _loginState.value = LoginUiState()
              _myState.value = MyUiState(isLoading = false)
              _weeklyRecState.value = WeeklyRecUiState.Loading
              _weeklyDetailSongs.value = null
              _weeklyDetailLoading.value = false
              playlistCache.clear()
              artistDetailCache.clear()
              quickListCache.clear()
              _discoverState.value = DiscoverUiState()
              _artistDetailState.value = ArtistDetailUiState()
              cache.clearUserData()
              repo.logout()
              session.clear()
              CookieManager.getInstance().removeAllCookies(null)
              CookieManager.getInstance().flush()
          }
      }
  ```

**(b)** `MainActivity.kt` — `MainApp` 里替换 `LaunchedEffect(appState.isLoggedIn, currentRoute)`（第 190-196 行）：

```kotlin
    LaunchedEffect(appState.isLoggedIn, currentRoute) {
        if (!appState.isLoggedIn && currentRoute != null && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
            val context = LocalContext.current
            val warning = mainViewModel.logoutCleanupWarning.value
            if (warning != null) {
                Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
                mainViewModel.consumeLogoutCleanupWarning()
            }
        }
    }
```

- [ ] **Step 6: 编译验证**

Run: `.\gradlew.bat --no-daemon :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/ncm/app/NeteaseApp.kt app/src/main/java/com/ncm/app/MainActivity.kt app/src/main/java/com/ncm/app/viewmodel/MainViewModel.kt app/src/main/java/com/ncm/app/domain/weekly/WeeklyRecUi.kt app/src/test/java/com/ncm/app/domain/weekly/WeeklyRecUiMapperTest.kt
git commit -m "feat(weekly): wire DI, weekly state and strict logout ordering into app"
```

---

### Task 10: AppPlayer 会话身份 + PlayerViewModel 播放记录钩子

**Files:**
- Modify: `app/src/main/java/com/ncm/app/playback/AppPlayer.kt`
- Modify: `app/src/main/java/com/ncm/app/viewmodel/PlayerViewModel.kt`

**Interfaces:**
- Consumes: `PlaySessionAccumulator`（Task 6）、`PlayEventEntity`/`NeteaseApp.instance.weeklyPlayLog`（Task 2/9）
- Produces:
  - `AppPlayer`：
    - `val sessionAccumulator = PlaySessionAccumulator()`
    - `fun beginPlaybackSession(song: Song)` — `sessionAccumulator.beginSession()` + 记录 `"${song.id}:${sessionStartedAt}"`
    - `fun currentPlaybackSessionId(): String?`、`fun currentPlaybackSessionSongId(): Long`、`fun currentPlaybackSessionStartedAt(): Long`
  - `PlayerViewModel`：
    - `onMediaItemTransition`（第 159 行 `AppPlayer.updateCurrentPlayback(...)` 之后）加 `AppPlayer.beginPlaybackSession(prepared.song)`
    - `setProgress`（第 331 行 `player.seekTo(position)` 之前）加 `AppPlayer.sessionAccumulator.onSeekStarted()`
    - `startProgressUpdates`（第 1238-1258 行）累计 + 判定 + 记录
    - 新增 `recordQualifiedPlay(songId, playbackSessionId, sessionStartedAt)`（捕获后立即记录，避免切歌竞态）

- [ ] **Step 1: 修改 AppPlayer.kt**

在 `AppPlayer` object 内（`rhythmAudioProcessor` 声明之后）加：

```kotlin
    /** 当前播放会话累计器（播放层单例持有，可跨 ViewModel 重建）。 */
    val sessionAccumulator = PlaySessionAccumulator()
    private var currentPlaybackSessionIdValue: String? = null
    private var currentPlaybackSessionSongIdValue: Long = 0L
```

在 `updateCurrentPlayback`（第 241-245 行）之后加：

```kotlin
    /** 媒体项切换时开启新播放会话：重置累计器并生成确定性会话 id。 */
    fun beginPlaybackSession(song: Song) {
        sessionAccumulator.beginSession()
        currentPlaybackSessionSongIdValue = song.id
        currentPlaybackSessionIdValue = "${song.id}:${sessionAccumulator.sessionStartedAt}"
    }

    fun currentPlaybackSessionId(): String? = currentPlaybackSessionIdValue

    fun currentPlaybackSessionSongId(): Long = currentPlaybackSessionSongIdValue

    fun currentPlaybackSessionStartedAt(): Long = sessionAccumulator.sessionStartedAt
```

`release()`（第 262-272 行）里加两行清理：

```kotlin
        currentPlaybackSessionIdValue = null
        currentPlaybackSessionSongIdValue = 0L
```

- [ ] **Step 2: 修改 PlayerViewModel.kt**

**(a)** import 加：

```kotlin
import com.ncm.app.data.weekly.PlayEventEntity
```

**(b)** `onMediaItemTransition`（第 159 行）把：

```kotlin
            AppPlayer.updateCurrentPlayback(prepared.song, prepared.source)
            AppPlayer.refreshPlaybackNotification(app)
```

改成：

```kotlin
            AppPlayer.updateCurrentPlayback(prepared.song, prepared.source)
            AppPlayer.beginPlaybackSession(prepared.song)
            AppPlayer.refreshPlaybackNotification(app)
```

**(c)** `setProgress`（第 328-337 行）把：

```kotlin
    fun setProgress(progress: Float) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = (duration * progress.coerceIn(0f, 1f)).toLong()
        player.seekTo(position)
```

改成：

```kotlin
    fun setProgress(progress: Float) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = (duration * progress.coerceIn(0f, 1f)).toLong()
        AppPlayer.sessionAccumulator.onSeekStarted()
        player.seekTo(position)
```

**(d)** 替换整个 `startProgressUpdates`（第 1238-1258 行）：

```kotlin
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            val accumulator = AppPlayer.sessionAccumulator
            while (player.isPlaying) {
                val rawDuration = player.duration
                val duration = rawDuration.takeIf { it > 0 } ?: 1
                val position = player.currentPosition.coerceAtLeast(0)
                accumulator.track(position, isPlaying = true)
                if (accumulator.consumeQualification(rawDuration)) {
                    recordQualifiedPlay(
                        songId = AppPlayer.currentPlaybackSessionSongId(),
                        playbackSessionId = AppPlayer.currentPlaybackSessionId(),
                        sessionStartedAt = AppPlayer.currentPlaybackSessionStartedAt()
                    )
                }
                val current = _state.value
                if (
                    kotlin.math.abs(position - current.currentPosition) >= MIN_PROGRESS_UPDATE_MS ||
                    duration != current.duration
                ) {
                    _state.value = current.copy(
                        currentPosition = position,
                        duration = duration,
                        progress = position.toFloat() / duration.toFloat()
                    )
                }
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    /** 有效播放达标后，把当前会话写入每周播放记录（去重由唯一索引保证）。 */
    private fun recordQualifiedPlay(
        songId: Long,
        playbackSessionId: String?,
        sessionStartedAt: Long
    ) {
        val userId = session.userId
        if (userId <= 0 || songId <= 0L || playbackSessionId.isNullOrBlank()) return
        viewModelScope.launch {
            NeteaseApp.instance.weeklyPlayLog.record(
                PlayEventEntity(
                    userId = userId,
                    songId = songId,
                    playbackSessionId = playbackSessionId,
                    sessionStartedAt = sessionStartedAt
                )
            )
        }
    }
```

> 注意：`consumeQualification(rawDuration)` 传入原始 `player.duration`（可能 ≤0/`C.TIME_UNSET`），这样未知时长时只按 30 秒阈值判定；不要传入 `?: 1` 的展示值。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat --no-daemon :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/ncm/app/playback/AppPlayer.kt app/src/main/java/com/ncm/app/viewmodel/PlayerViewModel.kt
git commit -m "feat(weekly): record valid plays into weekly play log from playback hooks"
```

---

### Task 11: UI —— 我的页卡片 + 每周推荐详情页 + 导航

**Files:**
- Modify: `app/src/main/java/com/ncm/app/ui/screens/my/MyScreen.kt`
- Create: `app/src/main/java/com/ncm/app/ui/screens/weekly/WeeklyRecommendationScreen.kt`
- Modify: `app/src/main/java/com/ncm/app/ui/navigation/NavGraph.kt`

**Interfaces:**
- Consumes: `weeklyRecState`/`weeklyDetailSongs`/`weeklyDetailLoading`/`cleanupWeeklyCacheOnPageOpen`/`loadWeeklyRecommendation`/`hydrateWeeklyDetailSongsNow`（Task 9）、`WeeklyRecUiState`/`WeeklyRecUiMapper`（Task 9）、`CachedSong`（Task 4）、`Song`（既有）、`playerViewModel.setQueue(songs, startIndex)` + `onOpenPlayer(id)`（既有）
- Produces:
  - `MyScreen` 新增参数 `onWeeklyClick: () -> Unit`；卡片 `WeeklyRecommendationCard(state, onClick, onRetry)`；`LaunchedEffect` 先清理再加载（loadMyData + loadWeeklyRecommendation）
  - `WeeklyRecommendationScreen(onBack, onOpenPlayer, playerViewModel, viewModel)` — TopAppBar + 四种状态 + 歌曲列表 + 点击播放（未水合则按需水合）
  - `NavGraph`：`Routes.WEEKLY = "weekly"` + composable + MyScreen 接线

- [ ] **Step 1: 修改 MyScreen.kt**

**(a)** import 加（`viewmodel.PlayerViewModel` 之后）：

```kotlin
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import com.ncm.app.domain.weekly.WeeklyRecUiMapper
import com.ncm.app.domain.weekly.WeeklyRecUiState
```

**(b)** 函数签名（第 51-58 行）加参数：

```kotlin
fun MyScreen(
    onPlaylistClick: (Long) -> Unit,
    onSongClick: (Long) -> Unit,
    onLogout: () -> Unit,
    onDisclaimerClick: () -> Unit,
    onWeeklyClick: () -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: MainViewModel = viewModel()
) {
```

**(c)** `LaunchedEffect(Unit)`（第 90-92 行）改为：

```kotlin
    LaunchedEffect(Unit) {
        viewModel.cleanupWeeklyCacheOnPageOpen()
        viewModel.loadMyData()
        viewModel.loadWeeklyRecommendation()
    }
```

**(d)** `state` 收集之后加：

```kotlin
    val weeklyRecState by viewModel.weeklyRecState.collectAsState()
```

**(e)** SettingsEntry item（第 102-110 行）之后、`when {` 之前插入卡片 item：

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

**(f)** 文件末尾（`MyPlaylistItem` 之后）加卡片 composable：

```kotlin
@Composable
private fun WeeklyRecommendationCard(
    state: WeeklyRecUiState,
    onClick: () -> Unit,
    onRetry: () -> Unit
) {
    when (state) {
        is WeeklyRecUiState.Loading -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .glassSurface(RoundedCornerShape(18.dp), elevation = 8.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Green500)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("每周推荐", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("正在根据本周听歌记录生成…", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                }
            }
        }

        is WeeklyRecUiState.Success -> {
            val cover = state.songs.firstOrNull()?.cover
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .glassSurface(RoundedCornerShape(18.dp), elevation = 8.dp)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(GlassSurface),
                    contentAlignment = Alignment.Center
                ) {
                    if (!cover.isNullOrBlank()) {
                        AsyncImage(
                            sizedImageUrl(cover, 140),
                            null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = Green500, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("每周推荐", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        WeeklyRecUiMapper.successSubtitle(state.seedCount, state.songs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        is WeeklyRecUiState.InsufficientData -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .glassSurface(RoundedCornerShape(18.dp), elevation = 8.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.MusicNote, null, tint = TextTertiary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("每周推荐", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("听歌数据不足，多听几首下周再来", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                }
            }
        }

        is WeeklyRecUiState.Error -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .glassSurface(RoundedCornerShape(18.dp), elevation = 8.dp)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Refresh, null, tint = TextTertiary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("每周推荐", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                }
                Text("重试", style = MaterialTheme.typography.labelMedium, color = Green500)
            }
        }
    }
}
```

- [ ] **Step 2: 创建 WeeklyRecommendationScreen.kt**

```kotlin
package com.ncm.app.ui.screens.weekly

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.data.model.Song
import com.ncm.app.domain.weekly.CachedSong
import com.ncm.app.domain.weekly.WeeklyRecUiMapper
import com.ncm.app.domain.weekly.WeeklyRecUiState
import com.ncm.app.ui.theme.GlassSurface
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextTertiary
import com.ncm.app.ui.theme.glassSurface
import com.ncm.app.ui.theme.miniPlayerSafeBottomPadding
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyRecommendationScreen(
    onBack: () -> Unit,
    onOpenPlayer: (Long) -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.weeklyRecState.collectAsState()
    val detailSongs by viewModel.weeklyDetailSongs.collectAsState()
    val detailLoading by viewModel.weeklyDetailLoading.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.cleanupWeeklyCacheOnPageOpen()
        viewModel.loadWeeklyRecommendation()
        // 状态落定为 Success 后再静默水合（供点击直接播放）；非 Success 则不水合。
        snapshotFlow { viewModel.weeklyRecState.value }
            .filterIsInstance<WeeklyRecUiState.Success>()
            .first()
        viewModel.hydrateWeeklyDetailSongsNow()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("每周推荐", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        when (val current = state) {
            is WeeklyRecUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Green500, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }

            is WeeklyRecUiState.InsufficientData -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        "本周听歌数据不足，多听几首下周再来",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                }
            }

            is WeeklyRecUiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(current.message, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadWeeklyRecommendation() }) {
                        Text("重试")
                    }
                }
            }

            is WeeklyRecUiState.Success -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
                ) {
                    item { WeeklyHeader(current) }
                    itemsIndexed(current.songs, key = { _, song -> song.songId }) { _, song ->
                        WeeklySongRow(
                            song = song,
                            onClick = {
                                val full = detailSongs
                                when {
                                    full.isNullOrEmpty() && detailLoading ->
                                        Toast.makeText(context, "正在加载歌曲…", Toast.LENGTH_SHORT).show()
                                    full.isNullOrEmpty() ->
                                        scope.launch {
                                            val loaded = viewModel.hydrateWeeklyDetailSongsNow()
                                            if (loaded.isNullOrEmpty()) {
                                                Toast.makeText(context, "歌曲加载失败，请重试", Toast.LENGTH_SHORT).show()
                                            } else {
                                                playWeekly(loaded, song.songId, playerViewModel, onOpenPlayer)
                                            }
                                        }
                                    else -> playWeekly(full, song.songId, playerViewModel, onOpenPlayer)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyHeader(state: WeeklyRecUiState.Success) {
    val cover = state.songs.firstOrNull()?.cover
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(GlassSurface),
            contentAlignment = Alignment.Center
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(cover, 200), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Green500, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("每周推荐 · ${state.displayWeekLabel}", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text(
                WeeklyRecUiMapper.successSubtitle(state.seedCount, state.songs.size),
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun WeeklySongRow(song: CachedSong, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .glassSurface(RoundedCornerShape(8.dp), elevation = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!song.cover.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(song.cover, 140), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Outlined.MusicNote, null, tint = TextTertiary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                song.artists.joinToString(" / "),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
    }
}

private fun playWeekly(
    songs: List<Song>,
    songId: Long,
    playerViewModel: PlayerViewModel,
    onOpenPlayer: (Long) -> Unit
) {
    val startIndex = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
    playerViewModel.setQueue(songs, startIndex)
    onOpenPlayer(songId)
}
```

> `snapshotFlow` / `filterIsInstance` / `first` 的 import 已在上方 import 块中完整列出。

- [ ] **Step 3: 修改 NavGraph.kt**

**(a)** import 加：

```kotlin
import com.ncm.app.ui.screens.weekly.WeeklyRecommendationScreen
```

**(b)** `Routes`（第 33 行 `DISCLAIMER` 之后）加：

```kotlin
    const val WEEKLY = "weekly"
```

**(c)** `MyScreen` composable（第 153-166 行）加参数：

```kotlin
        composable(Routes.MY) {
            MyScreen(
                onPlaylistClick = { id -> navController.navigate(Routes.playlistDetail(id)) },
                onSongClick = onOpenPlayer,
                onLogout = {
                    mainViewModel.logout()
                },
                onDisclaimerClick = {
                    navController.navigate(Routes.DISCLAIMER)
                },
                onWeeklyClick = {
                    navController.navigate(Routes.WEEKLY)
                },
                playerViewModel = playerViewModel,
                viewModel = mainViewModel
            )
        }
```

**(d)** `DISCLAIMER` composable（第 168-170 行）之后加：

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

- [ ] **Step 4: 编译验证**

Run: `.\gradlew.bat --no-daemon :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若 `snapshotFlow`/图标 import 报错，按提示补齐。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/ncm/app/ui/screens/my/MyScreen.kt app/src/main/java/com/ncm/app/ui/screens/weekly/WeeklyRecommendationScreen.kt app/src/main/java/com/ncm/app/ui/navigation/NavGraph.kt
git commit -m "feat(weekly): add weekly recommendation card, detail screen and navigation"
```

---

### Task 12: 全量门禁 + 真机 QA + README

**Files:**
- Modify: `README.md`
- QA: 真机验证（相似歌曲 JSON、端到端生成、退出登录清理）

**Interfaces:**
- Consumes: 全部 Task 1-11 产物
- Produces: 通过门禁的可发布代码 + 记录验证结论

- [ ] **Step 1: 全量门禁**

Run: `.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug`
Expected: BUILD SUCCESSFUL，全部测试通过，lint 无报错。

- [ ] **Step 2: 真机 QA（相似歌曲 JSON 验证）**

1. `.\gradlew.bat --no-daemon :app:installDebug` 装到真机。
2. 登录账号，播放任意歌曲超过 30 秒（触发一次有效播放写入）。
3. 进入「我的」页面等待每周推荐卡片出现「第 X 周」成功态（若数据不足则显示"听歌数据不足"）。
4. 打开详情页，确认歌曲列表可播放。
5. 用 logcat 过滤 `SimiDebug` 检查 `/api/simi/song` 真实 JSON：
   `adb logcat -s MusicRepository | grep SimiDebug`
6. 若真实响应的相似歌曲字段（`songs[].id/name/artists[].id/name`）与 `parseSimilarSongs` 不一致：
   - 调整 `MusicRepository.kt` 里的 `parseSimilarSongs` 字段名/结构；
   - 重跑 `.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.ncm.app.data.repository.MusicRepositorySimilarSongsTest"` 并同步更新测试期望；
   - 若真实响应完全不含 `artists`，则走 Plan B（已实现：候选缺艺人时用 `getSongDetails` 水合补全 `primaryArtistId`），无需改代码，记录即可。
7. 若端点路径不是 `api/simi/song`（例如需要 `/simi/song` 或带 `type` 参数），同步改 `NeteaseApi.getSimilarSongs` 注解并重跑测试。

- [ ] **Step 3: 真机 QA（退出登录清理）**

1. 登录 → 进入「我的」→ 触发每周推荐成功态。
2. 退出登录 → 确认跳转登录页、无崩溃。
3. 重新登录同一账号 → 进入「我的」→ 确认每周推荐重新生成（缓存已随退出清空）。
4. 构造清理失败场景（可选）：通过开发者选项/磁盘满模拟 Room 不可用，确认退出后 Toast 提示"部分本地数据清理失败"且不阻塞登录。

- [ ] **Step 4: 更新 README.md**

在「功能」的 `### Android 客户端 (`app/`)` 末尾加一行：

```markdown
- 每周推荐：按上一个完整自然周的播放记录生成相似歌曲推荐（我的页面入口）。
```

在「质量门禁」的测试覆盖列表末尾加：

```markdown
- 每周推荐：播放合格判定、播放记录去重与裁剪、推荐生成 single-flight、缓存命中零请求、退出登录清理顺序。
```

- [ ] **Step 5: 提交**

```bash
git add README.md
git commit -m "docs: document weekly recommendation feature and tests"
```

---

## Self-Review（写完后自查）

**1. 规格覆盖**（对照设计文档逐节）：
- §4 模型（PlayEventEntity / WeeklyRecCache / WeeklyRecCacheResult / CachedSong / WeeklyRecResult / WeeklyRecUiState）→ Task 2/4/9 ✓
- §5 存储（insert-ignore、deleteOlderThan、queryWeeklyStats、deleteOldest、countByUser、deleteAllByUser、deleteAllUsersOlderThan、insertAndPrune、pruneExpired 为 Cleaner 唯一入口）→ Task 2/7 ✓
- §6 算法（preliminaryScore、selectSeeds 8 选种 ≤2 同艺人、rankCandidates 去重/coScore/两趟艺人限制/稳定排序）→ Task 5 ✓
- §6.2 相似歌曲 Plan A/B + 真机验证 → Task 3/12 ✓
- §7 single-flight + 异常顺序 → Task 8 ✓
- §11.5 退出登录严格顺序 + ClearWeeklyDataResult → Task 7/9 ✓
- §11.7 页面打开清理（不 read）→ Task 7/9 ✓
- §11.8 Room 版本/迁移 → Task 1/2 ✓
- §12 五套测试套件 → Task 2-8 展开为十套测试文件 ✓

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤都给了完整代码；命令带预期输出。

**3. 类型一致性**：
- 端口方法签名在 Task 2/4 定义、Task 7/8 消费，逐字一致（`read(userId, startMs, endMs)`、`deleteAllByUser` 返回 Long）。
- `WeeklyRecResult.Success(songs, seedCount, displayWeekStart)` 在 Task 4 定义、Task 8 构造、Task 9 mapper 消费，一致。
- `WeeklyRecUiMapper.toUiState/displayWeekLabel/successSubtitle` 在 Task 9 定义、Task 11 使用，一致。
- `Seed(songId, score, primaryArtistId)` / `CandidateEdge(seedSongId, candidateSongId, seedScore, simRank, primaryArtistId)` 在 Task 5 定义、Task 8 构造，一致。
- `PlayEventEntity(userId, songId, playbackSessionId, sessionStartedAt, id)` 在 Task 2 定义、Task 10 构造，一致。
- `consumeQualification(durationMs: Long)` 在 Task 6 定义、Task 10 调用（传 `rawDuration`），一致。
- `logoutCoordinator.execute(userId)` 在 Task 7 定义、Task 9 调用，一致。

**已知取舍**：`MainViewModel.hydrateWeeklyDetailSongsNow` 与详情页 `snapshotFlow` 组合依赖 `weeklyRecState` 先落定为 Success；若水合失败，点击歌曲时按需重试并 Toast（Task 11 已实现）。`loadWeeklyRecommendation` 跳过已成功的状态，重登后 `logout()` 已重置为 Loading（Task 9）。

---

## 执行交接

**Plan complete and saved to `docs/superpowers/plans/2026-07-31-weekly-recommendation.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**

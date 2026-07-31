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

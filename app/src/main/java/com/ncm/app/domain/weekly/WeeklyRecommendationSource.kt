package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Song

/** 每周推荐用例的数据源。失败一律抛异常（含 CancellationException），由调用方处理。 */
interface WeeklyRecommendationSource {
    suspend fun getSimilarSongs(songId: Long): List<SimilarSong>
    suspend fun getSongDetails(ids: List<Long>): List<Song>
}

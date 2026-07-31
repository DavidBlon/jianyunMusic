package com.ncm.app.domain.weekly

import com.ncm.app.data.model.ArtistBrief

/** 相似歌曲接口返回的一首候选曲目（Plan A 直接携带艺人；Plan B 由 UseCase 补全）。 */
data class SimilarSong(
    val id: Long,
    val name: String,
    val artists: List<ArtistBrief>
)

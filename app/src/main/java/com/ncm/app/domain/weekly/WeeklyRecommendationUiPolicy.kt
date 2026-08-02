package com.ncm.app.domain.weekly

import com.ncm.app.data.model.Song

/**
 * ViewModel 使用的周推荐展示策略。
 *
 * 保持为纯函数，便于锁定跨周刷新和详情排序两个容易回归的边界。
 */
internal fun canReuseWeeklyRecommendation(
    state: WeeklyRecUiState,
    settledKey: GenerationKey?,
    requestedKey: GenerationKey
): Boolean = settledKey == requestedKey &&
    (state is WeeklyRecUiState.Success || state is WeeklyRecUiState.InsufficientData)

internal fun restoreWeeklyRecommendationOrder(
    requestedSongIds: List<Long>,
    loadedSongs: List<Song>
): List<Song> {
    val songsById = loadedSongs.associateBy { it.id }
    return requestedSongIds.mapNotNull(songsById::get)
}

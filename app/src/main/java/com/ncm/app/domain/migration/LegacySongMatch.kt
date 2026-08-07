package com.ncm.app.domain.migration

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey

/** 迁移候选（spec §12）：展示候选与匹配依据，由用户确认后建立新 ProviderTrackKey，不自动写入。 */
data class LegacySongMatch(
    val legacyKey: ProviderTrackKey,
    val candidates: List<OnlineTrack>,
    val matchBasis: String
)

/**
 * 为 legacy 网易云条目生成「迁移到当前来源」候选。只做标题级建议（含归一化），
 * 永不自动写库——同名/翻唱/现场版差异由用户判断。
 */
fun suggestMatches(
    legacy: List<OnlineTrack>,
    pluginResults: List<List<OnlineTrack>>
): List<LegacySongMatch> {
    val candidates = pluginResults.flatten()
    return legacy.mapNotNull { legacyTrack ->
        val exact = candidates.filter { it.title == legacyTrack.title }
        if (exact.isNotEmpty()) {
            LegacySongMatch(legacyTrack.key, exact, "标题完全匹配")
        } else {
            val normalized = candidates.filter { normalize(it.title) == normalize(legacyTrack.title) }
            if (normalized.isNotEmpty()) {
                LegacySongMatch(legacyTrack.key, normalized, "标题归一化匹配")
            } else {
                null
            }
        }
    }
}

private fun normalize(title: String): String =
    title.trim().lowercase().replace(Regex("\\s+"), "")

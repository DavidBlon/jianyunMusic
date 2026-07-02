package com.ncm.app.data.repository

import com.ncm.app.data.model.ArtistBrief

internal object BackupSongMatcher {

    data class Candidate(
        val fileHash: String,
        val name: String,
        val duration: Long,
        val artists: String
    )

    private data class CandidateScore(
        val candidate: Candidate,
        val total: Double,
        val nameSimilarity: Double,
        val artistSimilarity: Double,
        val durationRatio: Double
    )

    fun selectBest(
        songName: String,
        artists: List<ArtistBrief>,
        duration: Long,
        candidates: List<Candidate>
    ): Candidate? {
        if (candidates.isEmpty()) return null

        val neteaseArtists = artists
            .map { normalizeArtistName(it.name) }
            .filter { it.isNotEmpty() }

        val scored = candidates.map { candidate ->
            val charSim = charSimilarity(songName, candidate.name)
            val seqSim = sequentialSimilarity(songName, candidate.name)
            val nameSimilarity = maxOf(charSim, seqSim)
            if (nameSimilarity < 0.45) {
                return@map CandidateScore(candidate, 0.0, nameSimilarity, 0.0, 0.0)
            }

            val artistSimilarity = artistMatch(neteaseArtists, candidate.artists)
            var durationRatio = 1.0
            if (duration > 0 && candidate.duration > 0) {
                durationRatio = minOf(duration, candidate.duration).toDouble() /
                    maxOf(duration, candidate.duration)
            }

            val total = nameSimilarity * 50.0 + artistSimilarity * 35.0 + durationRatio * 15.0
            CandidateScore(candidate, total, nameSimilarity, artistSimilarity, durationRatio)
        }

        val best = scored.maxByOrNull { it.total } ?: return null

        val hasArtist = neteaseArtists.isNotEmpty()
        val hasDuration = duration > 0 && best.candidate.duration > 0
        val artistOk = !hasArtist || best.artistSimilarity >= 0.45
        val artistStrong = !hasArtist || best.artistSimilarity >= 0.75
        val durationOk = !hasDuration || best.durationRatio >= 0.78
        val durationStrong = !hasDuration || best.durationRatio >= 0.9

        val strongNameMatch = best.nameSimilarity >= 0.86 && (artistOk || durationOk)
        val balancedMatch = best.nameSimilarity >= 0.68 && artistOk && durationOk
        val metadataMatch = best.nameSimilarity >= 0.52 && artistStrong && durationStrong
        if (!strongNameMatch && !balancedMatch && !metadataMatch) return null

        val origName = normalize(songName)
        val bestName = normalize(best.candidate.name)
        if (origName.isNotEmpty() && bestName.isNotEmpty()) {
            val hasOverlap = origName.any { c -> bestName.contains(c) }
                || bestName.any { c -> origName.contains(c) }
            if (!hasOverlap) return null
        }

        return best.candidate
    }

    fun normalize(str: String): String {
        return str.lowercase()
            .replace(Regex("[（(][^）)]*[）)]"), "")
            .replace(Regex("\\s*(live|伴奏|铃声|完整版|纯音乐|instrumental|cover|remix|版|ver\\.?)\\s*"), " ")
            .replace(Regex("[\\s　]+"), " ")
            .replace(Regex("[，。！？、；：\"\"''【】《》\\-–—·～&@#\$%^*+=\\\\|<>?/]+"), " ")
            .trim()
    }

    private fun charSimilarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (na.contains(nb) || nb.contains(na)) return 0.9

        val setA = na.replace(" ", "").toSet()
        val setB = nb.replace(" ", "").toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0

        val inter = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        if (union == 0.0) return 0.0
        return inter / union
    }

    private fun sequentialSimilarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (na.contains(nb) || nb.contains(na)) return 0.85

        val sa = na.replace(" ", "")
        val sb = nb.replace(" ", "")
        if (sa.isEmpty() || sb.isEmpty()) return 0.0

        val lcs = longestCommonSubsequence(sa, sb)
        val maxLen = maxOf(sa.length, sb.length)
        return if (maxLen == 0) 0.0 else lcs.toDouble() / maxLen
    }

    private fun longestCommonSubsequence(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0 || n == 0) return 0
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    private fun artistMatch(neteaseArtists: List<String>, candidateArtistStr: String): Double {
        if (neteaseArtists.isEmpty()) return 1.0

        val candidateArtists = candidateArtistStr.split(Regex("[、,，/&]"))
            .map { normalizeArtistName(it) }
            .filter { it.isNotEmpty() && it.length >= 2 }

        if (candidateArtists.isEmpty()) return 0.0

        val matchCount = neteaseArtists.map { neteaseArtist ->
            val name = neteaseArtist.lowercase().trim()
            if (name.isEmpty()) return@map false
            candidateArtists.any { candidate -> candidate == name }
        }.count { it }

        return matchCount.toDouble() / maxOf(neteaseArtists.size, 1)
    }

    private fun normalizeArtistName(name: String): String {
        return name.lowercase()
            .replace(Regex("[（(][^）)]*[）)]"), "")
            .trim()
    }
}

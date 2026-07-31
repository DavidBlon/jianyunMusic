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

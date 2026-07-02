package com.ncm.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositoryPolicyTest {

    @Test
    fun mergeCookiePairs_overwritesDuplicateNamesAndKeepsStableOrder() {
        val merged = RepositoryPolicy.mergeCookiePairs(
            existing = "MUSIC_A=old; MUSIC_U=old-user; empty",
            incoming = "MUSIC_U=new-user; __csrf=token"
        )

        assertEquals("MUSIC_A=old; MUSIC_U=new-user; __csrf=token", merged)
    }

    @Test
    fun backupQualityFor_mapsStandardTo128kAndHigherTo320k() {
        assertEquals("128k", RepositoryPolicy.backupQualityFor(128000))
        assertEquals("320k", RepositoryPolicy.backupQualityFor(192000))
        assertEquals("320k", RepositoryPolicy.backupQualityFor(999000))
    }

    @Test
    fun playbackUnavailableMessage_prefersPlaybackAndSpecificUserFacingReasons() {
        assertNull(
            RepositoryPolicy.playbackUnavailableMessage(
                hasPlayableUrl = true,
                serverMessage = "ignored",
                hasFreeTrial = false,
                code = 404,
                fee = 1,
                loggedIn = false
            )
        )

        assertEquals(
            "这首歌当前只能试听，暂不支持播放完整版。",
            RepositoryPolicy.playbackUnavailableMessage(
                hasPlayableUrl = false,
                serverMessage = "server says no",
                hasFreeTrial = true,
                code = 200,
                fee = 0,
                loggedIn = true
            )
        )

        assertEquals(
            "当前未登录，部分会员或版权歌曲无法播放。",
            RepositoryPolicy.playbackUnavailableMessage(
                hasPlayableUrl = false,
                serverMessage = null,
                hasFreeTrial = false,
                code = 500,
                fee = 0,
                loggedIn = false
            )
        )
    }
}

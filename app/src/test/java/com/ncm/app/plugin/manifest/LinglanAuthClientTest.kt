package com.ncm.app.plugin.manifest

import com.ncm.app.plugin.auth.LinglanAuthState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LinglanAuthClientTest {

    @Test
    fun validKeyMapsToActive() = runTest {
        val client = LinglanAuthClient(http = { _, _ -> """{"code":200,"expireAt":9999999999999}""" })
        val result = client.validate("valid-key-123")
        assertEquals(LinglanAuthState.ACTIVE, result.state)
    }

    @Test
    fun expiredKeyMapsToExpired() = runTest {
        val client = LinglanAuthClient(http = { _, _ -> """{"code":401,"message":"key expired"}""" })
        assertEquals(LinglanAuthState.EXPIRED, client.validate("bad").state)
    }

    @Test
    fun revokedKeyMapsToRevoked() = runTest {
        val client = LinglanAuthClient(http = { _, _ -> """{"code":403,"message":"revoked"}""" })
        assertEquals(LinglanAuthState.REVOKED, client.validate("bad").state)
    }

    @Test
    fun networkFailureMapsToErrorNotInvalid() = runTest {
        val client = LinglanAuthClient(http = { _, _ -> throw java.io.IOException("no network") })
        assertEquals(LinglanAuthState.ERROR, client.validate("key").state)
    }

    @Test
    fun malformedBodyMapsToErrorNotInvalid() = runTest {
        val client = LinglanAuthClient(http = { _, _ -> "not-json-at-all" })
        assertEquals(LinglanAuthState.ERROR, client.validate("key").state)
    }

    @Test
    fun scriptUpdateResponseCannotAuthorizeAnArbitraryKey() = runTest {
        val client = LinglanAuthClient(
            http = { _, _ -> """{"code":200,"currentkey":"script-version","data":null}""" }
        )

        assertEquals(LinglanAuthState.ERROR, client.validate("arbitrary-long-text").state)
    }

    @Test
    fun missingSongParametersAfterAuthenticationMapsToActive() = runTest {
        val client = LinglanAuthClient(
            http = { _, _ -> """{"code":400,"message":"缺少必要参数: source, songId, quality"}""" }
        )

        assertEquals(LinglanAuthState.ACTIVE, client.validate("valid-key-123").state)
    }

    @Test
    fun platformScopeErrorOnProbeStillMeansKeyIsValid() = runTest {
        // 服务端先校验卡密、再校验平台/音质权限；探测未带歌曲时可能返回范围 403。
        // 不存在的卡密返回 401，因此该 403 说明卡密本身有效。
        val client = LinglanAuthClient(
            http = { _, _ -> """{"code":403,"message":"当前卡密不支持该平台或音质"}""" }
        )

        assertEquals(LinglanAuthState.ACTIVE, client.validate("valid-key-123").state)
    }

    @Test
    fun requestUrlNeverExposesCredential() {
        val client = LinglanAuthClient(
            endpoint = "https://example.test/music/url",
            http = { _, _ -> "{\"code\":200}" }
        )
        assertEquals(
            "https://example.test/music/url?source=kg&quality=128k",
            client.requestUrl()
        )
    }

}

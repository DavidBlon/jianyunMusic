package com.ncm.app.plugin.runtime

import java.io.File
import java.security.MessageDigest

data class CachedScript(
    val pluginId: String,
    val version: String,
    val sha256: String,
    val script: String
)

/**
 * 脚本缓存：键 = 用户授权身份的不可逆摘要 + 插件 ID + 版本（GC #10）。
 * 每插件最多保留当前版 + 一个未撤销上一版。不使用原始密钥作路径/文件名。
 * 约定：pluginId 与 version 均不得包含 '_'（由测试锁定）。
 */
class PluginScriptCache(
    private val dir: File,
    private val identityDigest: String
) {
    fun cacheKeyFor(pluginId: String, version: String): String =
        "${identityDigest}_${pluginId}_$version"

    fun loadActive(pluginId: String): CachedScript? = listFiles(pluginId)
        .filter { it.name.endsWith(SUFFIX_ACTIVE) }
        .maxByOrNull { it.lastModified() }
        ?.let(::readScript)

    fun stageCandidate(pluginId: String, version: String, script: String): CachedScript {
        val key = cacheKeyFor(pluginId, version)
        val file = File(dir, "$key$SUFFIX_CANDIDATE")
        file.writeText(script)
        return CachedScript(pluginId, version, sha256(script), script)
    }

    fun activateCandidate(pluginId: String, version: String): CachedScript? {
        val candidate = listFiles(pluginId).firstOrNull { it.name.endsWith(SUFFIX_CANDIDATE) }
            ?: return null
        val active = readScript(candidate)
        val key = cacheKeyFor(pluginId, version)
        File(dir, "$key$SUFFIX_ACTIVE").writeText(active.script)
        candidate.delete()
        return active
    }

    fun deleteAll(pluginId: String) {
        listFiles(pluginId).forEach { it.delete() }
    }

    private fun listFiles(pluginId: String): List<File> =
        dir.listFiles { f -> f.name.startsWith("${identityDigest}_${pluginId}_") }.orEmpty().toList()

    private fun readScript(file: File): CachedScript {
        val script = file.readText()
        val parts = file.name.split("_", limit = 3)
        val pluginId = parts.getOrNull(1).orEmpty()
        val version = parts.getOrNull(2)
            ?.removeSuffix(SUFFIX_ACTIVE)
            ?.removeSuffix(SUFFIX_CANDIDATE)
            .orEmpty()
        return CachedScript(
            pluginId = pluginId,
            version = version,
            sha256 = sha256(script),
            script = script
        )
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SUFFIX_ACTIVE = ".active"
        const val SUFFIX_CANDIDATE = ".candidate"
    }
}

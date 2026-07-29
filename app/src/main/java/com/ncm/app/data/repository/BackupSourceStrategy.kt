package com.ncm.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

/**
 * Runs trusted ID-based providers before lower-confidence search providers.
 * A search tier is not started until every exact provider has failed, so a
 * successful paid source does not trigger unnecessary third-party traffic.
 */
internal object BackupSourceStrategy {

    suspend fun <T> resolve(
        exactProviders: List<suspend () -> T?>,
        searchProviders: List<suspend () -> T?>
    ): T? {
        return resolveTier(exactProviders) ?: resolveTier(searchProviders)
    }

    private suspend fun <T> resolveTier(
        providers: List<suspend () -> T?>
    ): T? = supervisorScope {
        val jobs = providers.map { provider -> async { runProvider(provider) } }
        try {
            firstSuccessful(jobs.toMutableList())
        } finally {
            jobs.forEach { job ->
                if (job.isActive) job.cancel()
            }
        }
    }

    private suspend fun <T> firstSuccessful(jobs: MutableList<Deferred<T?>>): T? {
        while (jobs.isNotEmpty()) {
            val (completedJob, result) = select<Pair<Deferred<T?>, T?>> {
                jobs.forEach { job ->
                    job.onAwait { value -> job to value }
                }
            }
            jobs.remove(completedJob)
            if (result != null) return result
        }
        return null
    }

    private suspend fun <T> runProvider(provider: suspend () -> T?): T? {
        return try {
            provider()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}

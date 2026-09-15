package com.arkiv.player.data.credentials

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the encrypted credentials blob and calls the five native resolvers to get back
 * complete, usable values. Used both by the explicit "Activar" tap and by
 * [com.arkiv.player.data.update.UpdateWorker]'s silent periodic refresh.
 *
 * The five resolver functions are constructor parameters (defaulting to the real native ones) so
 * this class is testable with MockWebServer on the JVM, the same shape
 * [com.arkiv.player.data.update.UpdateChecker] already uses for its `client`: the real native
 * library isn't available on the JVM test runner.
 */
class CredentialsActivator(
    private val client: OkHttpClient,
    private val blobUrl: String = DEFAULT_BLOB_URL,
    private val resolveIptv3desKey: (ByteArray) -> String = NativeCredentialResolver::resolveIptv3desKey,
    private val resolveIptvHosts: (ByteArray) -> String = NativeCredentialResolver::resolveIptvHosts,
    private val resolveIptvAppId: (ByteArray) -> String = NativeCredentialResolver::resolveIptvAppId,
    private val resolveIptvApkVersion: (ByteArray) -> String = NativeCredentialResolver::resolveIptvApkVersion,
    private val resolveTmdbApiKey: (ByteArray) -> String = NativeCredentialResolver::resolveTmdbApiKey,
) {
    companion object {
        const val DEFAULT_BLOB_URL = "https://github.com/lordmacu/kino-light/releases/latest/download/credentials.enc"
    }

    /**
     * Null on any failure: no network, a bad download, a decrypt/combine failure, or the native
     * anti-instrumentation check tripping -- all indistinguishable on purpose (see the spec's
     * Error Handling section: telling an attacker exactly which defense caught them only helps
     * them route around it next time).
     */
    suspend fun activate(): RemoteCredentials? = withContext(Dispatchers.IO) {
        runCatching {
            val blob = client.newCall(
                Request.Builder().url(blobUrl).cacheControl(CacheControl.FORCE_NETWORK).build(),
            ).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return@withContext null

            val key = resolveIptv3desKey(blob)
            val hosts = resolveIptvHosts(blob)
            val appId = resolveIptvAppId(blob)
            val apkVersion = resolveIptvApkVersion(blob)
            val tmdbKey = resolveTmdbApiKey(blob)
            if (key.isEmpty() || hosts.isEmpty() || appId.isEmpty() || apkVersion.isEmpty() || tmdbKey.isEmpty()) {
                return@withContext null
            }
            RemoteCredentials(key, hosts, appId, apkVersion, tmdbKey)
        }.getOrNull()
    }
}

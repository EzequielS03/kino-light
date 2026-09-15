package com.arkiv.player.data.credentials

/** The five third-party credentials this app needs, once downloaded, decrypted and recombined. */
data class RemoteCredentials(
    val iptv3desKey: String,
    val iptvHosts: String,
    val iptvAppId: String,
    val iptvApkVersion: String,
    val tmdbApiKey: String,
)

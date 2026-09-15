package com.arkiv.player.data.credentials

/**
 * JNI bridge into the native module (`app/src/main/cpp`, gitignored -- see
 * docs/superpowers/specs/2026-09-15-split-credential-activation-design.md, "Native module source
 * stays out of the public repo"). Declaring these five function names here is public; what each
 * one actually does (AES-GCM decrypt, de-obfuscate, interleave-combine, refuse under
 * instrumentation) is not.
 */
internal object NativeCredentialResolver {
    init { System.loadLibrary("credentials") }

    /**
     * Each function takes the raw bytes of the downloaded `credentials.enc` and returns that
     * one field's fully combined value, or an empty string if decryption or the
     * anti-instrumentation check fails.
     */
    external fun resolveIptv3desKey(blob: ByteArray): String
    external fun resolveIptvHosts(blob: ByteArray): String
    external fun resolveIptvAppId(blob: ByteArray): String
    external fun resolveIptvApkVersion(blob: ByteArray): String
    external fun resolveTmdbApiKey(blob: ByteArray): String
}

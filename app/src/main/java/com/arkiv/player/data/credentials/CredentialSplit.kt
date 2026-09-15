package com.arkiv.player.data.credentials

/**
 * Splits a credential's characters by position into two halves -- one to ship inside the
 * downloaded encrypted blob, one to bake into the native library at build time -- so neither half
 * alone is a complete, usable value. See
 * docs/superpowers/specs/2026-09-15-split-credential-activation-design.md, "Data flow: the
 * interleave split/combine".
 *
 * `app/build.gradle.kts`'s `generateNativeSecretsHeader` task duplicates [split]'s rule at build
 * time (a plain Gradle script here can't import this class), and the native module
 * (`app/src/main/cpp`, gitignored) re-implements [combine] in C++. If this rule ever changes,
 * update all three.
 */
object CredentialSplit {
    /** Even indices (0, 2, 4, ...) go to the file half; odd indices go to the native half. */
    fun split(value: String): Pair<String, String> {
        val fileHalf = StringBuilder()
        val nativeHalf = StringBuilder()
        for (i in value.indices) {
            if (i % 2 == 0) fileHalf.append(value[i]) else nativeHalf.append(value[i])
        }
        return fileHalf.toString() to nativeHalf.toString()
    }

    /**
     * Reassembles [split]'s output. `fileHalf` must be exactly as long as `nativeHalf`, or exactly
     * one character longer (when the original value's length was odd).
     */
    fun combine(fileHalf: String, nativeHalf: String): String {
        require(fileHalf.length == nativeHalf.length || fileHalf.length == nativeHalf.length + 1) {
            "fileHalf (${fileHalf.length}) and nativeHalf (${nativeHalf.length}) lengths don't match a valid split"
        }
        val result = StringBuilder()
        for (i in fileHalf.indices) {
            result.append(fileHalf[i])
            if (i < nativeHalf.length) result.append(nativeHalf[i])
        }
        return result.toString()
    }
}

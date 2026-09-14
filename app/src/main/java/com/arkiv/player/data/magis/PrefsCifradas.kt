package com.arkiv.player.data.magis

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Open encrypted prefs WITHOUT an undecryptable file leaving the app unable to start.
 *
 * `EncryptedSharedPreferences` encrypts the file with a key from the Android Keystore, and that
 * key NEVER leaves the device. If the file survives but the key doesn't —restoring the backup on
 * a new phone, a Keystore that got reset— every `create` throws `AEADBadTagException` and there's
 * no going back: nobody will ever decrypt that file again.
 *
 * Since the store gets touched in `ArkivApp.onCreate`, that wasn't "some data got lost": it was
 * the app opening and closing itself in a loop, with no way out short of wiping its data by hand.
 *
 * The decision here is explicit: **between losing the session and not being able to open the app,
 * the session is lost**. What can't be decrypted gets thrown away and started fresh; the person
 * logs back in.
 *
 * Generic in `T` so it can be tested on the JVM: this logic doesn't care what gets opened.
 */
internal object EncryptedPrefs {
    fun <T> openOrRepair(
        create: () -> T,
        discardUndecryptable: () -> Unit,
        unencrypted: () -> T,
    ): T {
        try {
            return create()
        } catch (e: Throwable) {
            // An error that isn't encryption-related is our own bug, and our own bug can't cost
            // anyone their session: it propagates as-is, without deleting anything.
            if (!isEncryptionBroken(e)) throw e
        }
        discardUndecryptable()
        return try {
            create()
        } catch (e: Throwable) {
            if (!isEncryptionBroken(e)) throw e
            // Doesn't open even freshly thrown out: the Keystore itself is broken. Plain prefs
            // beat a phone where the app won't start -- it's the app's own private storage.
            unencrypted()
        }
    }

    /**
     * Tink sometimes wraps the Keystore's failure, so looking only at the top one isn't enough.
     * The hop limit is in case some cause chain bites its own tail.
     */
    private fun isEncryptionBroken(t: Throwable): Boolean {
        var cause: Throwable? = t
        var hops = 0
        while (cause != null && hops < 16) {
            if (cause is GeneralSecurityException || cause is IOException) return true
            cause = cause.cause
            hops++
        }
        return false
    }
}

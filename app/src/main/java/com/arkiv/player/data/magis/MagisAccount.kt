package com.arkiv.player.data.magis

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Message ready to show: distinguishes "the password is wrong" from "Magis isn't answering". */
class MagisException(message: String) : RuntimeException(message)

sealed interface MagisAccountState {
    data object None : MagisAccountState
    data class Linked(val email: String) : MagisAccountState
}

/**
 * The link with Magis as seen from the UI. Replaces the only thing that was still alive from
 * `AccountManager` in the three screens that consumed it (`AccountSection`, `TvSettingsCuenta`,
 * `TvOfertaVincularMagis`): holding whether there's an account and notifying when it changes.
 * `AccountManager` -along with `PantallaDeEntrada`/`TvPantallaDeEntrada`, which used it for Kino's
 * login- was deleted entirely in Task 9 (sub-project 2B).
 */
internal class MagisAccount(private val session: MagisSession) {
    private val _state = MutableStateFlow<MagisAccountState>(MagisAccountState.None)
    val state: StateFlow<MagisAccountState> = _state.asStateFlow()

    /**
     * Called once on entering the screen, NOT in the constructor: reading the email comes from
     * `EncryptedSharedPreferences` (disk + decryption) and this object is built from `AppGraph`,
     * which gets touched from the main thread. On the KALLEY that's milliseconds that are noticeable.
     *
     * `runCatching` on purpose: it's called by three `LaunchedEffect`s (including `ArkivTvRoot`'s at
     * startup), and an exception reading disk can't sink the app there. With it, it degrades to
     * [MagisAccountState.None] as if there were no linked account.
     *
     * Covers the READ, not opening the store: `EncryptedMagisCredentialStore` gets built earlier,
     * when evaluating `graph.magisAccount` (`AppGraph.magisStore`, `by lazy`), and the one
     * protecting that is `EncryptedPrefs.openOrRepair`, which does handle a broken Keystore
     * without throwing.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val email = runCatching { session.linkedEmail() }
            .onFailure { Log.w(TAG, "couldn't read the linked account: assuming not linked", it) }
            .getOrNull()
        _state.value = email?.let { MagisAccountState.Linked(it) } ?: MagisAccountState.None
    }

    suspend fun link(email: String, password: String) = withContext(Dispatchers.IO) {
        when (val r = session.login(email, password)) {
            is MagisResult.Ok -> _state.value = MagisAccountState.Linked(email)
            is MagisResult.RedError -> throw MagisException("Magis no disponible")
            // The portal says WHY, but in Chinese: we show ours and theirs (code +
            // message) goes to the log -- without the code, a "credenciales inválidas" that's
            // actually "aaa100082: this device is already bound to another account" is undiagnosable.
            is MagisResult.PortalError -> {
                Log.w(TAG, "link rejected by the portal: code=${r.code}, message=${r.msg}")
                throw MagisException("Credenciales de Magis inválidas")
            }
        }
    }

    suspend fun unlink() = withContext(Dispatchers.IO) {
        session.logout()
        _state.value = MagisAccountState.None
    }

    private companion object {
        const val TAG = "MagisAccount"
    }
}

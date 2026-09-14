package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.magis.MagisAccount
import com.arkiv.player.data.magis.MagisAccountState
import com.arkiv.player.data.magis.MagisException
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/**
 * Decides whether to offer linking Magis right on entering the TV (Task 10; since Task 8,
 * sub-project 2B, it no longer looks at any Kino session, only [MagisAccountState]).
 *
 * Kept separate from the Composable on purpose -same criterion `entrarDesdeTv` used in the
 * already-removed `TvPantallaDeEntrada.kt`-: this project has no Compose UI test infrastructure,
 * so the only way to test the condition ("does the screen show up or not?") is for it to live in
 * a plain, separate function.
 *
 * Only offered when:
 * - this device does NOT have Magis linked yet ([MagisAccountState.None]) -doesn't depend on
 *   whether a Kino account is connected or not: `MainActivity` composes `ArkivTvRoot` with no
 *   session gate (see its "Sin gate de sesión" comment), so that condition no longer applies-;
 * - the person hasn't said "Not now" before on this device ([dismissed], persisted in
 *   `SettingsStore.magisOfertaDescartada` -no longer resets itself, see its KDoc-).
 */
fun shouldOfferMagisLink(state: MagisAccountState, dismissed: Boolean): Boolean =
    state is MagisAccountState.None && !dismissed

/** Which field receives the on-screen keyboard's keys in [TvMagisLinkOffer]. */
private enum class MagisOfferField { EMAIL, PASSWORD }

/**
 * Offered as the FIRST thing on entering the TV when this device doesn't have Magis linked yet
 * (Task 10, see [shouldOfferMagisLink] for the exact condition -since Task 8, sub-project 2B, it
 * no longer depends on any Kino account-).
 *
 * ### Why it lives here and not in a login screen
 *
 * `MainActivity` composes `ArkivTvRoot` with no Kino session gate (see its "Sin gate de sesión"
 * comment): `TvPantallaDeEntrada`, which used to offer this same link from the login, was removed
 * entirely in Task 9 (sub-project 2B). This offer gets composed as the first thing INSIDE
 * `ArkivTvRoot` because that's the only place both routes that leave a device without Magis
 * linked pass through -one freshly installed, one that got unlinked-, regardless of whether a
 * Kino account is involved.
 *
 * ### Why a new Magis account can no longer be created from here
 *
 * Task 11 had added creating a new account in two steps (email → code by email → password). The
 * verification code was sent by Magis over email and the gateway orchestrated the round trip
 * (`registro_enviar_codigo`/`registro_confirmar`): with no server of our own there's nobody to
 * mint the temporary device or store state between the two steps, so account creation was given
 * up (sub-project 2A). Here an ALREADY existing account gets linked, which is what this screen
 * did from the start.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvMagisLinkOffer(account: MagisAccount, onNotNow: () -> Unit) {
    // BACK EXITS THIS SCREEN, not the app. `ArkivTvRoot` composes this offer and `return`s before
    // reaching its own BackHandler, so while it's shown none was set and the system took over
    // back: it closed Kino entirely. For whoever didn't want to link Magis, the only way to
    // continue was to leave the app and come back in.
    //
    // Does the same as "Not now" on purpose: they're the same intent -- "not this, right now"--
    // and having the button and the remote do different things would be worse than either one.
    BackHandler(onBack = onNotNow)
    val scope = rememberCoroutineScope()
    val fields = rememberTvFocusedFields(MagisOfferField.EMAIL)
    // No longer preloaded with any email: it used to come from the connected Kino account, and
    // since Task 8 (sub-project 2B) this screen depends on none. Starts blank.
    var passwordVisible by remember { mutableStateOf(false) }
    var keyboardMode by remember { mutableStateOf(TvKeyboardMode.LOWER) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun link() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                account.link(fields.value(MagisOfferField.EMAIL).trim(), fields.value(MagisOfferField.PASSWORD))
                // No need to "close" anything here: as soon as linking leaves the state as
                // Linked, shouldOfferMagisLink returns false and the caller (ArkivTvRoot) stops
                // composing this screen on its own, through account.state's normal recomposition.
            } catch (e: MagisException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val canLink = !busy &&
        fields.value(MagisOfferField.EMAIL).isNotBlank() &&
        fields.value(MagisOfferField.PASSWORD).isNotBlank()

    TvKeyboardAndFields(
        title = "Vincular tu cuenta de Magis",
        subtitle = "Tiene que ser una cuenta que ya exista. Da acceso a tu plan de Magis desde " +
            "Kino, y hace falta para el canal en vivo. \"Ahora no\" para saltear -queda en Ajustes.",
        keyboardMode = keyboardMode,
        onMode = { keyboardMode = it },
        activeText = fields.activeValue(),
        onActiveTextChange = { fields.writeActive(it); error = null },
        // To keep `@`/`.` visible without switching layers while typing the email.
        extras = if (fields.active == MagisOfferField.EMAIL) listOf('@', '.') else emptyList(),
    ) { firstFieldFocus ->
        TvFieldChip(
            label = "Email de Magis",
            value = fields.value(MagisOfferField.EMAIL),
            active = fields.active == MagisOfferField.EMAIL,
            onFocus = { fields.focus(MagisOfferField.EMAIL) },
            modifier = Modifier.focusRequester(firstFieldFocus),
        )

        TvFieldChip(
            label = "Contraseña de Magis",
            value = if (passwordVisible) {
                fields.value(MagisOfferField.PASSWORD)
            } else {
                "•".repeat(fields.value(MagisOfferField.PASSWORD).length)
            },
            active = fields.active == MagisOfferField.PASSWORD,
            // Masked ONLY while hidden: `PasswordVisualTransformation` masks the drawing, not the
            // accessibility semantics.
            masked = !passwordVisible,
            onFocus = { fields.focus(MagisOfferField.PASSWORD) },
        )
        TvPasswordVisibilityButton(visible = passwordVisible, onToggle = { passwordVisible = !passwordVisible })

        error?.let {
            Text(
                it,
                color = ArkivRed,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // FlowRow and not Row: in a `Row` that overflows, Compose CLIPS the last child -- and the
        // last one is exactly "Ahora no", the screen's exit. With the TV at 960 dp this column
        // ends up ~382 dp and the linking branch's three buttons add up to ~378: right at the
        // edge. With the code's step there are FOUR and it overflows for sure, so the only exit
        // ended up off-screen and Magis looked mandatory. It had already happened before in this
        // same column: see the comment on `FIELDS_WEIGHT` in TvKeyboardAndFields.kt, where a
        // `width(520.dp)` "took the create-account button with it". Wrapping, the width can no
        // longer hide an exit.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            TvOfferAction(if (busy) "Vinculando…" else "Vincular", enabled = canLink, onClick = ::link)
            // "Ahora no" doesn't depend on `busy`: if Magis is taking a while to respond, the
            // person still has to be able to leave -it's not a destructive button, so there's no
            // risk of leaving something half-done-.
            TvOfferAction("Ahora no", enabled = true, onClick = onNotNow)
        }
    }
}

/** This screen's action button (Vincular / Ahora no), both with the same look. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvOfferAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
}

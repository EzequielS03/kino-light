package com.arkiv.player.data.magis

import org.json.JSONObject
import java.util.Base64

/**
 * What to play, as a string the app stores in its local database (`torrentData`). Until Task 5
 * that column traveled between devices through cloud sync; without that sync, this string stays
 * only on the device that saved it.
 *
 * Replaces the `ref` the gateway used to mint (`base64url(json).hmac`, **with a 24h expiry**):
 * with no server there's nobody to ask for a new one, and there's no need to either: all the
 * portal needs to resolve is the `contentId`, which never expires. A local ref also fixes
 * something that was broken along the way — an item saved in the library more than a day ago
 * carried a dead ref.
 *
 * [programType] is what the portal calls the content ("movie", "teleplay", "variety"…), not
 * TMDB's "movie"/"tv": it's what decides whether chapters need to be listed before playing.
 */
internal data class MagisRef(
    val contentId: String,
    val programType: String = "movie",
    val episode: Int = 0,
) {
    val isSeries: Boolean get() = programType in SERIES

    /** `magis1:<type>:<episode>:<contentId>` — the contentId goes last so it doesn't matter if it
     *  ever carries a `:` inside. */
    fun encode(): String = "$PREFIX:$programType:$episode:$contentId"

    internal companion object {
        const val PREFIX = "magis1"

        /** The types the portal serves by chapters. A single definition: the same one the screens
         *  already consume (`MAGIS_SERIES`). */
        val SERIES = com.arkiv.player.data.gateway.MAGIS_SERIES

        /**
         * Reads a ref of its own, or an old one from the gateway. `null` if it's not Magis's or
         * can't be understood.
         *
         * The gateway's ref was opaque to the app **by contract**, not by cryptography: it's
         * `base64url(json).hmac`, and the json is read without the key. That's what lets the
         * already-saved library get migrated instead of asking the person to add it again. The
         * signature isn't validated (there's nothing to validate it with, and it doesn't matter
         * either: what comes out of here doesn't authorize anything, it only says which title to
         * ask the portal for) and the expiry is deliberately ignored.
         */
        fun decode(ref: String): MagisRef? {
            if (ref.isBlank()) return null
            if (ref.startsWith("$PREFIX:")) {
                val parts = ref.split(":", limit = 4)
                if (parts.size < 4) return null
                val contentId = parts[3].takeIf { it.isNotBlank() } ?: return null
                return MagisRef(
                    contentId = contentId,
                    programType = parts[1].ifBlank { "movie" },
                    episode = parts[2].toIntOrNull() ?: 0,
                )
            }
            return fromGatewayRef(ref)
        }

        private fun fromGatewayRef(ref: String): MagisRef? {
            val data = ref.substringBefore('.').takeIf { it.isNotBlank() && it != ref } ?: return null
            val json = runCatching {
                // `java.util.Base64` and not `android.util.Base64`: Android's is a stub that
                // returns null in JVM tests, so the migration path couldn't be tested (and this is
                // exactly the path that only ever runs once, silently).
                JSONObject(String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8))
            }.getOrNull() ?: return null
            if (json.optString("s") != "magis") return null
            val p = json.optJSONObject("p") ?: return null
            val contentId = p.optString("content_id").takeIf { it.isNotBlank() } ?: return null
            return MagisRef(
                contentId = contentId,
                programType = p.optString("program_type").ifBlank { "movie" },
                episode = p.optInt("episode", 0),
            )
        }
    }
}

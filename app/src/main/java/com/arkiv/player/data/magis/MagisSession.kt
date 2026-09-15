package com.arkiv.player.data.magis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Magis portal's session as seen from the device. Port of `IPTVClient.activate` /
 * `new_anonymous_device` / `login` (`vendor/iptv_client.py`) plus the recovery logic `arkiv-api`
 * learned in production (`adapters/magis/session.py`).
 *
 * Two modes, same as in the gateway:
 *  - **anonymous**: the device mints ITS OWN device (`v3/snToken` → `sn` → `v8/active`) and with
 *    that there's already catalog and VOD. Nobody needs to have an account.
 *  - **account**: `v8/login` with email+password. Needed for the live channel.
 *
 * What can never be done is making up an `sn`: the portal answers `snToken已经失效`. And two
 * identities over the SAME `sn` mutually kick each other out on every activation (the ping-pong
 * `session.py` documents), so the minted `sn` gets saved and reused forever.
 */
internal class MagisSession(
    private val portal: MagisPortalClientLike,
    private val store: MagisCredentialStore,
) {

    /** Activating/logging in are "read-modify-write" over the store: two coroutines at once
     *  would mint two devices and one would kick the other out. */
    private val lock = Mutex()

    val userId: String get() = store.readSession()?.userId.orEmpty()
    val userToken: String get() = store.readSession()?.userToken.orEmpty()

    /** This device's minted device. `MagisPortalClient` reads it on every call. */
    val sn: String get() = store.readSession()?.sn.orEmpty()

    val hasAccountLinked: Boolean get() = store.readAccount() != null

    /**
     * A device minted for a pending registration, held ENTIRELY in memory by whoever calls
     * [sendRegistrationCode] -- there's nothing to persist across a process restart, since
     * abandoning the flow mid-way just means minting a fresh device next time. Never written to
     * [store] until [confirmRegistration] succeeds, so a failed or abandoned registration can
     * never corrupt this device's real session.
     */
    data class PendingRegistration(
        val userId: String,
        val userToken: String,
        val sn: String,
    )

    /** Only the email: the saved password is for relogging on its own, not for showing or passing around. */
    fun linkedEmail(): String? = store.readAccount()?.first

    /**
     * Leaves the device with the BEST session it can have: the linked account's if there is one,
     * and otherwise the anonymous one. No-op if there's already a token.
     *
     * The difference with [ensureAnonymous] matters for the live channel: the portal rejects it
     * with an anonymous session, so a device with a linked account that fell back to anonymous
     * would end up with no live and no explanation. It's the same precedence order the gateway
     * uses in `client()`.
     */
    suspend fun ensureSession(): MagisResult<Unit> = lock.withLock {
        if (!store.readSession()?.userToken.isNullOrBlank()) return@withLock MagisResult.Ok(Unit)
        val account = store.readAccount()
        if (account != null) {
            val r = loginWithoutLock(account.first, account.second)
            // Rejected credential or portal down: anonymous is served before serving nothing at
            // all. Whoever needs a real account (live) checks it with `hasAccountLinked`.
            if (r is MagisResult.Ok) return@withLock r
        }
        ensureAnonymousWithoutLock()
    }

    /** Leaves the device with a usable session without asking anyone for an account. No-op if there's already a token. */
    suspend fun ensureAnonymous(): MagisResult<Unit> = lock.withLock { ensureAnonymousWithoutLock() }

    suspend fun login(email: String, password: String): MagisResult<Unit> =
        lock.withLock { loginWithoutLock(email, password) }

    /**
     * First step of creating a brand-new Magis account (no gateway needed -- port of the old
     * `arkiv-api`'s `registro_enviar_codigo`, `adapters/magis/session.py`): mints a FRESH, separate
     * device (never touches this device's stored session/`sn`) and asks Magis to email it a
     * verification code. The returned [PendingRegistration] must be held by the caller (in-memory
     * UI state) and passed to [confirmRegistration].
     */
    suspend fun sendRegistrationCode(email: String): MagisResult<PendingRegistration> = lock.withLock {
        val snR = portal.call("v3/snToken", hardwareFingerprint(), baseFields = false)
        val snJ = snR.getOrNull() ?: return@withLock snR.asError()
        val snToken = snJ.optString("snToken").takeIf { it.isNotBlank() }
            ?: return@withLock MagisResult.PortalError("snToken_failed", "el portal no devolvió snToken")
        val sn = (snJ.optString("sn").takeIf { it.isNotBlank() } ?: md5Hex(snToken + SNTOKEN_SALT)).lowercase()

        val activateBean = mapOf(
            "snToken" to snToken, "authVersion" to "", "authCode" to "", "preCode" to "",
            "macAddr" to FIXED_MAC, "reserve1" to "", "openNum" to 4, "channel" to "default",
            "matadata" to "", "signdata" to "",
        )
        val actR = portal.call("v8/active", activateBean, baseFields = false, sn = sn)
        val actJ = actR.getOrNull() ?: return@withLock actR.asError()
        val userId = actJ.optString("userId")
        val userToken = actJ.optString("userToken")
        if (userToken.isBlank()) {
            return@withLock MagisResult.PortalError("active_sin_token", "activación sin userToken")
        }
        val pending = PendingRegistration(userId, userToken, sn)

        val codeR = portal.call(
            "v2/sendEmailVerifyCode",
            mapOf("email" to email, "type" to "1", "userId" to userId, "userToken" to userToken),
            baseFields = false,
            sn = sn,
        )
        if (codeR !is MagisResult.Ok) return@withLock codeR.asError()
        MagisResult.Ok(pending)
    }

    /**
     * Second step (port of `registro_confirmar`): validates the code, binds email+password to the
     * PENDING device, logs in with the new credentials, and only on full success replaces this
     * device's stored session and account with the new ones. A failure at any step leaves the
     * current session (if any) completely untouched.
     */
    suspend fun confirmRegistration(
        pending: PendingRegistration,
        email: String,
        password: String,
        code: String,
    ): MagisResult<Unit> = lock.withLock {
        val validateR = portal.call(
            "v2/validateVerifyCode",
            mapOf(
                "type" to "1", "email" to email, "verifyCode" to code,
                "userToken" to pending.userToken, "userId" to pending.userId,
            ),
            baseFields = false,
            sn = pending.sn,
        )
        if (validateR !is MagisResult.Ok) return@withLock validateR.asError()

        val bindR = portal.call(
            "v2/bindEmail",
            mapOf(
                "email" to email, "pwd" to md5Hex(password + PASSWORD_SALT), "type" to "1",
                "userId" to pending.userId, "userToken" to pending.userToken,
            ),
            baseFields = false,
            sn = pending.sn,
        )
        if (bindR !is MagisResult.Ok) return@withLock bindR.asError()

        val loginBean = mapOf(
            "accountType" to "2", "userName" to email,
            "password" to md5Hex(password + PASSWORD_SALT), "type" to "1",
            "macAddr" to FIXED_MAC, "areaCode" to "", "verificationCode" to "", "verificationToken" to "",
            "matadata" to "", "signdata" to "", "channel" to "default",
        )
        val loginR = portal.call("v8/login", loginBean, baseFields = false, sn = pending.sn)
        val loginJ = loginR.getOrNull() ?: return@withLock loginR.asError()
        if (loginJ.optString("userToken").isBlank()) {
            return@withLock MagisResult.PortalError("login_sin_token", "login sin userToken")
        }

        store.saveSession(
            StoredSession(
                userId = loginJ.optString("userId"),
                userToken = loginJ.optString("userToken"),
                jwtToken = loginJ.optString("jwtToken"),
                sn = pending.sn,
            ),
        )
        store.saveAccount(email, password)
        MagisResult.Ok(Unit)
    }

    /**
     * Unlinks the account: logs out of the portal, deletes the credentials and goes back to
     * anonymous with the SAME device (the `sn` belongs to the device, not the account).
     */
    suspend fun logout(): MagisResult<Unit> = lock.withLock {
        val previous = store.readSession()
        if (!previous?.userToken.isNullOrBlank()) {
            portal.call(
                path = "v5/loginOut",
                bean = mapOf("userId" to previous!!.userId, "userToken" to previous.userToken),
                baseFields = false,
            )
        }
        store.clearAccount()
        forgetToken()
        ensureAnonymousWithoutLock()
    }

    /**
     * Runs [block] and, if the portal rejects it, reauthenticates and retries it ONCE.
     *
     * Reauthenticates on **any** `PortalError`, not just the known codes for an expired session
     * (`aaa100027`/`aaa100028`). It's what the gateway learned the hard way
     * (`adapter.py:_llamar`): the portal also kills the session when another device logs in with
     * the same device, and it announces that with a message localized in Chinese whose code isn't
     * documented. Discriminating finely leaves the saved session dead and EVERY call that follows
     * failing forever; the cost of getting it wrong the other way is one extra call.
     *
     * A [MagisResult.RedError] does NOT reauthenticate: the portal didn't say anything, it's down.
     */
    suspend fun <T> withValidSession(block: suspend () -> MagisResult<T>): MagisResult<T> {
        val first = block()
        if (first !is MagisResult.PortalError) return first
        val reauth = reauthenticate()
        if (reauth !is MagisResult.Ok) return first
        return block()
    }

    // --- inside the lock -------------------------------------------------

    private suspend fun ensureAnonymousWithoutLock(): MagisResult<Unit> {
        val previous = store.readSession()
        if (!previous?.userToken.isNullOrBlank()) return MagisResult.Ok(Unit)

        val storedSn = previous?.sn.orEmpty()
        if (storedSn.isNotBlank()) {
            val r = activate(snToken = "")
            if (r is MagisResult.Ok) return r
            // Only these two codes mean "that device no longer works": anything else (network,
            // portal down) does NOT authorize minting another one -- minting extras wastes devices.
            if (!(r is MagisResult.PortalError && r.code in INVALID_SN)) return r
        }
        return mintDevice()
    }

    /**
     * `v3/snToken` with a hardware fingerprint → `sn = md5(snToken + salt)` → `v8/active`.
     * The fingerprint is randomized on every minting: the portal hands out one device per
     * fingerprint, and two devices with the same fingerprint would be the same device.
     */
    private suspend fun mintDevice(): MagisResult<Unit> {
        val r = portal.call("v3/snToken", hardwareFingerprint(), baseFields = false)
        val j = r.getOrNull() ?: return r.map { }
        val snToken = j.optString("snToken").takeIf { it.isNotBlank() }
            ?: return MagisResult.PortalError("snToken_failed", "el portal no devolvió snToken")
        val sn = (j.optString("sn").takeIf { it.isNotBlank() } ?: md5Hex(snToken + SNTOKEN_SALT))
            .lowercase()
        // The `sn` is saved BEFORE activating because it's what the device dict has to carry in
        // that same call (`MagisPortalClient` reads it from the store).
        store.saveSession(StoredSession(userId = "", userToken = "", jwtToken = "", sn = sn))
        return activate(snToken)
    }

    /** `v8/active`: with an empty [snToken] it reactivates the device that's already saved. */
    private suspend fun activate(snToken: String): MagisResult<Unit> {
        val bean = mapOf(
            "snToken" to snToken,
            "authVersion" to "",
            "authCode" to "",
            "preCode" to "",
            "macAddr" to FIXED_MAC,
            "reserve1" to "",
            "openNum" to 4,
            "channel" to "default",
            // Only STBs with /system/etc/.UCERT carry these.
            "matadata" to "",
            "signdata" to "",
        )
        val r = portal.call("v8/active", bean, baseFields = false)
        val j = r.getOrNull() ?: return r.map { }
        if (j.optString("userToken").isBlank()) {
            return MagisResult.PortalError("active_sin_token", "activación sin userToken")
        }
        saveFromResponse(j)
        return MagisResult.Ok(Unit)
    }

    private suspend fun loginWithoutLock(email: String, password: String): MagisResult<Unit> {
        val bean = mapOf(
            "accountType" to "2",
            "userName" to email,
            // MD5(password + "cloudstream") in lowercase hex. The salt comes from `AbstractC5729e`
            // in the decompiled app; without it the portal rejects CORRECT credentials.
            "password" to md5Hex(password + PASSWORD_SALT),
            "type" to "1",
            "macAddr" to FIXED_MAC,
            "areaCode" to "",
            "verificationCode" to "",
            "verificationToken" to "",
            "matadata" to "",
            "signdata" to "",
            "channel" to "default",
        )
        // Logging in does NOT activate the device: it would waste an activation per `sn` for no reason.
        val r = portal.call("v8/login", bean, baseFields = false)
        val j = r.getOrNull() ?: return r.map { }
        if (j.optString("userToken").isBlank()) {
            return MagisResult.PortalError("login_sin_token", "login sin userToken")
        }
        saveFromResponse(j)
        store.saveAccount(email, password)
        return MagisResult.Ok(Unit)
    }

    private suspend fun reauthenticate(): MagisResult<Unit> = lock.withLock {
        val account = store.readAccount()
        // What's saved no longer works; if it isn't cleared, `ensureAnonymousWithoutLock` would take it as good.
        forgetToken()
        if (account != null) loginWithoutLock(account.first, account.second)
        else ensureAnonymousWithoutLock()
    }

    /** Drops the token but keeps the device: minting again would waste a device on every session expiry. */
    private fun forgetToken() {
        val previous = store.readSession() ?: return
        store.saveSession(previous.copy(userId = "", userToken = "", jwtToken = ""))
    }

    private fun saveFromResponse(j: JSONObject) {
        store.saveSession(
            StoredSession(
                userId = j.optString("userId"),
                userToken = j.optString("userToken"),
                jwtToken = j.optString("jwtToken"),
                // The device belongs to the DEVICE: neither login nor reactivation change it.
                sn = store.readSession()?.sn.orEmpty(),
            ),
        )
    }

    /**
     * The fingerprint `v3/snToken` expects (`SnTokenBean` in the decompiled app). The original app
     * sends the phone's real data; here go the emulator's, the one the protocol was captured with,
     * with the identifying fields randomized so each device mints ITS OWN device.
     */
    private fun hardwareFingerprint(): Map<String, Any?> = mapOf(
        "androidId" to randomHex(8),
        "board" to "goldfish_arm64",
        "brand" to "google",
        "cpuAbi" to "arm64-v8a",
        "cpuId" to randomHex(8),
        "device" to "emu64a",
        "diskInfo" to "8GB",
        "display" to "sdk_gphone64_arm64",
        "etheMac" to randomMac(),
        "fingerprint" to
            "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:user/release-keys",
        "gatewayMac" to randomMac(),
        "hardware" to "ranchu",
        "host" to "abfarm",
        "manufacturer" to "Google",
        "ramSize" to "4GB",
        "romSize" to "8GB",
        "serialNumber" to randomHex(8),
        "tags" to "release-keys",
        "verId" to "",
        "wifiMac" to randomMac(),
    )

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun randomMac(): String =
        ByteArray(6).also { random.nextBytes(it) }.joinToString(":") { "%02x".format(it) }

    private fun md5Hex(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val random = SecureRandom()

        const val SNTOKEN_SALT = "ntFT65w6itH!lHCPw7D=@qnsFC5adD28"
        const val PASSWORD_SALT = "cloudstream"

        /** The MAC the original app sends, fixed and fake. */
        const val FIXED_MAC = "02:00:00:00:00:00"

        /**
         * "This `sn` doesn't work to activate anonymously" → another device has to be minted.
         *  - `aaa100080`: invalid snToken/sn.
         *  - `aaa100082`: that device ended up bound to an account with a password and only
         *    accepts login. Without this code, the whole anonymous branch stays dead and the
         *    portal answers "请求参数异常！" to every call, which doesn't point here by any stretch
         *    (seen in production on 2026-08-12).
         */
        val INVALID_SN = setOf("aaa100080", "aaa100082")
    }
}

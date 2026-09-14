package com.arkiv.player.data.magis

import org.json.JSONObject

/** Queue of responses per endpoint -- each call to that `path` consumes the next one from its queue. */
internal class FakePortalClient : MagisPortalClientLike {
    val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

    /** The session (userId, userToken) each call traveled with, in the same order as
     *  [calls] -- to assert that a retry uses the NEW token, not the one that just died. */
    val sessions = mutableListOf<Pair<String, String>>()
    private val queuesByPath = mutableMapOf<String, ArrayDeque<MagisResult<JSONObject>>>()
    var defaultResponse: MagisResult<JSONObject> = MagisResult.Ok(JSONObject())

    fun queueResponse(path: String, result: MagisResult<JSONObject>) {
        queuesByPath.getOrPut(path) { ArrayDeque() }.addLast(result)
    }

    /** How many times that endpoint was called (to assert there was ONE retry, not two). */
    fun timesCalled(path: String): Int = calls.count { it.first == path }

    override suspend fun call(
        path: String,
        bean: Map<String, Any?>,
        baseFields: Boolean,
        userId: String,
        userToken: String,
    ): MagisResult<JSONObject> {
        calls.add(path to bean)
        sessions.add(userId to userToken)
        val queue = queuesByPath[path]
        return if (queue != null && queue.isNotEmpty()) queue.removeFirst() else defaultResponse
    }
}

internal class FakeCredentialStore : MagisCredentialStore {
    private var session: StoredSession? = null
    private var account: Pair<String, String>? = null

    override fun saveSession(s: StoredSession) { session = s }
    override fun readSession(): StoredSession? = session
    override fun saveAccount(email: String, password: String) { account = email to password }
    override fun readAccount(): Pair<String, String>? = account
    override fun clearAccount() { account = null }
}

/** Session with no linked account, already "activated" (userToken present) -- for tests that don't
 * need to exercise the activation flow itself. */
internal fun testSession(fake: FakePortalClient = FakePortalClient()): MagisSession {
    val store = FakeCredentialStore()
    store.saveSession(StoredSession(userId = "u-test", userToken = "t-test", jwtToken = "", sn = "sn-test"))
    return MagisSession(fake, store)
}

/** Same as [testSession] but with a linked account (for what requires an account, e.g. live). */
internal fun testSessionWithAccount(fake: FakePortalClient = FakePortalClient()): MagisSession {
    val store = FakeCredentialStore()
    store.saveSession(StoredSession(userId = "u-cuenta", userToken = "t-cuenta", jwtToken = "", sn = "sn-cuenta"))
    store.saveAccount("persona@ejemplo.com", "MiClaveMagis123")
    return MagisSession(fake, store)
}

/** Activated session but with NO linked account -- to test the "live requires an account" guard. */
internal fun testSessionWithoutAccount(fake: FakePortalClient = FakePortalClient()): MagisSession =
    testSession(fake)

/** Shortcut to build portal responses in tests. */
internal fun portalOk(vararg fields: Pair<String, Any?>): MagisResult<JSONObject> =
    MagisResult.Ok(JSONObject(fields.toMap()))

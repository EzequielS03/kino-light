package com.arkiv.player.data.magis

import org.json.JSONObject

/**
 * Magis portal catalog: search, detail and a root's columns. Returns the portal's raw JSON —
 * translating it to the models the UI already consumes is up to whoever wires it in.
 *
 * All three endpoints go with `baseFields` (the portal requires `portalCode` + the session in the
 * body), and all of them go through [MagisSession.withValidSession]: the `userToken` dies with no
 * warning and the retry has to travel with the new one, not the one that just died (that's why
 * it's read INSIDE the block).
 *
 * `v3/getColumnContents` isn't here on purpose: the gateway never used it for anything the app
 * shows (`getNextColumns`'s sections already come with their first items in `assetList`), and in
 * this branch unused code doesn't get written.
 */
internal class MagisCatalog(
    private val portal: MagisPortalClientLike,
    private val session: MagisSession,
) {

    /** [type]: `"1"` movie, `"0"` series. */
    suspend fun detail(contentId: String, type: String): MagisResult<JSONObject> = request(
        "v4/getItemData",
        mapOf(
            "contentId" to contentId,
            "type" to type,
            "sortType" to "0",
            "language" to "en",
            "macAddr" to "02:00:00:00:00:00",
        ),
    )

    suspend fun search(query: String, page: Int = 1, pageSize: Int = 20): MagisResult<JSONObject> =
        request(
            "v3/searchByName",
            mapOf(
                "value" to query,
                "type" to "0",
                "columnId" to "",
                "filter" to "",
                "pageNum" to page,
                "pageSize" to pageSize,
            ),
        )

    /**
     * A root's columns (`masnew_live`, `masnew_series`, `masnew_adult`…), each with its first items
     * in `assetList`. `masnew_vod`/`masnew_movie`/`masnew_home`/`masnew` are rejected by the portal
     * — measured on 2026-08-14, don't retry them.
     *
     * [pageSize] matters: the portal cuts off at the requested size without saying there's more
     * (with 30 it returned 30 live categories out of the 38 that exist).
     */
    suspend fun nextColumns(
        columnCode: String,
        page: Int = 1,
        pageSize: Int = 50,
    ): MagisResult<JSONObject> = request(
        "getNextColumns",
        mapOf(
            "columnCode" to columnCode,
            "pageNum" to page,
            "pageSize" to pageSize,
            "version" to "",
        ),
    )

    private suspend fun request(path: String, bean: Map<String, Any?>): MagisResult<JSONObject> {
        // With no token the portal answers with an undiagnosable error ("请求参数异常！"), so
        // there has to be a session first — with a valid token this doesn't touch the network.
        val session0 = session.ensureSession()
        if (session0 !is MagisResult.Ok) return session0.asError()
        return session.withValidSession {
            portal.call(
                path = path,
                bean = bean,
                baseFields = true,
                userId = session.userId,
                userToken = session.userToken,
            )
        }
    }
}

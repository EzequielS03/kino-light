package com.arkiv.player.data.magis

import org.json.JSONObject

/**
 * Catálogo del portal de Magis: búsqueda, detalle y las columnas de una raíz. Devuelve el JSON
 * crudo del portal — traducirlo a los modelos que ya consume la UI es cosa de quien lo cablea.
 *
 * Los tres endpoints van con `baseFields` (el portal exige `portalCode` + la sesión en el body), y
 * todos pasan por [MagisSession.conSesionValida]: el `userToken` se muere sin avisar y el reintento
 * tiene que viajar con el nuevo, no con el que acabó de morir (por eso se lee DENTRO del bloque).
 *
 * No está `v3/getColumnContents` a propósito: el gateway nunca lo usó para nada que la app muestre
 * (las secciones de `getNextColumns` ya vienen con sus primeros items en `assetList`), y en esta
 * rama el código que no se usa no se escribe.
 */
internal class MagisCatalog(
    private val portal: MagisPortalClientLike,
    private val session: MagisSession,
) {

    /** [tipo]: `"1"` película, `"0"` serie. */
    suspend fun detail(contentId: String, tipo: String): MagisResult<JSONObject> = pedir(
        "v4/getItemData",
        mapOf(
            "contentId" to contentId,
            "type" to tipo,
            "sortType" to "0",
            "language" to "en",
            "macAddr" to "02:00:00:00:00:00",
        ),
    )

    suspend fun search(query: String, pagina: Int = 1, tamano: Int = 20): MagisResult<JSONObject> =
        pedir(
            "v3/searchByName",
            mapOf(
                "value" to query,
                "type" to "0",
                "columnId" to "",
                "filter" to "",
                "pageNum" to pagina,
                "pageSize" to tamano,
            ),
        )

    /**
     * Las columnas de una raíz (`masnew_live`, `masnew_series`, `masnew_adult`…), cada una con sus
     * primeros items en `assetList`. `masnew_vod`/`masnew_movie`/`masnew_home`/`masnew` los rechaza
     * el portal — medido el 2026-08-14, no volver a intentarlos.
     *
     * [tamano] importa: el portal corta en el tamaño pedido sin decir que hay más (con 30 devolvía
     * 30 categorías de vivo de las 38 que existen).
     */
    suspend fun nextColumns(
        columnCode: String,
        pagina: Int = 1,
        tamano: Int = 50,
    ): MagisResult<JSONObject> = pedir(
        "getNextColumns",
        mapOf(
            "columnCode" to columnCode,
            "pageNum" to pagina,
            "pageSize" to tamano,
            "version" to "",
        ),
    )

    private suspend fun pedir(path: String, bean: Map<String, Any?>): MagisResult<JSONObject> {
        // Sin token el portal contesta un error que no se puede diagnosticar ("请求参数异常！"),
        // así que primero hay que tener sesión — con token vigente esto no toca la red.
        val sesion = session.ensureAnonymous()
        if (sesion !is MagisResult.Ok) return sesion.comoError()
        return session.conSesionValida {
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

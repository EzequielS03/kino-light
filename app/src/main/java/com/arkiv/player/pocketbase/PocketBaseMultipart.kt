package com.arkiv.player.pocketbase

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Arma el cuerpo `multipart/form-data` con el que se sube el JPEG de un frame.
 *
 * Vive aparte de [PocketBaseClient] para poder testear la construcción del cuerpo sin servidor:
 * los errores de esta parte (un campo que no viaja, un content-type mal puesto) son silenciosos y
 * solo se ven como un 400 del servidor en runtime.
 */
object PocketBaseMultipart {

    private val jpeg = "image/jpeg".toMediaType()

    fun build(
        fields: Map<String, Any?>,
        campoArchivo: String,
        nombre: String,
        bytes: ByteArray,
    ): MultipartBody {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        // Null como cadena vacía: PocketBase interpreta la AUSENCIA del campo como "no lo toques",
        // no como "vacialo", y acá siempre se manda la fila entera.
        fields.forEach { (k, v) -> b.addFormDataPart(k, v?.toString() ?: "") }
        b.addFormDataPart(campoArchivo, nombre, bytes.toRequestBody(jpeg))
        return b.build()
    }
}

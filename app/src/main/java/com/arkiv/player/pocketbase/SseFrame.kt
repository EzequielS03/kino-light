package com.arkiv.player.pocketbase

/** Un frame SSE ya ensamblado. */
data class SseFrame(val id: String?, val event: String?, val data: String)

/**
 * Parser incremental de SSE: se le alimenta línea por línea (sin el salto).
 * Devuelve un SseFrame cuando llega la línea en blanco que cierra el frame.
 */
class SseFrameParser {
    private var id: String? = null
    private var event: String? = null
    private val data = StringBuilder()
    private var hasContent = false

    fun feed(line: String): SseFrame? {
        if (line.isEmpty()) {
            if (!hasContent) return null
            val frame = SseFrame(id, event, data.toString())
            reset()
            return frame
        }
        hasContent = true
        val idx = line.indexOf(':')
        val field = if (idx >= 0) line.substring(0, idx) else line
        var value = if (idx >= 0) line.substring(idx + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "id" -> id = value
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    private fun reset() {
        id = null
        event = null
        data.setLength(0)
        hasContent = false
    }
}

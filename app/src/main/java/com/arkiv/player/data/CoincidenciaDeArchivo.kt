package com.arkiv.player.data

import java.text.Normalizer

/**
 * Reencontrar un archivo dentro de un ítem de archive.org cuando el nombre que teníamos guardado
 * ya no existe.
 *
 * Hace falta porque la app **cachea el nombre del archivo** en la base local: el path sale de
 * `/metadata/` cuando se importó el ítem y queda guardado en `episodes` (ver [MetadataParser], que
 * deriva hasta el id del episodio del nombre). Mientras archive no toque nada, perfecto. Pero si
 * allá renombran —o vuelven a derivar y el mkv pasa a servirse como mp4— la app sigue pidiendo el
 * nombre viejo y se come un 404 para siempre, sin forma de recuperarse sola.
 *
 * Con la metadata fresca en la mano, esto decide cuál de los archivos que hay AHORA es el que
 * estábamos buscando.
 *
 * **La regla que manda es rendirse.** Un match equivocado no falla: reproduce otro capítulo sin
 * decir nada, y el usuario cree que la app está rota de una manera mucho más confusa que un error.
 * Así que solo se acepta una coincidencia cuando es *inequívoca* — si hay dos candidatos igual de
 * buenos, o el parecido es vago, devuelve null y que el error se vea. Es la misma disciplina que ya
 * tiene [MetadataParser.episodeNumberOf] ("ante la duda devuelve null").
 */
object CoincidenciaDeArchivo {

    /**
     * El archivo de [candidatos] que se corresponde con [pathViejo], o null si no hay uno claro.
     *
     * Se prueban criterios de más a menos evidencia, y cada uno solo vale si deja UN único
     * candidato en pie.
     */
    fun mejor(pathViejo: String, candidatos: List<String>): String? {
        if (candidatos.isEmpty()) return null

        // 1. Está tal cual: no hubo renombre (o ya lo arreglamos antes).
        candidatos.firstOrNull { it == pathViejo }?.let { return it }

        // 2. Mismo path, otra extensión: archive volvió a derivar (mkv → mp4). El nombre es el
        //    mismo, así que la evidencia es tan fuerte como una igualdad.
        unico(candidatos) { sinExtension(it) == sinExtension(pathViejo) }?.let { return it }

        // 3. Mismo nombre una vez normalizado: el renombre fue cosmético (guiones bajos por
        //    espacios, puntuación, acentos, mayúsculas). Es de lejos el caso más común.
        unico(candidatos) { normalizar(it) == normalizar(pathViejo) }?.let { return it }

        // 4. Igual pero mirando solo el nombre del archivo: además lo movieron de carpeta.
        unico(candidatos) { normalizar(base(it)) == normalizar(base(pathViejo)) }?.let { return it }

        // 5. Último recurso: el número de capítulo, y solo si AMBOS lados lo declaran de forma
        //    explícita (S01E02 / 1x02). Un número suelto en el nombre no alcanza — "Get Backers 19"
        //    y "Get Backers 1" se parecen demasiado como para apostar.
        val numeroViejo = MetadataParser.episodeNumberOf(pathViejo) ?: return null
        return unico(candidatos) { MetadataParser.episodeNumberOf(it) == numeroViejo }
    }

    /** El único elemento que cumple, o null si no hay ninguno o hay más de uno. */
    private inline fun unico(candidatos: List<String>, criterio: (String) -> Boolean): String? =
        candidatos.filter(criterio).singleOrNull()

    private fun base(path: String): String = path.substringAfterLast('/')

    private fun sinExtension(path: String): String {
        val nombre = base(path)
        val punto = nombre.lastIndexOf('.')
        val recortado = if (punto > 0) nombre.substring(0, punto) else nombre
        val carpeta = path.dropLast(nombre.length)
        return carpeta + recortado
    }

    /**
     * Deja el nombre en su esqueleto comparable: sin extensión, sin acentos, en minúsculas y sin
     * nada que no sea letra o número. Así "Oh, Mi Amigo... Kazuki vs. Juubei" y
     * "Oh Mi Amigo Kazuki vs Juubei" son el mismo archivo, que es lo que un humano diría.
     *
     * Los dígitos SÍ se conservan: son justo lo que distingue el capítulo 19 del 9.
     */
    private fun normalizar(path: String): String =
        Normalizer.normalize(sinExtension(path), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
}

package com.stravart.core.shape

import java.util.Locale

/**
 * Sérialise une forme en texte, pour qu'un dessin fait à la main survive à la
 * fermeture de l'application.
 *
 * Le format est délibérément trivial — un préfixe indiquant si le trait est fermé,
 * puis les points — parce qu'il n'a qu'un seul lecteur et qu'une dépendance de
 * sérialisation serait disproportionnée. Les coordonnées sont normalisées dans
 * [-0,5 ; 0,5] : cinq décimales représentent un dix-millième de la forme.
 */
object ShapeCodec {

    private const val CLOSED_PREFIX = "c:"
    private const val OPEN_PREFIX = "o:"

    fun encode(shape: ShapePath): String = buildString {
        append(if (shape.closed) CLOSED_PREFIX else OPEN_PREFIX)
        shape.points.forEachIndexed { index, point ->
            if (index > 0) append(';')
            append(String.format(Locale.ROOT, "%.5f,%.5f", point.x, point.y))
        }
    }

    /** @return la forme décodée, ou `null` si le texte est absent ou inexploitable. */
    fun decode(text: String?): ShapePath? {
        if (text.isNullOrBlank()) return null
        val closed = when {
            text.startsWith(CLOSED_PREFIX) -> true
            text.startsWith(OPEN_PREFIX) -> false
            else -> return null
        }
        val points = text.drop(CLOSED_PREFIX.length).split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val y = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            Pt(x, y)
        }
        return runCatching { ShapePath.of(points, closed) }.getOrNull()
    }
}

package com.stravart.core.image

/**
 * Une image réduite à ce qui nous intéresse : la luminosité de chaque pixel, et sa
 * transparence quand il y en a une.
 *
 * Le module `core` ignore Android, et c'est délibéré : la détection de contour est
 * de l'arithmétique sur des tableaux, donc vérifiable en quelques millisecondes sur
 * la machine de développement plutôt que sur un téléphone.
 */
class Raster(
    val width: Int,
    val height: Int,
    /** Luminosité de 0 (noir) à 255 (blanc), ligne par ligne. */
    val luminance: IntArray,
    /** Opacité de 0 à 255, ou `null` si l'image n'a pas de couche alpha. */
    val alpha: IntArray? = null,
) {
    init {
        require(width > 0 && height > 0) { "image vide" }
        require(luminance.size == width * height) { "luminance incohérente avec les dimensions" }
        require(alpha == null || alpha.size == width * height) { "alpha incohérent avec les dimensions" }
    }

    val size: Int get() = width * height

    fun index(x: Int, y: Int) = y * width + x

    /** Proportion de pixels franchement transparents. */
    val transparency: Double by lazy {
        val channel = alpha ?: return@lazy 0.0
        channel.count { it < OPAQUE_ENOUGH }.toDouble() / size
    }

    private companion object {
        const val OPAQUE_ENOUGH = 128
    }
}

/** Image binaire : `true` là où se trouve le sujet. */
class Mask(val width: Int, val height: Int, val inside: BooleanArray) {
    init {
        require(inside.size == width * height) { "masque incohérent avec les dimensions" }
    }

    fun at(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && inside[y * width + x]

    val coverage: Double get() = inside.count { it }.toDouble() / inside.size
}

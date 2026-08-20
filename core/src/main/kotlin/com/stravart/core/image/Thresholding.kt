package com.stravart.core.image

/**
 * Sépare le sujet du fond.
 *
 * Deux cas se présentent. Une image détourée porte sa réponse dans sa couche alpha :
 * le sujet, c'est ce qui est opaque, et rien ne vaut cette information. Sinon il faut
 * deviner, et la méthode d'Otsu le fait bien : elle cherche le seuil de luminosité
 * qui sépare le mieux les pixels en deux groupes homogènes.
 *
 * Reste à savoir lequel des deux est le sujet. On regarde le pourtour de l'image :
 * ce qui touche le bord est presque toujours le fond.
 */
object Thresholding {

    /** En deçà de cette part de pixels transparents, la couche alpha n'apprend rien. */
    private const val MEANINGFUL_TRANSPARENCY = 0.02

    private const val OPAQUE_ENOUGH = 128

    data class Outcome(val mask: Mask, val threshold: Int, val usedAlpha: Boolean, val inverted: Boolean)

    /**
     * @param sensitivity décalage du seuil, de -1 (ne retenir que le plus sombre) à
     *   +1 (retenir large). 0 laisse la méthode d'Otsu décider seule.
     * @param invert `null` pour laisser le pourtour trancher, sinon impose le sens.
     */
    fun apply(raster: Raster, sensitivity: Double = 0.0, invert: Boolean? = null): Outcome {
        val alpha = raster.alpha
        if (alpha != null && raster.transparency >= MEANINGFUL_TRANSPARENCY) {
            val inside = BooleanArray(raster.size) { alpha[it] >= OPAQUE_ENOUGH }
            val mask = Mask(raster.width, raster.height, inside)
            val flip = invert ?: false
            return Outcome(if (flip) mask.inverted() else mask, OPAQUE_ENOUGH, usedAlpha = true, inverted = flip)
        }

        val base = otsu(raster.luminance)
        val threshold = (base + sensitivity * MAX_SHIFT).toInt().coerceIn(1, 254)
        // Par défaut le sujet est le plus sombre : un dessin, un logo, une silhouette
        // se détachent sur un fond clair.
        // Comparaison large : la méthode d'Otsu range le niveau du seuil lui-même
        // dans la classe sombre. Avec un test strict, une image à deux niveaux voyait
        // son sujet tomber pile sur le seuil et disparaître.
        val inside = BooleanArray(raster.size) { raster.luminance[it] <= threshold }
        var mask = Mask(raster.width, raster.height, inside)

        val flip = invert ?: (borderCoverage(mask) > 0.5)
        if (flip) mask = mask.inverted()
        return Outcome(mask, threshold, usedAlpha = false, inverted = flip)
    }

    /** Seuil maximisant la variance inter-classes (Otsu). */
    fun otsu(luminance: IntArray): Int {
        val histogram = IntArray(256)
        for (value in luminance) histogram[value.coerceIn(0, 255)]++

        val total = luminance.size.toDouble()
        var sum = 0.0
        for (level in 0..255) sum += level * histogram[level]

        var backgroundWeight = 0.0
        var backgroundSum = 0.0
        var best = 0
        var bestVariance = -1.0

        for (level in 0..255) {
            backgroundWeight += histogram[level]
            if (backgroundWeight == 0.0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight <= 0.0) break

            backgroundSum += level * histogram[level]
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (sum - backgroundSum) / foregroundWeight
            val between = backgroundWeight * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)

            if (between > bestVariance) {
                bestVariance = between
                best = level
            }
        }
        return best
    }

    /** Part du pourtour de l'image classée comme sujet. */
    private fun borderCoverage(mask: Mask): Double {
        var counted = 0
        var inside = 0
        for (x in 0 until mask.width) {
            for (y in intArrayOf(0, mask.height - 1)) {
                counted++
                if (mask.at(x, y)) inside++
            }
        }
        for (y in 0 until mask.height) {
            for (x in intArrayOf(0, mask.width - 1)) {
                counted++
                if (mask.at(x, y)) inside++
            }
        }
        return if (counted == 0) 0.0 else inside.toDouble() / counted
    }

    private fun Mask.inverted() = Mask(width, height, BooleanArray(inside.size) { !inside[it] })

    private const val MAX_SHIFT = 60.0
}

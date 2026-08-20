package com.stravart.core.image

import com.stravart.core.shape.Pt
import com.stravart.core.shape.ShapePath
import kotlin.math.hypot

/** Ce qu'on a tiré d'une image, et comment on s'y est pris. */
data class ExtractedShape(
    val path: ShapePath,
    /** Contour en coordonnées pixel, pour le superposer à l'image dans l'aperçu. */
    val contour: List<Pt>,
    val threshold: Int,
    val usedAlpha: Boolean,
    val inverted: Boolean,
    /** Part de l'image occupée par le sujet, entre 0 et 1. */
    val coverage: Double,
)

class ImageShapeException(message: String) : Exception(message)

/**
 * Transforme une image en forme de parcours.
 *
 * La chaîne est courte : séparer le sujet du fond, longer le bord de la plus grande
 * tache, retirer l'escalier de pixels, et livrer un [ShapePath] identique à ceux du
 * catalogue. Le reste de l'application — projection, points de passage, routage — ne
 * sait pas d'où vient la forme et n'a pas à le savoir.
 */
object ImageShapeExtractor {

    /** Nombre de points du tracé rendu, aligné sur celui des formes intégrées. */
    const val OUTPUT_POINTS = 240

    /** En deçà, le sujet est trop petit pour porter un dessin lisible. */
    private const val MIN_COVERAGE = 0.005

    /** Au-delà, c'est le fond qui a été pris pour le sujet. */
    private const val MAX_COVERAGE = 0.95

    /** Tolérance de simplification, en proportion de la diagonale de l'image. */
    private const val SIMPLIFY_RATIO = 0.0035

    /**
     * @param sensitivity de -1 (ne retenir que le plus contrasté) à +1 (retenir large).
     * @param invert `null` pour laisser le pourtour de l'image trancher.
     * @throws ImageShapeException si aucune forme exploitable ne s'en dégage.
     */
    fun extract(raster: Raster, sensitivity: Double = 0.0, invert: Boolean? = null): ExtractedShape {
        val outcome = Thresholding.apply(raster, sensitivity, invert)
        val coverage = outcome.mask.coverage

        if (coverage < MIN_COVERAGE) {
            throw ImageShapeException(
                "Aucune forme nette ne se détache de cette image. Essayez une silhouette " +
                    "bien contrastée, ou ajustez la sensibilité.",
            )
        }
        if (coverage > MAX_COVERAGE) {
            throw ImageShapeException(
                "L'image est presque entièrement retenue comme sujet. Inversez la détection " +
                    "ou choisissez une image au fond plus uni.",
            )
        }

        val contour = ContourTracer.outerContour(outcome.mask)
            ?: throw ImageShapeException("Le contour du sujet n'a pas pu être suivi.")

        val tolerance = hypot(raster.width.toDouble(), raster.height.toDouble()) * SIMPLIFY_RATIO
        val simplified = PathSimplifier.simplify(contour, tolerance.coerceAtLeast(1.0))
        if (simplified.size < ShapePath.MIN_POINTS) {
            throw ImageShapeException("La forme obtenue est trop sommaire pour dessiner un parcours.")
        }

        val smoothed = PathSimplifier.smoothClosed(simplified)
        val path = runCatching {
            // Repère écran : l'axe y descend, la conversion le remet vers le nord.
            // +1 : sur un tracé fermé, le rééchantillonnage ne conserve pas le point
            // de bouclage, qui ferait doublon avec le premier.
            ShapePath.fromScreen(smoothed, closed = true).resampled(OUTPUT_POINTS + 1)
        }.getOrElse {
            throw ImageShapeException("La forme obtenue est dégénérée : ${it.message}")
        }

        return ExtractedShape(
            path = path,
            contour = simplified,
            threshold = outcome.threshold,
            usedAlpha = outcome.usedAlpha,
            inverted = outcome.inverted,
            coverage = coverage,
        )
    }
}

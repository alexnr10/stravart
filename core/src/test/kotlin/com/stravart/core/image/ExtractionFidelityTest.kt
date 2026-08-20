package com.stravart.core.image

import com.stravart.core.shape.Pt
import com.stravart.core.shape.ShapeLibrary
import com.stravart.core.shape.ShapePath
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

/**
 * Vérifie que passer par une image ne dégrade pas la forme.
 *
 * Chaque forme du catalogue est dessinée dans une image, puis relue par la détection
 * de contours : ce qui en ressort doit être la forme de départ. Ce test tient lieu de
 * garde-fou, une version antérieure ayant perdu jusqu'au dixième de la forme —
 * pointes d'étoile rabotées, crans d'éclair comblés — pour un défaut invisible à
 * l'œil sur l'aperçu mais qui dominait de loin l'erreur du routage.
 */
class ExtractionFidelityTest {

    /** Écart toléré entre la forme relue et l'originale, en % de l'emprise. */
    private val maxDistortionPercent = 1.0

    /** Remplit un polygone dans une image, par balayage pair-impair. */
    private fun rasterise(shape: ShapePath, size: Int = 640): Raster {
        val margin = size * 0.1
        val span = size - 2 * margin
        // Plan normalisé [-0,5 ; 0,5] avec le nord en haut -> repère écran, y vers le bas.
        val polygon = shape.renderedPoints.map {
            Pt(margin + (it.x + 0.5) * span, margin + (0.5 - it.y) * span)
        }
        val luminance = IntArray(size * size) { WHITE }
        for (y in 0 until size) {
            val crossings = ArrayList<Double>()
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                val scanline = y + 0.5
                if ((a.y <= scanline && b.y > scanline) || (b.y <= scanline && a.y > scanline)) {
                    crossings += a.x + (scanline - a.y) / (b.y - a.y) * (b.x - a.x)
                }
            }
            crossings.sort()
            var k = 0
            while (k + 1 < crossings.size) {
                val from = crossings[k].toInt().coerceIn(0, size - 1)
                val to = crossings[k + 1].toInt().coerceIn(0, size - 1)
                for (x in from..to) luminance[y * size + x] = BLACK
                k += 2
            }
        }
        return Raster(size, size, luminance)
    }

    /** Grignote le bord au hasard, comme une silhouette découpée dans une photo. */
    private fun roughen(raster: Raster, percent: Int, seed: Long): Raster {
        val random = Random(seed)
        val luminance = raster.luminance.copyOf()
        for (y in 1 until raster.height - 1) {
            for (x in 1 until raster.width - 1) {
                val here = raster.luminance[y * raster.width + x]
                val onEdge = here != raster.luminance[y * raster.width + x + 1] ||
                    here != raster.luminance[(y + 1) * raster.width + x]
                if (onEdge && random.nextInt(100) < percent) {
                    luminance[y * raster.width + x] = WHITE - here
                }
            }
        }
        return Raster(raster.width, raster.height, luminance)
    }

    /**
     * Écart moyen entre la forme relue et l'originale, en % de l'emprise. Les deux
     * étant normalisées dans le même carré, la comparaison est directe.
     */
    private fun distortionPercent(original: ShapePath, extracted: ShapePath): Double {
        val reference = original.resampled(200).renderedPoints
        val candidate = extracted.resampled(200).renderedPoints
        return reference.map { p -> candidate.minOf { hypot(it.x - p.x, it.y - p.y) } }.average() * 100
    }

    @Test
    fun `every catalogue shape survives a round trip through an image`() {
        for (preset in ShapeLibrary.presets) {
            val extracted = ImageShapeExtractor.extract(rasterise(preset.path)).path
            val distortion = distortionPercent(preset.path, extracted)
            assertTrue(
                "${preset.label} : ${"%.2f".format(distortion)} % de distorsion",
                distortion < maxDistortionPercent,
            )
        }
    }

    @Test
    fun `a ragged edge does not blunt the shape either`() {
        for (preset in ShapeLibrary.presets) {
            val rough = roughen(rasterise(preset.path), percent = 45, seed = 7)
            val extracted = ImageShapeExtractor.extract(rough).path
            val distortion = distortionPercent(preset.path, extracted)
            assertTrue(
                "${preset.label} : ${"%.2f".format(distortion)} % de distorsion",
                distortion < maxDistortionPercent * 1.5,
            )
        }
    }

    @Test
    fun `the sharpest features are the ones worth checking`() {
        // L'étoile et l'éclair sont les plus exposés : ce sont leurs pointes et leurs
        // crans qu'un lissage mal placé efface en premier.
        for (id in listOf("star", "lightning", "arrow")) {
            val preset = ShapeLibrary.byId(id)!!
            val extracted = ImageShapeExtractor.extract(rasterise(preset.path)).path
            assertTrue(
                "${preset.label} : ${extracted.length / kotlin.math.max(extracted.bounds.width, extracted.bounds.height)}",
                // Le rapport longueur/emprise chute dès qu'on rabote les angles.
                extracted.length / kotlin.math.max(extracted.bounds.width, extracted.bounds.height) >
                    preset.path.length / kotlin.math.max(preset.path.bounds.width, preset.path.bounds.height) * 0.95,
            )
        }
    }

    private companion object {
        const val WHITE = 255
        const val BLACK = 0
    }
}

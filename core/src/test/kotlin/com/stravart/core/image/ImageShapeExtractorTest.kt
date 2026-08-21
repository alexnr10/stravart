package com.stravart.core.image

import com.stravart.core.shape.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ImageShapeExtractorTest {

    /** Construit une image en niveaux de gris à partir d'un test d'appartenance. */
    private fun raster(
        width: Int = 200,
        height: Int = 200,
        alpha: Boolean = false,
        subject: (Int, Int) -> Boolean,
    ): Raster {
        val luminance = IntArray(width * height)
        val opacity = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val inside = subject(x, y)
            luminance[y * width + x] = if (inside) 20 else 240
            opacity[y * width + x] = if (inside) 255 else 0
        }
        return Raster(width, height, luminance, if (alpha) opacity else null)
    }

    private fun disc(cx: Int, cy: Int, r: Int): (Int, Int) -> Boolean =
        { x, y -> hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r }

    @Test
    fun `a dark disc on a light background becomes a round shape`() {
        val extracted = ImageShapeExtractor.extract(raster(subject = disc(100, 100, 60)))

        assertEquals(ImageShapeExtractor.OUTPUT_POINTS, extracted.path.points.size)
        assertTrue(extracted.path.closed)
        // Un disque est aussi large que haut.
        assertEquals(1.0, extracted.path.bounds.width / extracted.path.bounds.height, 0.08)
        // Et son périmètre approche celui du cercle circonscrit à la forme normalisée.
        assertEquals(Math.PI, extracted.path.length, 0.15)
    }

    @Test
    fun `a rectangle keeps its proportions`() {
        val extracted = ImageShapeExtractor.extract(
            raster(subject = { x, y -> x in 40..160 && y in 70..130 }),
        )
        // 120 de large sur 60 de haut : deux fois plus large que haut.
        assertEquals(2.0, extracted.path.bounds.width / extracted.path.bounds.height, 0.1)
    }

    @Test
    fun `the shape is flipped so that the top of the image points north`() {
        // Un triangle dont la pointe est en haut de l'image.
        val extracted = ImageShapeExtractor.extract(
            raster(subject = { x, y -> y >= 40 && kotlin.math.abs(x - 100) <= (y - 40) / 2 }),
        )
        val topOfImage = extracted.path.points.maxByOrNull { it.y }!!
        // La pointe, étroite, doit se retrouver au nord après conversion.
        assertTrue(kotlin.math.abs(topOfImage.x) < 0.1)
    }

    @Test
    fun `the largest subject wins over the specks around it`() {
        val extracted = ImageShapeExtractor.extract(
            raster(subject = { x, y ->
                disc(60, 60, 40)(x, y) || disc(170, 30, 8)(x, y) || disc(20, 180, 6)(x, y)
            }),
        )
        // Le grand disque fait 80 px de côté : la forme retenue est ronde, pas éclatée.
        assertEquals(1.0, extracted.path.bounds.width / extracted.path.bounds.height, 0.12)
    }

    @Test
    fun `only the outer contour is kept, holes are ignored`() {
        val ring = raster(subject = { x, y ->
            val d = hypot((x - 100).toDouble(), (y - 100).toDouble())
            d in 40.0..70.0
        })
        val extracted = ImageShapeExtractor.extract(ring)
        // Le contour extérieur seul : un anneau donne un cercle, pas deux.
        assertEquals(1.0, extracted.path.bounds.width / extracted.path.bounds.height, 0.08)
        assertEquals(Math.PI, extracted.path.length, 0.15)
    }

    @Test
    fun `a light subject on a dark background is detected too`() {
        // Le pourtour de l'image est sombre : c'est donc lui le fond.
        val width = 200
        val luminance = IntArray(width * width) { index ->
            val x = index % width
            val y = index / width
            if (disc(100, 100, 55)(x, y)) 235 else 15
        }
        val extracted = ImageShapeExtractor.extract(Raster(width, width, luminance))
        assertTrue("détection inversée attendue", extracted.inverted)
        assertEquals(1.0, extracted.path.bounds.width / extracted.path.bounds.height, 0.08)
    }

    @Test
    fun `transparency is preferred over guessing`() {
        val extracted = ImageShapeExtractor.extract(raster(alpha = true, subject = disc(100, 100, 50)))
        assertTrue(extracted.usedAlpha)
        assertEquals(1.0, extracted.path.bounds.width / extracted.path.bounds.height, 0.08)
    }

    @Test
    fun `a blank image is refused with an explanation`() {
        val blank = Raster(100, 100, IntArray(100 * 100) { 255 })
        val error = runCatching { ImageShapeExtractor.extract(blank) }.exceptionOrNull()
        assertTrue(error is ImageShapeException)
        assertTrue(error!!.message!!.isNotBlank())
    }

    @Test
    fun `a speck of dust is not a shape`() {
        val extracted = runCatching {
            ImageShapeExtractor.extract(raster(subject = disc(100, 100, 2)))
        }
        assertTrue(extracted.exceptionOrNull() is ImageShapeException)
    }

    @Test
    fun `sensitivity decides how much of a soft edge is kept`() {
        // Un dégradé radial, comme le bord flou d'un sujet photographié : le seuil
        // décide alors de la taille du sujet retenu.
        val width = 200
        val luminance = IntArray(width * width) { index ->
            val d = hypot((index % width - 100).toDouble(), (index / width - 100).toDouble())
            (d * 2.2).toInt().coerceIn(0, 255)
        }
        val gradient = Raster(width, width, luminance)

        val tight = ImageShapeExtractor.extract(gradient, sensitivity = -0.8)
        val loose = ImageShapeExtractor.extract(gradient, sensitivity = 0.8)

        assertTrue(tight.threshold < loose.threshold)
        assertTrue(
            "serré ${tight.coverage} vs large ${loose.coverage}",
            tight.coverage < loose.coverage,
        )
    }

    @Test
    fun `the returned contour is expressed in image pixels`() {
        val extracted = ImageShapeExtractor.extract(raster(subject = disc(100, 100, 60)))
        extracted.contour.forEach {
            assertTrue(it.x in 0.0..200.0)
            assertTrue(it.y in 0.0..200.0)
        }
        // Il enferme une aire proche de celle du disque d'origine.
        assertEquals(Math.PI * 60 * 60, PathSimplifier.area(extracted.contour), 3_000.0)
    }

    @Test
    fun `simplification removes the pixel staircase`() {
        val diagonal = (0..100).flatMap { listOf(Pt(it.toDouble(), it.toDouble()), Pt(it + 1.0, it.toDouble())) }
        val simplified = PathSimplifier.simplify(diagonal, tolerancePixels = 2.0)
        assertTrue("il reste ${simplified.size} points", simplified.size < 10)
    }
}

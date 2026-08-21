package com.stravart.core.shape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShapePathTest {

    @Test
    fun `normalisation centers the shape and caps its extent at one`() {
        val square = ShapePath.of(
            listOf(Pt(10.0, 20.0), Pt(14.0, 20.0), Pt(14.0, 22.0), Pt(10.0, 22.0)),
            closed = true,
        )
        assertEquals(0.0, square.bounds.centerX, 1e-9)
        assertEquals(0.0, square.bounds.centerY, 1e-9)
        assertEquals(1.0, square.bounds.width, 1e-9)
        assertEquals(0.5, square.bounds.height, 1e-9)
    }

    @Test
    fun `closed shape length includes the closing segment`() {
        val square = ShapePath.of(
            listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0)),
            closed = true,
        )
        assertEquals(4.0, square.length, 1e-9)

        val open = ShapePath.of(
            listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0)),
            closed = false,
        )
        assertEquals(3.0, open.length, 1e-9)
    }

    @Test
    fun `resampling preserves length and produces even spacing`() {
        val square = ShapePath.of(
            listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0)),
            closed = true,
        )
        val resampled = square.resampled(101)
        assertEquals(100, resampled.points.size) // le point de bouclage n'est pas dupliqué
        assertEquals(square.length, resampled.length, 1e-9)

        val pts = resampled.renderedPoints
        val steps = (1 until pts.size).map { abs(pts[it].x - pts[it - 1].x) + abs(pts[it].y - pts[it - 1].y) }
        steps.forEach { assertEquals(square.length / 100, it, 1e-9) }
    }

    @Test
    fun `screen coordinates are flipped so that north is up`() {
        val fromScreen = ShapePath.fromScreen(listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(0.5, 1.0)), closed = true)
        // Le point le plus haut à l'écran (y = 0) doit devenir le point le plus au nord.
        assertTrue(fromScreen.points[0].y > fromScreen.points[2].y)
    }

    @Test
    fun `duplicate points are dropped`() {
        val shape = ShapePath.of(
            listOf(Pt(0.0, 0.0), Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0)),
            closed = true,
        )
        assertEquals(3, shape.points.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a degenerate shape is rejected`() {
        ShapePath.of(listOf(Pt(1.0, 1.0), Pt(1.0, 1.0)), closed = true)
    }

    @Test
    fun `open shapes are not closed implicitly`() {
        val line = ShapePath.of(listOf(Pt(0.0, 0.0), Pt(1.0, 0.0)), closed = false)
        assertFalse(line.closed)
        assertEquals(2, line.renderedPoints.size)
    }
}

package com.stravart.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoTest {

    private val paris = LatLon(48.8566, 2.3522)

    @Test
    fun `distance between two known points matches reference value`() {
        // Paris (Notre-Dame) -> Tour Eiffel : ~4,1 km à vol d'oiseau.
        val notreDame = LatLon(48.8530, 2.3499)
        val eiffel = LatLon(48.8584, 2.2945)
        val distance = Geo.distance(notreDame, eiffel)
        assertTrue("distance inattendue: $distance", abs(distance - 4100) < 120)
    }

    @Test
    fun `offset then toLocal round trips`() {
        val moved = Geo.offset(paris, eastMeters = 1500.0, northMeters = -700.0)
        val local = Geo.toLocal(paris, moved)
        assertEquals(1500.0, local.x, 0.5)
        assertEquals(-700.0, local.y, 0.5)
    }

    @Test
    fun `offset distance matches requested displacement`() {
        val moved = Geo.offset(paris, 3000.0, 4000.0)
        assertEquals(5000.0, Geo.distance(paris, moved), 5.0)
    }

    @Test
    fun `resample keeps endpoints and total length`() {
        val path = (0..10).map { Geo.offset(paris, it * 100.0, 0.0) }
        val resampled = Geo.resample(path, 37)
        assertEquals(37, resampled.size)
        assertEquals(path.first().lat, resampled.first().lat, 1e-9)
        assertEquals(path.last().lon, resampled.last().lon, 1e-9)
        assertEquals(Geo.pathLength(path), Geo.pathLength(resampled), 1.0)
    }

    @Test
    fun `resample spaces points evenly`() {
        val path = listOf(paris, Geo.offset(paris, 1000.0, 0.0), Geo.offset(paris, 1000.0, 1000.0))
        val resampled = Geo.resample(path, 21)
        val steps = (1 until resampled.size).map { Geo.distance(resampled[it - 1], resampled[it]) }
        val expected = 2000.0 / 20
        steps.forEach { assertEquals(expected, it, 1.0) }
    }

    @Test
    fun `distanceToPath measures perpendicular offset`() {
        val path = listOf(paris, Geo.offset(paris, 1000.0, 0.0))
        val above = Geo.offset(paris, 500.0, 250.0)
        assertEquals(250.0, Geo.distanceToPath(above, path), 1.0)
    }

    @Test
    fun `distanceToPath clamps to segment ends`() {
        val path = listOf(paris, Geo.offset(paris, 1000.0, 0.0))
        val beyond = Geo.offset(paris, 1300.0, 0.0)
        assertEquals(300.0, Geo.distanceToPath(beyond, path), 1.0)
    }
}

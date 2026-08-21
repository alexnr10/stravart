package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeCoverageTest {

    private val start = LatLon(48.8566, 2.3522)

    private fun at(east: Double, north: Double) = Geo.offset(start, east, north)

    private fun leg(fromE: Double, fromN: Double, toE: Double, toN: Double) = buildList {
        val length = kotlin.math.hypot(toE - fromE, toN - fromN)
        val count = kotlin.math.ceil(length / 25.0).toInt().coerceAtLeast(1)
        for (k in 1..count) {
            val t = k.toDouble() / count
            add(at(fromE + (toE - fromE) * t, fromN + (toN - fromN) * t))
        }
    }

    /** Forme droite de 2 km d'est en ouest, échantillonnée finement. */
    private val ideal = buildList {
        add(at(0.0, 0.0))
        addAll(leg(0.0, 0.0, 2000.0, 0.0))
    }

    @Test
    fun `a route that hugs the shape leaves nothing behind`() {
        val route = ideal.map { Geo.offset(it, 0.0, 20.0) }
        val report = ShapeCoverage.analyse(ideal, route)
        assertTrue(report.stretches.isEmpty())
        assertEquals(0.0, report.unfollowedRatio, 1e-9)
    }

    @Test
    fun `a detour around an obstacle is reported with its extent`() {
        // L'itinéraire s'écarte de 300 m entre 800 m et 1 400 m : un obstacle.
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 800.0, 0.0))
            addAll(leg(800.0, 0.0, 800.0, 300.0))
            addAll(leg(800.0, 300.0, 1400.0, 300.0))
            addAll(leg(1400.0, 300.0, 1400.0, 0.0))
            addAll(leg(1400.0, 0.0, 2000.0, 0.0))
        }
        val report = ShapeCoverage.analyse(ideal, route)

        assertEquals(1, report.stretches.size)
        val stretch = report.stretches.single()
        assertEquals(300.0, stretch.maxDeviationMeters, 30.0)

        // La portion signalée est plus courte que l'obstacle : aux deux bords du
        // détour, l'itinéraire revient toucher la forme, et là il la suit encore.
        // Ce qui est perdu, c'est le milieu — environ 400 m sur 2 000.
        assertEquals(400.0, stretch.lengthMeters, 80.0)
        assertEquals(0.20, report.unfollowedRatio, 0.05)
    }

    @Test
    fun `the reported stretch follows the shape, not the route`() {
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 800.0, 0.0))
            addAll(leg(800.0, 0.0, 800.0, 300.0))
            addAll(leg(800.0, 300.0, 1400.0, 300.0))
            addAll(leg(1400.0, 300.0, 1400.0, 0.0))
            addAll(leg(1400.0, 0.0, 2000.0, 0.0))
        }
        val stretch = ShapeCoverage.analyse(ideal, route).stretches.single()
        // La portion signalée est bien sur la forme : elle reste sur l'axe est-ouest.
        stretch.shapePoints.forEach {
            assertTrue(kotlin.math.abs(Geo.toLocal(start, it).y) < 1.0)
        }
    }

    @Test
    fun `two separate obstacles are reported separately`() {
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 300.0, 0.0))
            addAll(leg(300.0, 0.0, 300.0, 250.0))
            addAll(leg(300.0, 250.0, 700.0, 250.0))
            addAll(leg(700.0, 250.0, 700.0, 0.0))
            addAll(leg(700.0, 0.0, 1300.0, 0.0))
            addAll(leg(1300.0, 0.0, 1300.0, 250.0))
            addAll(leg(1300.0, 250.0, 1700.0, 250.0))
            addAll(leg(1700.0, 250.0, 1700.0, 0.0))
            addAll(leg(1700.0, 0.0, 2000.0, 0.0))
        }
        assertEquals(2, ShapeCoverage.analyse(ideal, route).stretches.size)
    }

    @Test
    fun `a brief wobble is not worth reporting`() {
        // Écart franc mais bref : cinquante mètres de forme, invisible à l'échelle.
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 1000.0, 0.0))
            addAll(leg(1000.0, 0.0, 1025.0, 200.0))
            addAll(leg(1025.0, 200.0, 1050.0, 0.0))
            addAll(leg(1050.0, 0.0, 2000.0, 0.0))
        }
        assertTrue(ShapeCoverage.analyse(ideal, route).stretches.isEmpty())
    }

    @Test
    fun `the threshold can be tightened`() {
        val route = ideal.map { Geo.offset(it, 0.0, 140.0) }
        assertTrue(ShapeCoverage.analyse(ideal, route, thresholdMeters = 200.0).stretches.isEmpty())
        assertEquals(1, ShapeCoverage.analyse(ideal, route, thresholdMeters = 80.0).stretches.size)
    }

    @Test
    fun `degenerate inputs are handled`() {
        assertEquals(0.0, ShapeCoverage.analyse(emptyList(), emptyList()).unfollowedRatio, 1e-9)
        assertEquals(0.0, ShapeCoverage.analyse(ideal, emptyList()).unfollowedRatio, 1e-9)
    }
}

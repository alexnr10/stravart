package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteOverlapTest {

    private val start = LatLon(45.7640, 4.8357)

    private fun at(east: Double, north: Double) = Geo.offset(start, east, north)

    private fun leg(fromE: Double, fromN: Double, toE: Double, toN: Double) = buildList {
        val length = kotlin.math.hypot(toE - fromE, toN - fromN)
        val count = kotlin.math.ceil(length / 20.0).toInt().coerceAtLeast(1)
        for (k in 1..count) {
            val t = k.toDouble() / count
            add(at(fromE + (toE - fromE) * t, fromN + (toN - fromN) * t))
        }
    }

    @Test
    fun `a clean loop retraces nothing`() {
        val loop = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 1000.0, 0.0))
            addAll(leg(1000.0, 0.0, 1000.0, 1000.0))
            addAll(leg(1000.0, 1000.0, 0.0, 1000.0))
            addAll(leg(0.0, 1000.0, 0.0, 0.0))
        }
        assertTrue("recouvrement ${RouteOverlap.measure(loop)}", RouteOverlap.measure(loop) < 0.05)
    }

    @Test
    fun `an out and back retraces half of its length`() {
        // Aller-retour pur : la moitié du parcours est un second passage.
        val there = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 1000.0, 0.0))
            addAll(leg(1000.0, 0.0, 0.0, 0.0))
        }
        val ratio = RouteOverlap.measure(there)
        assertTrue("recouvrement $ratio", ratio in 0.40..0.55)
    }

    @Test
    fun `a lollipop counts only its stem`() {
        // 500 m de tige, une boucle de 1 000 m, puis les 500 m de tige à l'envers :
        // un quart des 2 000 m est refait.
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 500.0, 0.0))
            addAll(leg(500.0, 0.0, 500.0, 250.0))
            addAll(leg(500.0, 250.0, 750.0, 250.0))
            addAll(leg(750.0, 250.0, 750.0, 0.0))
            addAll(leg(750.0, 0.0, 500.0, 0.0))
            addAll(leg(500.0, 0.0, 0.0, 0.0))
        }
        val ratio = RouteOverlap.measure(route)
        assertTrue("recouvrement $ratio", ratio in 0.18..0.32)
    }

    @Test
    fun `parallel streets are not mistaken for a second pass`() {
        // Deux rues distantes de 60 m : un tracé qui remonte l'une puis redescend
        // l'autre ne revient pas sur ses pas.
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 800.0, 0.0))
            addAll(leg(800.0, 0.0, 800.0, 60.0))
            addAll(leg(800.0, 60.0, 0.0, 60.0))
        }
        assertTrue("recouvrement ${RouteOverlap.measure(route)}", RouteOverlap.measure(route) < 0.05)
    }

    @Test
    fun `a degenerate input is handled`() {
        assertEquals(0.0, RouteOverlap.measure(emptyList()), 1e-9)
        assertEquals(0.0, RouteOverlap.measure(listOf(at(0.0, 0.0))), 1e-9)
        assertEquals(0.0, RouteOverlap.measure(listOf(at(0.0, 0.0), at(0.0, 0.0))), 1e-9)
    }
}

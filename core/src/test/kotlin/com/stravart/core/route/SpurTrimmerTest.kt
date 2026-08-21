package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpurTrimmerTest {

    private val start = LatLon(48.8566, 2.3522)

    private fun at(east: Double, north: Double) = Geo.offset(start, east, north)

    /** Segment droit échantillonné tous les [step] mètres, extrémité finale comprise. */
    private fun leg(fromE: Double, fromN: Double, toE: Double, toN: Double, step: Double = 25.0) =
        buildList {
            val length = kotlin.math.hypot(toE - fromE, toN - fromN)
            val count = kotlin.math.ceil(length / step).toInt().coerceAtLeast(1)
            for (k in 1..count) {
                val t = k.toDouble() / count
                add(at(fromE + (toE - fromE) * t, fromN + (toN - fromN) * t))
            }
        }

    @Test
    fun `an out-and-back into a dead end is removed`() {
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 400.0, 0.0))
            addAll(leg(400.0, 0.0, 400.0, 200.0)) // entrée dans l'impasse
            addAll(leg(400.0, 200.0, 400.0, 0.0)) // et retour par le même chemin
            addAll(leg(400.0, 0.0, 1000.0, 0.0))
        }
        val before = Geo.pathLength(route)

        val result = SpurTrimmer.trim(route)
        val cleaned = result.select(route)

        assertEquals(1, result.spurCount)
        assertEquals(400.0, result.removedMeters, 5.0)
        assertEquals(before - 400.0, Geo.pathLength(cleaned), 5.0)

        // Plus rien ne s'écarte de l'axe est-ouest.
        cleaned.forEach { assertTrue(kotlin.math.abs(Geo.toLocal(start, it).y) < 1.0) }
    }

    @Test
    fun `the endpoints of the route are always preserved`() {
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 0.0, 300.0))
            addAll(leg(0.0, 300.0, 0.0, 0.0))
            addAll(leg(0.0, 0.0, 800.0, 0.0))
        }
        val cleaned = SpurTrimmer.trim(route).select(route)

        assertEquals(0.0, Geo.distance(route.first(), cleaned.first()), 1.0)
        assertEquals(0.0, Geo.distance(route.last(), cleaned.last()), 1.0)
    }

    @Test
    fun `a genuine block loop is kept`() {
        // Un tour de pâté de maisons revient lui aussi à son point de départ, mais il
        // enferme une surface : c'est ce qui le distingue d'un aller-retour. On
        // repart ensuite vers le sud, pour qu'aucune portion ne serve deux fois.
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 2000.0, 0.0))
            addAll(leg(2000.0, 0.0, 2000.0, 200.0))
            addAll(leg(2000.0, 200.0, 2200.0, 200.0))
            addAll(leg(2200.0, 200.0, 2200.0, 0.0))
            addAll(leg(2200.0, 0.0, 2000.0, 0.0))
            addAll(leg(2000.0, 0.0, 2000.0, -600.0))
        }
        val result = SpurTrimmer.trim(route)
        assertEquals(0, result.spurCount)
        assertFalse(result.trimmed)
    }

    @Test
    fun `retracing a stretch of the outward road is removed too`() {
        // Cas courant : le tracé referme un pâté de maisons puis repart par où il
        // vient d'arriver. Les 400 m parcourus deux fois n'ont pas à rester.
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 2000.0, 0.0))
            addAll(leg(2000.0, 0.0, 2000.0, 200.0))
            addAll(leg(2000.0, 200.0, 2200.0, 200.0))
            addAll(leg(2200.0, 200.0, 2200.0, 0.0))
            addAll(leg(2200.0, 0.0, 2000.0, 0.0))
            addAll(leg(2000.0, 0.0, 2600.0, 0.0))
        }
        assertEquals(1, SpurTrimmer.trim(route).spurCount)
    }

    @Test
    fun `a clean closed loop is left untouched`() {
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 1000.0, 0.0))
            addAll(leg(1000.0, 0.0, 1000.0, 1000.0))
            addAll(leg(1000.0, 1000.0, 0.0, 1000.0))
            addAll(leg(0.0, 1000.0, 0.0, 0.0))
        }
        val result = SpurTrimmer.trim(route)
        assertEquals(0, result.spurCount)
        assertEquals(route.size, result.keptIndices.size)
    }

    @Test
    fun `several dead ends are all removed`() {
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 300.0, 0.0))
            addAll(leg(300.0, 0.0, 300.0, 150.0))
            addAll(leg(300.0, 150.0, 300.0, 0.0))
            addAll(leg(300.0, 0.0, 700.0, 0.0))
            addAll(leg(700.0, 0.0, 700.0, -180.0))
            addAll(leg(700.0, -180.0, 700.0, 0.0))
            addAll(leg(700.0, 0.0, 1200.0, 0.0))
        }
        val result = SpurTrimmer.trim(route)
        assertEquals(2, result.spurCount)
        // 150 et 180 m d'impasse, aller et retour. La coupe peut mordre de quelques
        // dizaines de mètres sur la voie principale, de part et d'autre de l'entrée :
        // le raccord y reste une corde de moins de 25 m, donc sur la chaussée.
        assertEquals(660.0, result.removedMeters, 60.0)

        val cleaned = result.select(route)
        cleaned.forEach { assertTrue(kotlin.math.abs(Geo.toLocal(start, it).y) < 1.0) }
    }

    @Test
    fun `a very short wobble is not worth removing`() {
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 500.0, 0.0))
            addAll(leg(500.0, 0.0, 500.0, 8.0, step = 4.0))
            addAll(leg(500.0, 8.0, 500.0, 0.0, step = 4.0))
            addAll(leg(500.0, 0.0, 1000.0, 0.0))
        }
        assertEquals(0, SpurTrimmer.trim(route).spurCount)
    }

    @Test
    fun `parallel data such as elevation follows the same selection`() {
        val route = buildList {
            add(at(0.0, 0.0))
            addAll(leg(0.0, 0.0, 400.0, 0.0))
            addAll(leg(400.0, 0.0, 400.0, 200.0))
            addAll(leg(400.0, 200.0, 400.0, 0.0))
            addAll(leg(400.0, 0.0, 1000.0, 0.0))
        }
        val elevations = route.indices.map { it.toDouble() }
        val result = SpurTrimmer.trim(route)

        val keptPoints = result.select(route)
        val keptElevations = result.select(elevations)
        assertEquals(keptPoints.size, keptElevations.size)
        // Chaque altitude conservée reste celle de son point.
        result.keptIndices.forEachIndexed { position, original ->
            assertEquals(original.toDouble(), keptElevations[position], 1e-9)
        }
    }

    @Test
    fun `a route too short to contain a detour is returned as is`() {
        val route = listOf(at(0.0, 0.0), at(100.0, 0.0), at(200.0, 0.0))
        val result = SpurTrimmer.trim(route)
        assertEquals(listOf(0, 1, 2), result.keptIndices)
        assertEquals(0, result.spurCount)
    }
}

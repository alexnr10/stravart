package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.routing.ActivityType
import com.stravart.core.routing.RoutedPath
import com.stravart.core.routing.RoutingEngine
import com.stravart.core.routing.StraightLineEngine
import com.stravart.core.shape.ShapeLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Vérifie les deux garde-fous ajoutés après retour d'usage : les allers-retours dans
 * les impasses disparaissent du parcours, et un quartier qui ne permet pas de boucler
 * se solde par un refus explicite plutôt que par un tracé absurde.
 */
class DeadEndSimulationTest {

    private val start = LatLon(48.8566, 2.3522)

    private fun request(km: Double = 8.0, shapeId: String = "heart", overlap: Double = 0.30) =
        RouteRequest(
            shape = ShapeLibrary.byId(shapeId)!!.path,
            start = start,
            distanceMeters = km * 1000,
            activity = ActivityType.RUN,
            maxOverlapRatio = overlap,
        )

    @Test
    fun `dead end detours never reach the finished route`() {
        val engine = DeadEndEngine(spurMeters = 150.0)
        val route = RouteGenerator(engine).generate(request())

        assertTrue("aucun aller-retour retiré", route.removedSpurs > 0)
        // Le tracé rendu ne contient plus d'excursion : un second passage du
        // nettoyeur n'a plus rien à couper.
        assertEquals(0, SpurTrimmer.trim(route.points).spurCount)
    }

    @Test
    fun `trimming the detours does not break the requested distance`() {
        val route = RouteGenerator(DeadEndEngine(spurMeters = 150.0)).generate(request())
        val error = abs(route.distanceMeters - 8_000.0) / 8_000.0
        assertTrue("écart de ${(error * 100).roundToInt()} %", error <= 0.05)
    }

    @Test
    fun `the loop still starts and ends on the chosen point`() {
        val route = RouteGenerator(DeadEndEngine(spurMeters = 200.0)).generate(request())
        assertEquals(0.0, Geo.distance(start, route.points.first()), 5.0)
        assertEquals(0.0, Geo.distance(start, route.points.last()), 5.0)
    }

    @Test
    fun `elevation stays aligned with the trimmed geometry`() {
        val route = RouteGenerator(DeadEndEngine(spurMeters = 150.0, withElevation = true))
            .generate(request())
        assertNotNull(route.elevations)
        assertEquals(route.points.size, route.elevations!!.size)
    }

    @Test
    fun `an area that cannot be looped is refused rather than fudged`() {
        val error = runCatching { RouteGenerator(RetracingEngine()).generate(request()) }
            .exceptionOrNull()

        assertTrue("exception inattendue: $error", error is UnsuitableAreaException)
        val unsuitable = error as UnsuitableAreaException
        assertTrue("recouvrement ${unsuitable.overlapRatio}", unsuitable.overlapRatio > 0.30)
        assertTrue(unsuitable.message!!.contains("revenir sur ses pas"))
    }

    @Test
    fun `the caller can accept a route that doubles back`() {
        val route = RouteGenerator(RetracingEngine()).generate(request(overlap = 1.0))
        assertTrue("recouvrement ${route.overlapRatio}", route.overlapRatio > 0.30)
    }

    @Test
    fun `a clean engine reports no detour and no doubling back`() {
        val route = RouteGenerator(StraightLineEngine).generate(request())
        assertEquals(0, route.removedSpurs)
        assertEquals(0.0, route.overlapRatio, 1e-9)
    }

    /** Relie les points de passage en ligne droite, en densifiant à [step] mètres. */
    private fun connect(from: LatLon, to: LatLon, step: Double = 25.0): List<LatLon> {
        val length = Geo.distance(from, to)
        val count = kotlin.math.ceil(length / step).toInt().coerceAtLeast(1)
        return (1..count).map { k ->
            val t = k.toDouble() / count
            LatLon(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t)
        }
    }

    /**
     * Moteur simulant un réseau parsemé d'impasses : un point de passage sur trois
     * n'est atteignable qu'en entrant puis en ressortant par le même chemin.
     */
    private inner class DeadEndEngine(
        private val spurMeters: Double,
        private val withElevation: Boolean = false,
    ) : RoutingEngine {
        override val id = "dead-end"
        override val displayName = "Impasses simulées"
        override val snapsToRoads = true
        override val maxWaypoints = 40

        override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
            val points = ArrayList<LatLon>()
            points += waypoints.first()
            waypoints.forEachIndexed { index, waypoint ->
                if (index > 0) points += connect(points.last(), waypoint)
                if (index > 0 && index % 3 == 0 && index < waypoints.lastIndex) {
                    val tip = Geo.offset(waypoint, spurMeters, 0.0)
                    points += connect(waypoint, tip)
                    points += connect(tip, waypoint)
                }
            }
            val elevations = if (withElevation) {
                points.mapIndexed { index, _ -> 80.0 + (index % 12) * 2.5 }
            } else {
                null
            }
            return RoutedPath(points, Geo.pathLength(points), elevations)
        }
    }

    /**
     * Moteur simulant un hameau desservi par une seule route : le seul itinéraire
     * possible consiste à aller au bout puis à revenir par le même chemin.
     */
    private inner class RetracingEngine : RoutingEngine {
        override val id = "retracing"
        override val displayName = "Voie unique simulée"
        override val snapsToRoads = true
        override val maxWaypoints = 40

        override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
            val outward = ArrayList<LatLon>()
            outward += waypoints.first()
            for (i in 1 until waypoints.size) outward += connect(outward.last(), waypoints[i])
            val points = outward + outward.asReversed().drop(1)
            return RoutedPath(points, Geo.pathLength(points))
        }
    }
}

package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.routing.ActivityType
import com.stravart.core.routing.RoutedPath
import com.stravart.core.routing.RoutingEngine
import com.stravart.core.routing.RoutingException
import com.stravart.core.routing.StraightLineEngine
import com.stravart.core.shape.ShapeLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class RouteGeneratorTest {

    private val start = LatLon(48.8566, 2.3522)

    private fun request(distanceMeters: Double = 10_000.0, shapeId: String = "heart") = RouteRequest(
        shape = ShapeLibrary.byId(shapeId)!!.path,
        start = start,
        distanceMeters = distanceMeters,
        activity = ActivityType.RUN,
    )

    @Test
    fun `without road snapping the distance is exact and the shape is perfect`() {
        val route = RouteGenerator(StraightLineEngine).generate(request())

        assertEquals(10_000.0, route.distanceMeters, 60.0)
        assertEquals(1, route.attempts)
        assertFalse(route.snappedToRoads)
        assertEquals(100, route.fidelity.score)
        assertEquals(0.0, Geo.distance(start, route.points.first()), 1.0)
    }

    @Test
    fun `road snapping detours are compensated by rescaling the shape`() {
        val engine = InflatingEngine(factor = 1.28)
        val route = RouteGenerator(engine).generate(request())

        val error = abs(route.distanceMeters - 10_000.0) / 10_000.0
        assertTrue("écart de ${(error * 100).toInt()} %", error <= 0.03)
        assertTrue("trop d'appels réseau: ${engine.calls}", engine.calls <= 4)
        assertTrue(route.attempts >= 2)
    }

    @Test
    fun `rescaling converges for every preset shape and both activities`() {
        for (preset in ShapeLibrary.presets) {
            for (activity in ActivityType.entries) {
                val engine = InflatingEngine(factor = 1.35)
                val route = RouteGenerator(engine).generate(
                    request(distanceMeters = 8_000.0).copy(shape = preset.path, activity = activity),
                )
                val error = abs(route.distanceMeters - 8_000.0) / 8_000.0
                assertTrue("${preset.id}/$activity: écart ${(error * 100).toInt()} %", error <= 0.03)
            }
        }
    }

    @Test
    fun `a shorter route than requested is stretched back`() {
        val engine = InflatingEngine(factor = 0.75)
        val route = RouteGenerator(engine).generate(request(distanceMeters = 5_000.0))
        assertTrue(abs(route.distanceMeters - 5_000.0) / 5_000.0 <= 0.03)
    }

    @Test
    fun `waypoint spacing is tight enough to follow the shape`() {
        // C'est l'espacement, et non le plafond, qui décide de la fidélité sur les
        // distances courantes : entre deux points de passage, le moteur est libre.
        assertEquals(120.0, request().effectiveSpacingMeters, 1e-9)
        assertEquals(200.0, request().copy(activity = ActivityType.BIKE).effectiveSpacingMeters, 1e-9)

        val engine = InflatingEngine(factor = 1.2, maxWaypoints = 200)
        RouteGenerator(engine).generate(request(distanceMeters = 10_000.0))
        assertTrue("seulement ${engine.lastWaypointCount} points", engine.lastWaypointCount >= 60)
    }

    @Test
    fun `the number of waypoints stays within the engine limit`() {
        val engine = InflatingEngine(factor = 1.2, maxWaypoints = 12)
        RouteGenerator(engine).generate(request(distanceMeters = 30_000.0))
        assertTrue("appelé avec ${engine.lastWaypointCount} points", engine.lastWaypointCount <= 12)
        assertTrue(engine.lastWaypointCount >= 8)
    }

    @Test
    fun `elevation gain is derived from the engine altitudes`() {
        val engine = InflatingEngine(factor = 1.0, withElevation = true)
        val route = RouteGenerator(engine).generate(request(distanceMeters = 5_000.0))
        assertTrue("dénivelé absent", (route.ascentMeters ?: 0.0) > 0.0)
    }

    @Test(expected = RoutingException::class)
    fun `an engine failure is surfaced to the caller`() {
        RouteGenerator(FailingEngine).generate(request())
    }

    @Test
    fun `progress is reported for each attempt`() {
        val seen = mutableListOf<RouteProgress>()
        RouteGenerator(InflatingEngine(factor = 1.28)).generate(request()) { seen += it }
        assertTrue(seen.isNotEmpty())
        assertEquals(1, seen.first().attempt)
        seen.forEach { assertTrue(it.message.isNotBlank()) }
    }

    /**
     * Moteur simulé : suit fidèlement les points de passage mais rallonge chaque
     * segment d'un facteur constant, comme le ferait un vrai réseau de rues.
     */
    private class InflatingEngine(
        private val factor: Double,
        override val maxWaypoints: Int = 40,
        private val withElevation: Boolean = false,
    ) : RoutingEngine {
        override val id = "fake"
        override val displayName = "Moteur simulé"
        override val snapsToRoads = true

        var calls = 0
        var lastWaypointCount = 0

        override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
            calls++
            lastWaypointCount = waypoints.size

            val points = ArrayList<LatLon>(waypoints.size * 2)
            points += waypoints.first()
            for (i in 1 until waypoints.size) {
                val a = waypoints[i - 1]
                val b = waypoints[i]
                val length = Geo.distance(a, b)
                if (factor > 1.0 && length > 1.0) {
                    // Un crochet perpendiculaire allonge le segment du facteur voulu.
                    val bulge = length / 2 * sqrt(factor * factor - 1)
                    val va = Geo.toLocal(a, b)
                    val nx = -va.y / length
                    val ny = va.x / length
                    val mid = Geo.offset(a, va.x / 2 + nx * bulge, va.y / 2 + ny * bulge)
                    points += mid
                }
                points += b
            }
            val raw = Geo.pathLength(points)
            // Pour factor < 1 on ne peut pas raccourcir la géométrie : on se contente
            // d'annoncer la distance correspondante, ce qui suffit à piloter l'échelle.
            val distance = if (factor >= 1.0) raw else raw * factor
            val elevations = if (withElevation) {
                points.mapIndexed { index, _ -> 100.0 + (index % 10) * 3.0 }
            } else {
                null
            }
            return RoutedPath(points, distance, elevations)
        }
    }

    private object FailingEngine : RoutingEngine {
        override val id = "failing"
        override val displayName = "Moteur en panne"
        override val snapsToRoads = true
        override val maxWaypoints = 40
        override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath =
            throw RoutingException("réseau indisponible")
    }
}

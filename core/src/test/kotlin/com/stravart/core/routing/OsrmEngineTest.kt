package com.stravart.core.routing

import com.stravart.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmEngineTest {

    private val waypoints = listOf(LatLon(48.8566, 2.3522), LatLon(48.8600, 2.3600))

    private val json = """
        {"code":"Ok","routes":[{"distance":2350.7,"duration":900.0,
        "geometry":{"type":"LineString","coordinates":[[2.3522,48.8566],[2.356,48.858],[2.36,48.86]]}}]}
    """.trimIndent()

    @Test
    fun `parses geometry and distance`() {
        val path = OsrmEngine(http = { json }).route(waypoints, ActivityType.BIKE)
        assertEquals(3, path.points.size)
        assertEquals(2350.7, path.distanceMeters, 1e-6)
        assertEquals(48.858, path.points[1].lat, 1e-6)
    }

    @Test
    fun `builds the expected url`() {
        var seen: String? = null
        OsrmEngine(http = { url -> seen = url; json }).route(waypoints, ActivityType.RUN)
        assertTrue(seen!!, seen!!.contains("/route/v1/foot/2.352200,48.856600;2.360000,48.860000"))
        assertTrue(seen!!, seen!!.contains("geometries=geojson"))
    }

    @Test
    fun `an error code is turned into a routing exception`() {
        val engine = OsrmEngine(http = { """{"code":"NoRoute","message":"Impossible route"}""" })
        val error = runCatching { engine.route(waypoints, ActivityType.RUN) }.exceptionOrNull()
        assertTrue(error is RoutingException)
        assertTrue(error!!.message!!.contains("Impossible route"))
    }

    @Test
    fun `a custom profile overrides the activity default`() {
        var seen: String? = null
        OsrmEngine(http = { url -> seen = url; json }, profileOverride = "driving")
            .route(waypoints, ActivityType.RUN)
        assertTrue(seen!!, seen!!.contains("/route/v1/driving/"))
    }
}

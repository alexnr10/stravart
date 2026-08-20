package com.stravart.core.routing

import com.stravart.core.geo.LatLon
import com.stravart.core.net.HttpClient
import com.stravart.core.net.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BRouterEngineTest {

    private val waypoints = listOf(LatLon(48.8566, 2.3522), LatLon(48.8600, 2.3600), LatLon(48.8566, 2.3522))

    private val geoJson = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
        "properties":{"creator":"BRouter-1.7.3","track-length":"4210","filtered ascend":"57",
        "plain-ascend":"41","total-time":"1200"},
        "geometry":{"type":"LineString","coordinates":[
        [2.3522,48.8566,35.0],[2.3560,48.8580,38.5],[2.3600,48.8600,41.0],[2.3522,48.8566,35.0]]}}]}
    """.trimIndent()

    @Test
    fun `parses geometry, distance, elevation and ascent`() {
        val engine = BRouterEngine(http = { geoJson })
        val path = engine.route(waypoints, ActivityType.BIKE)

        assertEquals(4, path.points.size)
        assertEquals(48.8566, path.points.first().lat, 1e-6)
        assertEquals(2.3600, path.points[2].lon, 1e-6)
        assertEquals(4210.0, path.distanceMeters, 1e-6)
        assertEquals(57.0, path.ascentMeters!!, 1e-6)
        assertEquals(listOf(35.0, 38.5, 41.0, 35.0), path.elevations)
    }

    @Test
    fun `builds a request with lon,lat pairs and the activity profile`() {
        var seen: String? = null
        val engine = BRouterEngine(http = { url -> seen = url; geoJson })
        engine.route(waypoints, ActivityType.RUN)

        val url = seen!!
        assertTrue(url, url.startsWith(BRouterEngine.DEFAULT_BASE_URL))
        assertTrue(url, url.contains("format=geojson"))
        assertTrue(url, url.contains("profile=hiking-beta"))
        // lonlats encodés : longitude d'abord, points séparés par une barre verticale.
        assertTrue(url, url.contains("2.352200%2C48.856600%7C"))
    }

    @Test
    fun `falls back to the next profile when the first one is refused`() {
        val attempted = mutableListOf<String>()
        val engine = BRouterEngine(http = { url ->
            val profile = Regex("profile=([^&]+)").find(url)!!.groupValues[1]
            attempted += profile
            if (profile == "hiking-beta") throw HttpException(500, "profile not found") else geoJson
        })
        engine.route(waypoints, ActivityType.RUN)
        assertEquals(listOf("hiking-beta", "trekking"), attempted)
    }

    @Test
    fun `a plain text error is reported as a routing failure`() {
        val engine = BRouterEngine(http = { "target island detected for section 2" })
        val error = runCatching { engine.route(waypoints, ActivityType.BIKE) }.exceptionOrNull()
        assertTrue(error is RoutingException)
        assertTrue(error!!.message!!, error.message!!.contains("island"))
    }

    @Test
    fun `missing coordinates are reported`() {
        val engine = BRouterEngine(http = { """{"type":"FeatureCollection","features":[]}""" })
        val error = runCatching { engine.route(waypoints, ActivityType.BIKE) }.exceptionOrNull()
        assertTrue(error is RoutingException)
    }

    @Test
    fun `coordinates without altitude yield no elevation profile`() {
        val flat = """
            {"features":[{"properties":{"track-length":"1000"},
            "geometry":{"coordinates":[[2.35,48.85],[2.36,48.86],[2.35,48.85]]}}]}
        """.trimIndent()
        val path = BRouterEngine(http = { flat }).route(waypoints, ActivityType.RUN)
        assertNull(path.elevations)
        assertNotNull(path.distanceMeters)
    }

    @Test
    fun `at least two waypoints are required`() {
        val engine = BRouterEngine(http = { geoJson })
        val error = runCatching { engine.route(waypoints.take(1), ActivityType.RUN) }.exceptionOrNull()
        assertTrue(error is RoutingException)
    }

    @Test
    fun `profiles are ordered by relevance for each activity`() {
        assertEquals("hiking-beta", BRouterEngine.profilesFor(ActivityType.RUN).first())
        assertEquals("trekking", BRouterEngine.profilesFor(ActivityType.BIKE).first())
    }
}

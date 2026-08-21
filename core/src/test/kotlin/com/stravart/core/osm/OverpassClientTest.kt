package com.stravart.core.osm

import com.stravart.core.geo.LatLon
import com.stravart.core.net.HttpClient
import com.stravart.core.net.HttpException
import com.stravart.core.routing.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class OverpassClientTest {

    private class Recorder(private val body: String) : HttpClient {
        var lastUrl: String? = null
        override fun get(url: String): String {
            lastUrl = url
            return body
        }
    }

    private val sample = """
        {"version":0.6,"elements":[
          {"type":"way","id":1,"tags":{"highway":"residential"},
           "geometry":[{"lat":48.85,"lon":2.35},{"lat":48.851,"lon":2.351}]},
          {"type":"way","id":2,"tags":{"highway":"footway"},
           "geometry":[{"lat":48.86,"lon":2.36},{"lat":48.861,"lon":2.361},{"lat":48.862,"lon":2.362}]},
          {"type":"node","id":9,"lat":48.85,"lon":2.35},
          {"type":"way","id":3,"tags":{"highway":"service"},"geometry":[{"lat":48.87,"lon":2.37}]}
        ]}
    """.trimIndent()

    private val center = LatLon(48.8566, 2.3522)

    @Test
    fun `ways come back with their geometry`() {
        val ways = OverpassClient(Recorder(sample)).fetch(center, 1000.0)
        assertEquals(2, ways.size)
        assertEquals(2, ways[0].points.size)
        assertEquals(3, ways[1].points.size)
        assertEquals(LatLon(48.85, 2.35), ways[0].points.first())
    }

    /** Un nœud isolé n'est pas une rue, et une voie d'un seul point n'a pas d'orientation. */
    @Test
    fun `nodes and single point ways are left out`() {
        val ways = OverpassClient(Recorder(sample)).fetch(center, 1000.0)
        assertTrue(ways.none { it.points.size < 2 })
    }

    @Test
    fun `the query frames the requested area and filters on the activity`() {
        val recorder = Recorder(sample)
        OverpassClient(recorder).fetch(center, 1000.0, ActivityType.BIKE)
        val query = URLDecoder.decode(recorder.lastUrl!!.substringAfter("data="), "UTF-8")

        assertTrue(query.contains("out geom"))
        assertTrue("les voies rapides doivent rester exclues", !query.contains("motorway"))
        assertTrue("à vélo on ne compte pas les trottoirs", !query.contains("footway"))
        assertTrue(query.contains("cycleway"))

        // La boîte doit encadrer le centre demandé.
        val box = query.substringAfterLast("(").substringBefore(")").split(",").map { it.toDouble() }
        assertEquals(4, box.size)
        assertTrue(box[0] < center.lat && center.lat < box[2])
        assertTrue(box[1] < center.lon && center.lon < box[3])
    }

    @Test
    fun `running keeps the paths that cycling drops`() {
        val onFoot = OverpassClient.highwayFilter(ActivityType.RUN)
        assertTrue(onFoot.contains("footway"))
        assertTrue(onFoot.contains("pedestrian"))
        assertTrue("on ne court pas sur une voie rapide", !onFoot.contains("trunk"))
    }

    /**
     * Mieux vaut refuser franchement qu'expédier une requête que le service mettra
     * une minute à rejeter.
     */
    @Test
    fun `an oversized area is refused before any request`() {
        val recorder = Recorder(sample)
        val error = assertThrows(OverpassException::class.java) {
            OverpassClient(recorder).fetch(center, OverpassClient.MAX_RADIUS_METERS + 1)
        }
        assertTrue(error.message!!.contains("trop vaste"))
        assertEquals(null, recorder.lastUrl)
    }

    @Test
    fun `a service failure is reported as such`() {
        val failing = HttpClient { throw HttpException(429, "Too Many Requests") }
        val error = assertThrows(OverpassException::class.java) {
            OverpassClient(failing).fetch(center, 1000.0)
        }
        assertTrue(error.message!!.contains("indisponible"))
    }

    @Test
    fun `unreadable content is reported rather than silently empty`() {
        val error = assertThrows(OverpassException::class.java) {
            OverpassClient(Recorder("<html>maintenance</html>")).fetch(center, 1000.0)
        }
        assertTrue(error.message!!.contains("inattendue"))
    }

    @Test
    fun `an empty area yields no ways rather than an error`() {
        val ways = OverpassClient(Recorder("""{"elements":[]}""")).fetch(center, 1000.0)
        assertTrue(ways.isEmpty())
    }
}

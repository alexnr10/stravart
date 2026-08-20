package com.stravart.core.gpx

import com.stravart.core.geo.LatLon
import com.stravart.core.route.Fidelity
import com.stravart.core.route.GeneratedRoute
import com.stravart.core.routing.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class GpxWriterTest {

    private val points = listOf(
        LatLon(48.8566, 2.3522),
        LatLon(48.8576, 2.3532),
        LatLon(48.8586, 2.3512),
        LatLon(48.8566, 2.3522),
    )

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    @Test
    fun `output is well formed GPX 1_1 with every point`() {
        val xml = GpxWriter.write(points, name = "Cœur de Paris", activityType = "running")
        val doc = parse(xml)

        val root = doc.documentElement
        assertEquals("gpx", root.localName)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)

        val trkpts = doc.getElementsByTagName("trkpt")
        assertEquals(points.size, trkpts.length)

        val first = trkpts.item(0) as Element
        assertEquals(48.856600, first.getAttribute("lat").toDouble(), 1e-6)
        assertEquals(2.352200, first.getAttribute("lon").toDouble(), 1e-6)

        assertEquals(1, doc.getElementsByTagName("trk").length)
        assertEquals("running", doc.getElementsByTagName("type").item(0).textContent)
    }

    @Test
    fun `elevations are written when available`() {
        val xml = GpxWriter.write(
            points,
            elevations = listOf(35.0, 41.5, 38.2, 35.0),
            name = "Test",
            activityType = "cycling",
        )
        val eles = parse(xml).getElementsByTagName("ele")
        assertEquals(points.size, eles.length)
        assertEquals(41.5, eles.item(1).textContent.toDouble(), 1e-6)
    }

    @Test
    fun `points have no ele tag when elevation is unknown`() {
        val xml = GpxWriter.write(points, name = "Test", activityType = "running")
        assertEquals(0, parse(xml).getElementsByTagName("ele").length)
    }

    @Test
    fun `bounds cover the whole track`() {
        val xml = GpxWriter.write(points, name = "Test", activityType = "running")
        val bounds = parse(xml).getElementsByTagName("bounds").item(0) as Element
        assertEquals(48.8566, bounds.getAttribute("minlat").toDouble(), 1e-6)
        assertEquals(48.8586, bounds.getAttribute("maxlat").toDouble(), 1e-6)
        assertEquals(2.3512, bounds.getAttribute("minlon").toDouble(), 1e-6)
        assertEquals(2.3532, bounds.getAttribute("maxlon").toDouble(), 1e-6)
    }

    @Test
    fun `special characters in the name are escaped`() {
        val xml = GpxWriter.write(points, name = "Rouen & <Seine>", activityType = "running")
        assertFalse(xml.contains("<Seine>"))
        assertTrue(xml.contains("&amp;"))
        assertEquals("Rouen & <Seine>", parse(xml).getElementsByTagName("name").item(0).textContent)
    }

    @Test
    fun `route metadata ends up in the description`() {
        val route = GeneratedRoute(
            points = points,
            elevations = null,
            idealShape = points,
            distanceMeters = 10_250.0,
            ascentMeters = 88.0,
            fidelity = Fidelity(42.0, 130.0, 78),
            overlapRatio = 0.04,
            removedSpurs = 1,
            activity = ActivityType.BIKE,
            engineName = "BRouter",
            snappedToRoads = true,
            attempts = 2,
            name = "Cœur 10 km",
        )
        val desc = parse(GpxWriter.write(route)).getElementsByTagName("desc").item(0).textContent
        assertTrue(desc, desc.contains("10.25 km"))
        assertTrue(desc, desc.contains("88 m D+"))
        assertTrue(desc, desc.contains("78 %"))
    }

    @Test
    fun `file names are slugified`() {
        assertEquals("coeur-10-km.gpx", GpxWriter.fileName("Cœur 10 km"))
        assertEquals("foret-de-fontainebleau.gpx", GpxWriter.fileName("Forêt de Fontainebleau"))
        assertEquals("stravart-parcours.gpx", GpxWriter.fileName("///"))
        assertTrue(GpxWriter.fileName("a".repeat(200)).length <= 64)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a single point is not a track`() {
        GpxWriter.write(points.take(1), name = "Test", activityType = "running")
    }
}

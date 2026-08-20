package com.stravart.core.geocode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocoderTest {

    private val json = """
        [{"lat":"48.8566969","lon":"2.3514616","display_name":"Paris, Île-de-France, France"},
         {"lat":"45.7578137","lon":"4.8320114","display_name":"Lyon, Métropole de Lyon, France"}]
    """.trimIndent()

    @Test
    fun `parses search results`() {
        val places = NominatimGeocoder(http = { json }).search("paris")
        assertEquals(2, places.size)
        assertEquals("Paris, Île-de-France, France", places[0].name)
        assertEquals(48.8566969, places[0].location.lat, 1e-7)
        assertEquals(4.8320114, places[1].location.lon, 1e-7)
    }

    @Test
    fun `query and language are url encoded`() {
        var seen: String? = null
        NominatimGeocoder(http = { url -> seen = url; json }, language = "fr").search("gare de l'est")
        assertTrue(seen!!, seen!!.contains("q=gare+de+l%27est"))
        assertTrue(seen!!, seen!!.contains("accept-language=fr"))
        assertTrue(seen!!, seen!!.contains("format=jsonv2"))
    }

    @Test
    fun `a blank query performs no request`() {
        val places = NominatimGeocoder(http = { error("le réseau ne doit pas être sollicité") }).search("  ")
        assertTrue(places.isEmpty())
    }

    @Test
    fun `malformed entries are skipped`() {
        val partial = """[{"lat":"48.0","display_name":"sans longitude"},{"lat":"1.0","lon":"2.0","display_name":"ok"}]"""
        assertEquals(1, NominatimGeocoder(http = { partial }).search("x").size)
    }

    @Test
    fun `reverse geocoding returns null instead of failing`() {
        val geocoder = NominatimGeocoder(http = { throw java.io.IOException("hors ligne") })
        assertNull(geocoder.reverse(com.stravart.core.geo.LatLon(48.0, 2.0)))
    }
}

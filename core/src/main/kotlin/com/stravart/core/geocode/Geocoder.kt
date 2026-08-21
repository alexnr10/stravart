package com.stravart.core.geocode

import com.stravart.core.geo.LatLon
import com.stravart.core.net.HttpClient
import com.stravart.core.net.JdkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** Un lieu trouvé par la recherche d'adresse. */
data class Place(val name: String, val location: LatLon)

class GeocodingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Recherche d'adresse via [Nominatim](https://nominatim.org).
 *
 * Le service public impose une requête par seconde et un User-Agent identifiable :
 * l'appelant doit donc temporiser la saisie plutôt que d'interroger à chaque frappe.
 */
class NominatimGeocoder(
    private val http: HttpClient = JdkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val language: String = Locale.getDefault().language,
) {

    fun search(query: String, limit: Int = 6): List<Place> {
        if (query.isBlank()) return emptyList()
        val url = "${baseUrl.trimEnd('/')}/search?format=jsonv2&limit=$limit" +
            "&accept-language=${encode(language)}&q=${encode(query)}"
        val body = request(url)

        return try {
            Json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val lat = obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val name = obj["display_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Place(name, LatLon(lat, lon))
            }
        } catch (e: Exception) {
            throw GeocodingException("Réponse inattendue du service d'adresses.", e)
        }
    }

    /** Nom lisible du lieu situé aux coordonnées données, ou `null` s'il est inconnu. */
    fun reverse(location: LatLon): String? {
        val url = "${baseUrl.trimEnd('/')}/reverse?format=jsonv2" +
            "&accept-language=${encode(language)}" +
            "&lat=${format(location.lat)}&lon=${format(location.lon)}"
        return try {
            Json.parseToJsonElement(request(url)).jsonObject["display_name"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    private fun request(url: String): String = try {
        http.get(url)
    } catch (e: IOException) {
        throw GeocodingException("Service d'adresses injoignable : ${e.message}", e)
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun format(value: Double) = String.format(Locale.ROOT, "%.6f", value)

    companion object {
        const val DEFAULT_BASE_URL = "https://nominatim.openstreetmap.org"
    }
}

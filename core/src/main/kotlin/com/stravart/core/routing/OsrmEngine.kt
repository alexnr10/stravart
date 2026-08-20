package com.stravart.core.routing

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.net.HttpClient
import com.stravart.core.net.JdkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Locale

/**
 * Moteur [OSRM](https://project-osrm.org).
 *
 * Le serveur de démonstration public ne sert que le profil voiture ; on peut pointer
 * [baseUrl] vers une instance auto-hébergée servant `foot` ou `bike` depuis les
 * réglages de l'application.
 */
class OsrmEngine(
    private val http: HttpClient = JdkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val profileOverride: String? = null,
) : RoutingEngine {

    override val id = "osrm"
    override val displayName = "OSRM"
    override val snapsToRoads = true
    override val maxWaypoints = 25

    override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
        if (waypoints.size < 2) throw RoutingException("Il faut au moins deux points de passage.")
        val profile = profileOverride ?: profileFor(activity)
        val coords = waypoints.joinToString(";") { format(it.lon) + "," + format(it.lat) }
        val url = "${baseUrl.trimEnd('/')}/route/v1/$profile/$coords" +
            "?overview=full&geometries=geojson&continue_straight=false"

        val body = try {
            http.get(url)
        } catch (e: IOException) {
            throw RoutingException("Serveur OSRM injoignable : ${e.message}", e)
        }

        val root = try {
            JSON.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw RoutingException("Réponse OSRM inattendue : ${body.trim().take(200)}", e)
        }

        val code = root["code"]?.jsonPrimitive?.content
        if (code != null && code != "Ok") {
            val message = root["message"]?.jsonPrimitive?.content ?: code
            throw RoutingException("OSRM : $message")
        }

        val route = (root["routes"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw RoutingException("OSRM n'a renvoyé aucun itinéraire.")
        val coordinates = route["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
            ?: throw RoutingException("OSRM n'a renvoyé aucune géométrie.")

        val points = coordinates.map {
            val pair = it.jsonArray
            LatLon(lat = pair[1].jsonPrimitive.content.toDouble(), lon = pair[0].jsonPrimitive.content.toDouble())
        }
        if (points.size < 2) throw RoutingException("Itinéraire OSRM trop court pour être exploité.")

        val distance = route["distance"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: Geo.pathLength(points)
        return RoutedPath(points = points, distanceMeters = distance)
    }

    private fun format(value: Double) = String.format(Locale.ROOT, "%.6f", value)

    companion object {
        const val DEFAULT_BASE_URL = "https://router.project-osrm.org"

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun profileFor(activity: ActivityType): String = when (activity) {
            ActivityType.RUN -> "foot"
            ActivityType.BIKE -> "bike"
        }
    }
}

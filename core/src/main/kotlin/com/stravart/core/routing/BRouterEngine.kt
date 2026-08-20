package com.stravart.core.routing

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.net.HttpClient
import com.stravart.core.net.HttpException
import com.stravart.core.net.JdkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Locale

/**
 * Moteur s'appuyant sur [BRouter](https://brouter.de) : gratuit, sans clé d'API,
 * et pensé pour le vélo et la randonnée (donc pour ce que nous faisons).
 *
 * Le serveur public est tenu par la communauté : on limite le nombre d'appels par
 * génération et on s'identifie via un User-Agent explicite.
 */
class BRouterEngine(
    private val http: HttpClient = JdkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** Profil imposé ; si `null`, on prend celui associé à l'activité. */
    private val profileOverride: String? = null,
) : RoutingEngine {

    override val id = "brouter"
    override val displayName = "BRouter (routes et chemins)"
    override val snapsToRoads = true

    /**
     * Resserrer les points de passage colle le tracé à la forme voulue, et ne coûte
     * pas plus cher au serveur : découper en tronçons courts lui épargne justement
     * les longues recherches de chemin. La borne protège surtout la longueur de
     * l'URL et laisse de la marge sous les limites du service public.
     */
    override val maxWaypoints = 80

    override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
        if (waypoints.size < 2) throw RoutingException("Il faut au moins deux points de passage.")
        val profiles = profileOverride?.let { listOf(it) } ?: profilesFor(activity)

        var current = waypoints
        var lastError: Exception? = null

        for (round in 0..MAX_REDUCTIONS) {
            for (profile in profiles) {
                try {
                    return requestRoute(current, profile)
                } catch (e: Exception) {
                    lastError = e
                    // Inutile d'insister avec un autre profil quand l'échec vient du
                    // tracé lui-même (point isolé, waypoint en pleine forêt) : seule
                    // une erreur du serveur peut venir du profil demandé.
                    if (!looksLikeProfileIssue(e)) break
                }
            }

            // Dernier recours : réessayer avec deux fois moins de points de passage.
            // Cela rattrape aussi bien un serveur qui refuse une requête trop longue
            // qu'un point de passage isolé qui rendait le tracé introuvable. On perd
            // en fidélité à la forme, mais on rend un parcours.
            val reduced = halve(current)
            if (reduced.size == current.size) break
            current = reduced
        }

        throw RoutingException(
            "BRouter : ${lastError?.message ?: "itinéraire introuvable"}",
            lastError,
        )
    }

    /** Un point de passage sur deux, extrémités conservées. */
    private fun halve(waypoints: List<LatLon>): List<LatLon> {
        if (waypoints.size <= MIN_WAYPOINTS_AFTER_REDUCTION) return waypoints
        val kept = waypoints.filterIndexed { index, _ -> index % 2 == 0 }
        return if (kept.last() == waypoints.last()) kept else kept + waypoints.last()
    }

    private fun looksLikeProfileIssue(e: Exception): Boolean =
        e is HttpException || e.cause is HttpException ||
            e.message?.contains("profil", ignoreCase = true) == true

    private fun requestRoute(waypoints: List<LatLon>, profile: String): RoutedPath {
        val lonlats = waypoints.joinToString("|") { format(it.lon) + "," + format(it.lat) }
        val url = buildString {
            append(baseUrl)
            append("?lonlats=").append(urlEncode(lonlats))
            append("&profile=").append(urlEncode(profile))
            append("&alternativeidx=0&format=geojson")
        }

        val body = try {
            http.get(url)
        } catch (e: IOException) {
            throw RoutingException("Serveur BRouter injoignable : ${e.message}", e)
        }
        return parse(body)
    }

    private fun parse(body: String): RoutedPath {
        // En cas d'échec fonctionnel BRouter répond en texte brut, pas en JSON.
        val root = try {
            JSON.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw RoutingException("Réponse BRouter inattendue : ${body.trim().take(200)}", e)
        }

        val feature = (root["features"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw RoutingException("BRouter n'a renvoyé aucun itinéraire.")
        val coordinates = feature["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
            ?: throw RoutingException("BRouter n'a renvoyé aucune géométrie.")

        val points = ArrayList<LatLon>(coordinates.size)
        val elevations = ArrayList<Double>(coordinates.size)
        var hasElevation = true
        for (element in coordinates) {
            val triple = element.jsonArray
            points += LatLon(lat = triple[1].jsonPrimitive.double(), lon = triple[0].jsonPrimitive.double())
            if (triple.size > 2) elevations += triple[2].jsonPrimitive.double() else hasElevation = false
        }
        if (points.size < 2) throw RoutingException("Itinéraire BRouter trop court pour être exploité.")

        val properties = feature["properties"] as? JsonObject
        val distance = properties?.numberLike("track-length") ?: Geo.pathLength(points)
        val ascent = properties?.numberLike("filtered ascend") ?: properties?.numberLike("plain-ascend")

        return RoutedPath(
            points = points,
            distanceMeters = distance,
            elevations = if (hasElevation) elevations else null,
            ascentMeters = ascent,
        )
    }

    private fun JsonObject.numberLike(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()

    private fun JsonPrimitive.double(): Double =
        content.toDoubleOrNull() ?: throw RoutingException("Coordonnée illisible : $content")

    private fun format(value: Double) = String.format(Locale.ROOT, "%.6f", value)

    private fun urlEncode(value: String) =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        const val DEFAULT_BASE_URL = "https://brouter.de/brouter"

        /** Nombre de fois où l'on réessaie avec moitié moins de points de passage. */
        private const val MAX_REDUCTIONS = 2

        private const val MIN_WAYPOINTS_AFTER_REDUCTION = 8

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Profils BRouter par activité, du plus adapté au plus tolérant : si le serveur
         * ne connaît pas le premier, on retombe sur le suivant.
         */
        fun profilesFor(activity: ActivityType): List<String> = when (activity) {
            ActivityType.RUN -> listOf("hiking-beta", "trekking", "shortest")
            ActivityType.BIKE -> listOf("trekking", "fastbike", "shortest")
        }
    }
}

package com.stravart.core.osm

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.net.HttpClient
import com.stravart.core.net.JdkHttpClient
import com.stravart.core.placement.RoadWay
import com.stravart.core.routing.ActivityType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class OverpassException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Récupère le réseau viaire d'un secteur auprès d'[Overpass](https://overpass-api.de).
 *
 * La recherche de placement a besoin des rues elles-mêmes, pas d'itinéraires : c'est
 * ce qui permet d'évaluer des milliers de candidats sans solliciter le routeur. Une
 * seule requête suffit par recherche.
 *
 * Le service est public, partagé et gratuit. Deux précautions en découlent : le
 * secteur demandé est **borné** — au-delà, mieux vaut refuser que d'expédier une
 * requête qui sera de toute façon rejetée après une longue attente — et le résultat
 * a vocation à être gardé en mémoire entre deux recherches voisines.
 */
class OverpassClient(
    private val http: HttpClient = JdkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutSeconds: Int = 60,
) {

    /**
     * @param radiusMeters demi-côté du carré demandé, plafonné à [MAX_RADIUS_METERS].
     * @throws OverpassException si le secteur est trop vaste, le service indisponible
     *   ou la réponse illisible.
     */
    fun fetch(
        center: LatLon,
        radiusMeters: Double,
        activity: ActivityType = ActivityType.RUN,
    ): List<RoadWay> {
        require(radiusMeters > 0) { "le rayon doit être positif" }
        if (radiusMeters > MAX_RADIUS_METERS) {
            throw OverpassException(
                "Secteur trop vaste : ${radiusMeters.toInt()} m demandés pour un maximum de " +
                    "${MAX_RADIUS_METERS.toInt()} m. Rapprochez le départ ou réduisez le rayon.",
            )
        }

        val south = Geo.offset(center, 0.0, -radiusMeters).lat
        val north = Geo.offset(center, 0.0, radiusMeters).lat
        val west = Geo.offset(center, -radiusMeters, 0.0).lon
        val east = Geo.offset(center, radiusMeters, 0.0).lon

        val body = request(query(south, west, north, east, activity))
        return parse(body)
    }

    private fun query(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        activity: ActivityType,
    ): String {
        val box = listOf(south, west, north, east)
            .joinToString(",") { String.format(Locale.ROOT, "%.6f", it) }
        // `out geom` livre les coordonnées avec chaque voie. C'est plus volumineux
        // qu'un renvoi de références, mais cela évite de recoudre nœuds et voies —
        // recouture dont la moindre erreur produirait des rues fantômes.
        return """
            [out:json][timeout:$timeoutSeconds];
            way["highway"~"^(${highwayFilter(activity)})$"]
               ["area"!="yes"]
               ["access"!~"^(private|no)$"]
               ($box);
            out geom;
        """.trimIndent()
    }

    private fun request(query: String): String = try {
        http.get("${baseUrl.trimEnd('/')}?data=${URLEncoder.encode(query, "UTF-8")}")
    } catch (e: IOException) {
        throw OverpassException("Réseau viaire indisponible : ${e.message}", e)
    }

    private fun parse(body: String): List<RoadWay> = try {
        val elements = Json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray.orEmpty()
        elements.mapNotNull { element ->
            val obj = element.jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "way") return@mapNotNull null
            val geometry = obj["geometry"]?.jsonArray ?: return@mapNotNull null
            val points = geometry.mapNotNull { node ->
                val n = node.jsonObject
                val lat = n["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val lon = n["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (lat == null || lon == null) null else LatLon(lat, lon)
            }
            // Une voie tronquée au bord du secteur peut n'avoir qu'un point : elle ne
            // porte alors aucune orientation et n'apprend rien.
            if (points.size < 2) null else RoadWay(points)
        }
    } catch (e: Exception) {
        throw OverpassException("Réponse inattendue du service de données cartographiques.", e)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://overpass-api.de/api/interpreter"

        /**
         * Demi-côté maximal du secteur.
         *
         * Quatre kilomètres font un carré de huit sur huit — de l'ordre d'un ou deux
         * arrondissements. Au-delà, le volume rendu devient déraisonnable pour un
         * téléphone en itinérance autant que pour un service partagé.
         */
        const val MAX_RADIUS_METERS = 4_000.0

        /**
         * Les voies retenues selon l'activité.
         *
         * À pied on emprunte les allées de parc et les chemins, à vélo on les évite et
         * l'on tolère de plus grands axes. Les voies rapides sont exclues des deux :
         * elles fausseraient la note en offrant de longues lignes bien orientées sur
         * lesquelles personne ne court.
         */
        fun highwayFilter(activity: ActivityType): String = when (activity) {
            ActivityType.RUN -> "footway|path|pedestrian|steps|track|living_street|" +
                "residential|unclassified|service|cycleway|tertiary|secondary"
            ActivityType.BIKE -> "cycleway|path|track|living_street|residential|" +
                "unclassified|service|tertiary|secondary|primary"
        }
    }
}

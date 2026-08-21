package com.stravart.core.gpx

import com.stravart.core.geo.LatLon
import com.stravart.core.route.GeneratedRoute
import java.time.Instant
import java.text.Normalizer
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Écrit un fichier GPX 1.1 importable comme parcours dans Garmin Connect
 * (Entraînement › Parcours › Importer) ou comme itinéraire dans Strava
 * (Mes itinéraires › Importer un GPX).
 *
 * Le tracé est écrit en `<trk>` sans horodatage des points : c'est un parcours à
 * suivre, pas une activité déjà réalisée. Les deux plateformes l'acceptent ainsi.
 */
object GpxWriter {

    private const val CREATOR = "StravArt"

    /** Ligatures que la décomposition Unicode ne sait pas séparer. */
    private val LIGATURES = mapOf("œ" to "oe", "æ" to "ae", "ß" to "ss", "ø" to "o", "đ" to "d")

    fun write(route: GeneratedRoute, description: String? = null): String = write(
        points = route.points,
        elevations = route.elevations,
        name = route.name,
        activityType = route.activity.gpxType,
        description = description ?: buildDescription(route),
    )

    fun write(
        points: List<LatLon>,
        elevations: List<Double>? = null,
        name: String,
        activityType: String,
        description: String? = null,
        time: Instant = Instant.now(),
    ): String {
        require(points.size >= 2) { "un GPX demande au moins 2 points" }
        require(elevations == null || elevations.size == points.size) {
            "le nombre d'altitudes doit correspondre au nombre de points"
        }

        val sb = StringBuilder(points.size * 64)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="$CREATOR" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
                """http://www.topografix.com/GPX/1/1/gpx.xsd">""",
        ).append('\n')

        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escape(name)).append("</name>\n")
        if (!description.isNullOrBlank()) {
            sb.append("    <desc>").append(escape(description)).append("</desc>\n")
        }
        sb.append("    <time>").append(time.truncatedTo(ChronoUnit.SECONDS).toString()).append("</time>\n")
        appendBounds(sb, points)
        sb.append("  </metadata>\n")

        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(name)).append("</name>\n")
        sb.append("    <type>").append(escape(activityType)).append("</type>\n")
        sb.append("    <trkseg>\n")
        points.forEachIndexed { index, p ->
            sb.append("      <trkpt lat=\"").append(coord(p.lat))
                .append("\" lon=\"").append(coord(p.lon)).append('"')
            val ele = elevations?.get(index)
            if (ele == null) {
                sb.append("/>\n")
            } else {
                sb.append("><ele>").append(String.format(Locale.ROOT, "%.1f", ele)).append("</ele></trkpt>\n")
            }
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Transforme un nom de parcours en nom de fichier sûr, extension comprise.
     *
     * Le résultat est volontairement limité à l'ASCII : le fichier finit sur un
     * ordinateur, dans un e-mail ou sur une montre, et tout le monde ne s'entend pas
     * sur l'encodage des noms de fichiers.
     */
    fun fileName(name: String): String {
        val slug = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
            .let { text -> LIGATURES.entries.fold(text) { acc, (from, to) -> acc.replace(from, to) } }
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
        return (if (slug.isEmpty()) "stravart-parcours" else slug) + ".gpx"
    }

    private fun buildDescription(route: GeneratedRoute): String = buildString {
        append(String.format(Locale.ROOT, "%.2f km", route.distanceKm))
        route.ascentMeters?.let { append(String.format(Locale.ROOT, " · %.0f m D+", it)) }
        append(" · ressemblance ").append(route.fidelity.score).append(" %")
        append(" · ").append(route.engineName)
        append(" · généré par StravArt")
    }

    private fun appendBounds(sb: StringBuilder, points: List<LatLon>) {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        sb.append("    <bounds minlat=\"").append(coord(minLat))
            .append("\" minlon=\"").append(coord(minLon))
            .append("\" maxlat=\"").append(coord(maxLat))
            .append("\" maxlon=\"").append(coord(maxLon))
            .append("\"/>\n")
    }

    private fun coord(value: Double) = String.format(Locale.ROOT, "%.6f", value)

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}

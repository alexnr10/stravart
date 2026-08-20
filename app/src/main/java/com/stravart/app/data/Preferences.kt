package com.stravart.app.data

import android.content.Context
import com.stravart.core.geo.LatLon
import com.stravart.core.routing.ActivityType
import com.stravart.core.shape.AnchorMode

/**
 * Mémorise les derniers réglages : rouvrir l'application sur la même ville, la
 * même distance et la même activité évite de tout ressaisir à chaque sortie.
 */
class Preferences(context: Context) {

    private val prefs = context.getSharedPreferences("stravart", Context.MODE_PRIVATE)

    var shapeId: String
        get() = prefs.getString(KEY_SHAPE, null) ?: "heart"
        set(value) = prefs.edit().putString(KEY_SHAPE, value).apply()

    var distanceKm: Float
        get() = prefs.getFloat(KEY_DISTANCE, 10f)
        set(value) = prefs.edit().putFloat(KEY_DISTANCE, value).apply()

    var activity: ActivityType
        get() = ActivityType.entries.firstOrNull { it.id == prefs.getString(KEY_ACTIVITY, null) }
            ?: ActivityType.RUN
        set(value) = prefs.edit().putString(KEY_ACTIVITY, value.id).apply()

    var anchorMode: AnchorMode
        get() = runCatching { AnchorMode.valueOf(prefs.getString(KEY_ANCHOR, "") ?: "") }
            .getOrDefault(AnchorMode.START)
        set(value) = prefs.edit().putString(KEY_ANCHOR, value.name).apply()

    var engineId: String
        get() = prefs.getString(KEY_ENGINE, null) ?: "brouter"
        set(value) = prefs.edit().putString(KEY_ENGINE, value).apply()

    var osrmUrl: String
        get() = prefs.getString(KEY_OSRM_URL, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_OSRM_URL, value).apply()

    var lastStart: LatLon?
        get() {
            if (!prefs.contains(KEY_START_LAT)) return null
            val lat = prefs.getFloat(KEY_START_LAT, 0f).toDouble()
            val lon = prefs.getFloat(KEY_START_LON, 0f).toDouble()
            return runCatching { LatLon(lat, lon) }.getOrNull()
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_START_LAT).remove(KEY_START_LON)
            } else {
                editor.putFloat(KEY_START_LAT, value.lat.toFloat())
                editor.putFloat(KEY_START_LON, value.lon.toFloat())
            }
            editor.apply()
        }

    var lastStartLabel: String?
        get() = prefs.getString(KEY_START_LABEL, null)
        set(value) = prefs.edit().putString(KEY_START_LABEL, value).apply()

    private companion object {
        const val KEY_SHAPE = "shape"
        const val KEY_DISTANCE = "distance_km"
        const val KEY_ACTIVITY = "activity"
        const val KEY_ANCHOR = "anchor"
        const val KEY_ENGINE = "engine"
        const val KEY_OSRM_URL = "osrm_url"
        const val KEY_START_LAT = "start_lat"
        const val KEY_START_LON = "start_lon"
        const val KEY_START_LABEL = "start_label"
    }
}

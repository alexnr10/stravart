package com.stravart.core.geo

/** Un point géographique en degrés décimaux (WGS84). */
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "latitude hors bornes: $lat" }
        require(lon in -180.0..180.0) { "longitude hors bornes: $lon" }
    }
}

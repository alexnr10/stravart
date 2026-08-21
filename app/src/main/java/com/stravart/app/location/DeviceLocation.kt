package com.stravart.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.stravart.core.geo.LatLon
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Position de l'appareil via le [LocationManager] du système.
 *
 * On s'en tient volontairement à l'API de la plateforme : pas de services Google
 * Play, donc une application qui fonctionne aussi sur les appareils dégooglisés,
 * et une permission facultative — la saisie d'adresse reste toujours possible.
 */
object DeviceLocation {

    /** Au-delà, une position mémorisée est jugée trop vieille pour un départ de sortie. */
    private const val MAX_AGE_MS = 5 * 60 * 1000L

    private const val LIVE_FIX_TIMEOUT_MS = 12_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Renvoie la position courante, ou `null` si elle reste introuvable.
     *
     * On commence par les positions déjà connues du système — instantané et sans
     * consommer le GPS — puis on n'allume un fournisseur que si aucune n'est assez
     * récente.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): LatLon? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        lastKnown(manager)?.let { return it.toLatLon() }

        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null

        return withTimeoutOrNull(LIVE_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location.toLatLon())
                    }

                    // Abstraite jusqu'à Android 9 : l'omettre planterait sur ces versions.
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onProviderEnabled(provider: String) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                runCatching {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? {
        val now = System.currentTimeMillis()
        return manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time <= MAX_AGE_MS }
            .maxByOrNull { it.time }
    }

    private fun Location.toLatLon() = LatLon(latitude, longitude)
}

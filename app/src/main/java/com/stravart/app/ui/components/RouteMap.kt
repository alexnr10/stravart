package com.stravart.app.ui.components

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stravart.core.geo.LatLon
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Carte OpenStreetMap affichant le parcours calculé et, en pointillés, la forme
 * visée — voir l'écart entre les deux est ce qui permet de juger le résultat.
 */
@Composable
fun RouteMap(
    start: LatLon?,
    route: List<LatLon>,
    idealShape: List<LatLon>,
    routeColor: Color,
    shapeColor: Color,
    startTitle: String,
    onLongPress: (LatLon) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val mapView = remember { MapView(context) }
    val routeLine = remember { Polyline(mapView) }
    val shapeLine = remember { Polyline(mapView) }
    val startMarker = remember { Marker(mapView) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(13.0)
                isTilesScaledToDpi = true

                shapeLine.outlinePaint.apply {
                    strokeWidth = 6f
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                    pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
                }
                routeLine.outlinePaint.apply {
                    strokeWidth = 14f
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                val events = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p ?: return false
                        currentOnLongPress(LatLon(p.latitude, p.longitude))
                        return true
                    }
                })

                overlays.add(events)
                overlays.add(shapeLine)
                overlays.add(routeLine)
                overlays.add(startMarker)
                overlays.add(CopyrightOverlay(context))
            }
        },
        update = { map ->
            shapeLine.outlinePaint.color = shapeColor.toArgb()
            routeLine.outlinePaint.color = routeColor.toArgb()
            shapeLine.setPoints(idealShape.toGeoPoints())
            routeLine.setPoints(route.toGeoPoints())

            startMarker.isEnabled = start != null
            startMarker.title = startTitle
            start?.let { startMarker.position = GeoPoint(it.lat, it.lon) }

            map.invalidate()
        },
    )

    // Cadrer sur le tracé quand son emprise change réellement. Se contenter des
    // listes comme clés relancerait le cadrage à chaque recomposition, et annulerait
    // le déplacement que l'utilisateur vient de faire à la main.
    val hasRoute = route.size >= 2
    val frame = Frame.of(if (hasRoute) route else idealShape)
    LaunchedEffect(frame, start) {
        if (frame != null) {
            // On anime l'arrivée d'un parcours, mais pas l'aperçu : celui-ci se
            // recadre à chaque cran du curseur de distance, et l'animation traînerait.
            mapView.post { mapView.zoomToBoundingBox(frame.toBoundingBox(), hasRoute, MAP_PADDING_PX) }
        } else if (start != null) {
            mapView.post { mapView.controller.animateTo(GeoPoint(start.lat, start.lon)) }
        }
    }
}

private const val MAP_PADDING_PX = 80

/** Emprise d'un tracé, comparable par valeur : deux emprises identiques ne recadrent pas. */
private data class Frame(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    fun toBoundingBox() = BoundingBox(maxLat, maxLon, minLat, minLon)

    companion object {
        fun of(points: List<LatLon>): Frame? {
            if (points.size < 2) return null
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            for (p in points) {
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lon < minLon) minLon = p.lon
                if (p.lon > maxLon) maxLon = p.lon
            }
            return Frame(minLat, minLon, maxLat, maxLon)
        }
    }
}

private fun List<LatLon>.toGeoPoints(): List<GeoPoint> = map { GeoPoint(it.lat, it.lon) }

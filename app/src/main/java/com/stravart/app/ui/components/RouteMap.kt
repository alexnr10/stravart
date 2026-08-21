package com.stravart.app.ui.components

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
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
import androidx.compose.ui.platform.LocalDensity
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
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

/** Couleurs des tracés, groupées pour ne pas allonger la liste des paramètres. */
data class RouteMapColors(
    val route: Color,
    val casing: Color,
    val shape: Color,
    val stray: Color,
    val markerRing: Color,
    val markerCore: Color,
)

/**
 * Carte OpenStreetMap affichant le parcours calculé et, en pointillés, la forme
 * visée — voir l'écart entre les deux est ce qui permet de juger le résultat.
 *
 * @param recenterRequest incrémenter cette valeur recadre sur le tracé. La carte
 *   garde son `MapView` pour elle ; passer un compteur suffit à la piloter de
 *   l'extérieur sans exposer la vue ni son cycle de vie.
 */
@Composable
fun RouteMap(
    start: LatLon?,
    route: List<LatLon>,
    idealShape: List<LatLon>,
    unfollowed: List<List<LatLon>>,
    colors: RouteMapColors,
    startTitle: String,
    onLongPress: (LatLon) -> Unit,
    modifier: Modifier = Modifier,
    recenterRequest: Int = 0,
    /**
     * Change dès que la place laissée à la carte change. Le cadrage en dépend :
     * une emprise ajustée à une carte haute déborde d'une carte basse.
     */
    layoutKey: Any = Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density
    val framePadding = (MAP_PADDING_DP * density).roundToInt()
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val mapView = remember { MapView(context) }
    // Le gainage passe sous l'itinéraire : c'est lui qui le détache des rues, dont
    // les tuiles OpenStreetMap portent déjà des traits de largeur comparable.
    val casingLine = remember { Polyline(mapView) }
    val routeLine = remember { Polyline(mapView) }
    val shapeLine = remember { Polyline(mapView) }
    val startMarker = remember { Marker(mapView) }
    // Les portions non suivies vont et viennent d'un calcul à l'autre : on garde un
    // petit vivier de polylignes plutôt que d'en recréer à chaque recomposition.
    val strayLines = remember { mutableListOf<Polyline>() }
    // Le marqueur ne dépend que des couleurs : le redessiner à chaque mise à jour
    // reviendrait à allouer un bitmap par cran du curseur de distance.
    val startIcon = remember(colors.markerRing, colors.markerCore, density) {
        startDot(context.resources, colors.markerRing.toArgb(), colors.markerCore.toArgb(), density)
    }

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
                    strokeWidth = 2.5f.dpPx(density)
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                    pathEffect = DashPathEffect(
                        floatArrayOf(7f.dpPx(density), 7f.dpPx(density)),
                        0f,
                    )
                }
                casingLine.outlinePaint.apply {
                    strokeWidth = 8f.dpPx(density)
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                routeLine.outlinePaint.apply {
                    strokeWidth = 5f.dpPx(density)
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

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
                overlays.add(casingLine)
                overlays.add(routeLine)
                overlays.add(startMarker)
            }
        },
        update = { map ->
            shapeLine.outlinePaint.color = colors.shape.toArgb()
            casingLine.outlinePaint.color = colors.casing.toArgb()
            routeLine.outlinePaint.color = colors.route.toArgb()

            val points = route.toGeoPoints()
            shapeLine.setPoints(idealShape.toGeoPoints())
            casingLine.setPoints(points)
            routeLine.setPoints(points)

            syncStrayLines(map, strayLines, unfollowed, colors.stray.toArgb(), density)

            startMarker.isEnabled = start != null
            startMarker.title = startTitle
            startMarker.icon = startIcon
            start?.let { startMarker.position = GeoPoint(it.lat, it.lon) }

            map.invalidate()
        },
    )

    // Cadrer sur le tracé quand son emprise change réellement. Se contenter des
    // listes comme clés relancerait le cadrage à chaque recomposition, et annulerait
    // le déplacement que l'utilisateur vient de faire à la main.
    val hasRoute = route.size >= 2
    val frame = Frame.of(if (hasRoute) route else idealShape)
    LaunchedEffect(frame, start, recenterRequest, layoutKey) {
        if (frame != null) {
            // On anime l'arrivée d'un parcours, mais pas l'aperçu : celui-ci se
            // recadre à chaque cran du curseur de distance, et l'animation traînerait.
            mapView.post { mapView.zoomToBoundingBox(frame.toBoundingBox(), hasRoute, framePadding) }
        } else if (start != null) {
            mapView.post { mapView.controller.animateTo(GeoPoint(start.lat, start.lon)) }
        }
    }
}

/**
 * Ajuste le vivier de polylignes d'alerte au nombre de portions à montrer.
 *
 * Ces portions suivent la *forme*, pas l'itinéraire : elles disent « voilà ce que le
 * réseau n'a pas permis de dessiner », ce qui vaut mieux que de laisser l'utilisateur
 * soupçonner un défaut de calcul.
 */
private fun syncStrayLines(
    map: MapView,
    pool: MutableList<Polyline>,
    stretches: List<List<LatLon>>,
    color: Int,
    density: Float,
) {
    while (pool.size < stretches.size) {
        val line = Polyline(map).apply {
            outlinePaint.style = Paint.Style.STROKE
            outlinePaint.strokeWidth = 6f.dpPx(density)
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.isAntiAlias = true
        }
        pool += line
        map.overlays.add(line)
    }
    while (pool.size > stretches.size) {
        map.overlays.remove(pool.removeAt(pool.lastIndex))
    }
    stretches.forEachIndexed { index, points ->
        pool[index].outlinePaint.color = color
        pool[index].setPoints(points.toGeoPoints())
    }
}

/**
 * Marqueur de départ : une pastille et non une épingle.
 *
 * L'épingle désigne un point situé sous sa pointe, ce qui oblige à viser au jugé ;
 * le départ est un endroit précis du tracé, et une pastille centrée le montre là où
 * il est. L'anneau clair la détache d'un fond de carte quelconque.
 */
private fun startDot(resources: Resources, ring: Int, core: Int, density: Float): BitmapDrawable {
    val size = (18f * density).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    paint.color = ring
    canvas.drawCircle(center, center, center, paint)
    paint.color = core
    canvas.drawCircle(center, center, 11f * density / 2f, paint)
    return BitmapDrawable(resources, bitmap)
}

private fun Float.dpPx(density: Float): Float = this * density

/**
 * Marge autour du tracé, en dp et non en pixels : quatre-vingts pixels faisaient
 * vingt-sept dp sur un écran dense et deux fois plus sur un écran ordinaire.
 */
private const val MAP_PADDING_DP = 24

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

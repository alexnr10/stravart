package com.stravart.app.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stravart.app.BuildConfig
import com.stravart.app.R
import com.stravart.app.data.Preferences
import com.stravart.app.location.DeviceLocation
import com.stravart.core.geo.LatLon
import com.stravart.core.geocode.NominatimGeocoder
import com.stravart.core.geocode.Place
import com.stravart.core.net.JdkHttpClient
import com.stravart.core.route.GeneratedRoute
import com.stravart.core.route.RouteGenerator
import com.stravart.core.route.RouteRequest
import com.stravart.core.routing.ActivityType
import com.stravart.core.routing.BRouterEngine
import com.stravart.core.routing.OsrmEngine
import com.stravart.core.routing.RoutingEngine
import com.stravart.core.routing.StraightLineEngine
import com.stravart.core.shape.AnchorMode
import com.stravart.core.shape.ShapeLibrary
import com.stravart.core.shape.ShapePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Identifiant de la forme dessinée à la main, par opposition aux formes du catalogue. */
const val CUSTOM_SHAPE_ID = "custom"

/** Moteurs de routage proposés dans les réglages. */
enum class EngineChoice(val id: String, @StringRes val labelRes: Int) {
    BROUTER("brouter", R.string.engine_brouter),
    OSRM("osrm", R.string.engine_osrm),
    STRAIGHT("straight", R.string.engine_straight);

    companion object {
        fun fromId(id: String): EngineChoice = entries.firstOrNull { it.id == id } ?: BROUTER
    }
}

data class RouteUiState(
    val shapeId: String = "heart",
    val customShape: ShapePath? = null,
    val distanceKm: Float = 10f,
    val activity: ActivityType = ActivityType.RUN,
    val rotationDeg: Float = 0f,
    val mirrored: Boolean = false,
    val anchorMode: AnchorMode = AnchorMode.START,
    val engine: EngineChoice = EngineChoice.BROUTER,
    val osrmUrl: String = "",
    val start: LatLon? = null,
    val startLabel: String? = null,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Place> = emptyList(),
    val locating: Boolean = false,
    val generating: Boolean = false,
    val progress: String? = null,
    val route: GeneratedRoute? = null,
    val message: String? = null,
) {
    val distanceMeters: Double get() = distanceKm.toDouble() * 1000.0

    val shapeLabel: String?
        get() = if (shapeId == CUSTOM_SHAPE_ID) null else ShapeLibrary.byId(shapeId)?.label

    /** La forme effectivement utilisée, ou `null` si le dessin personnalisé manque. */
    val shape: ShapePath?
        get() = if (shapeId == CUSTOM_SHAPE_ID) customShape else ShapeLibrary.byId(shapeId)?.path

    val canGenerate: Boolean get() = start != null && shape != null && !generating
}

class RouteViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = Preferences(application)
    private val http = JdkHttpClient(userAgent = "StravArt/${BuildConfig.VERSION_NAME} (Android)")
    private val geocoder = NominatimGeocoder(http)

    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var generateJob: Job? = null

    private fun restoreState() = RouteUiState(
        shapeId = preferences.shapeId,
        distanceKm = preferences.distanceKm,
        activity = preferences.activity,
        anchorMode = preferences.anchorMode,
        engine = EngineChoice.fromId(preferences.engineId),
        osrmUrl = preferences.osrmUrl,
        start = preferences.lastStart,
        startLabel = preferences.lastStartLabel,
    )

    // --- Réglages du parcours ------------------------------------------------

    fun selectShape(id: String) {
        preferences.shapeId = id
        _state.update { it.copy(shapeId = id, route = null) }
    }

    fun setCustomShape(shape: ShapePath) {
        preferences.shapeId = CUSTOM_SHAPE_ID
        _state.update { it.copy(shapeId = CUSTOM_SHAPE_ID, customShape = shape, route = null) }
    }

    fun setDistance(km: Float) {
        preferences.distanceKm = km
        _state.update { it.copy(distanceKm = km) }
    }

    fun setActivity(activity: ActivityType) {
        preferences.activity = activity
        _state.update { it.copy(activity = activity) }
    }

    fun setRotation(degrees: Float) = _state.update { it.copy(rotationDeg = degrees) }

    fun setMirrored(mirrored: Boolean) = _state.update { it.copy(mirrored = mirrored) }

    fun setAnchorMode(mode: AnchorMode) {
        preferences.anchorMode = mode
        _state.update { it.copy(anchorMode = mode) }
    }

    fun setEngine(choice: EngineChoice) {
        preferences.engineId = choice.id
        _state.update { it.copy(engine = choice) }
    }

    fun setOsrmUrl(url: String) {
        preferences.osrmUrl = url
        _state.update { it.copy(osrmUrl = url) }
    }

    // --- Point de départ -----------------------------------------------------

    fun setStart(location: LatLon, label: String? = null) {
        preferences.lastStart = location
        preferences.lastStartLabel = label
        _state.update {
            it.copy(start = location, startLabel = label, results = emptyList(), query = "", route = null)
        }
        if (label == null) resolveLabel(location)
    }

    private fun resolveLabel(location: LatLon) {
        viewModelScope.launch {
            val label = withContext(Dispatchers.IO) { runCatching { geocoder.reverse(location) }.getOrNull() }
            if (label != null && _state.value.start == location) {
                preferences.lastStartLabel = label
                _state.update { it.copy(startLabel = label) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            // Nominatim tolère une requête par seconde : on attend la fin de la saisie.
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            val outcome = withContext(Dispatchers.IO) { runCatching { geocoder.search(query) } }
            outcome
                .onSuccess { places ->
                    _state.update {
                        it.copy(
                            searching = false,
                            results = places,
                            message = if (places.isEmpty()) string(R.string.search_empty) else it.message,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            searching = false,
                            results = emptyList(),
                            message = string(R.string.search_failed, error.message.orEmpty()),
                        )
                    }
                }
        }
    }

    /** Appelée une fois l'autorisation de localisation accordée. */
    fun locateMe() {
        viewModelScope.launch {
            _state.update { it.copy(locating = true) }
            val location = DeviceLocation.current(getApplication())
            _state.update { it.copy(locating = false) }
            if (location == null) {
                _state.update { it.copy(message = string(R.string.location_unavailable)) }
            } else {
                setStart(location)
            }
        }
    }

    fun onLocationPermissionDenied() =
        _state.update { it.copy(locating = false, message = string(R.string.location_permission_needed)) }

    // --- Génération ----------------------------------------------------------

    fun generate() {
        val current = _state.value
        val start = current.start
        val shape = current.shape
        if (start == null || shape == null) {
            _state.update { it.copy(message = string(R.string.start_missing)) }
            return
        }

        val request = RouteRequest(
            shape = shape,
            start = start,
            distanceMeters = current.distanceMeters,
            activity = current.activity,
            rotationDeg = current.rotationDeg.toDouble(),
            mirrored = current.mirrored,
            anchorMode = current.anchorMode,
            name = routeName(current),
        )
        val engine = engineFor(current)

        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            _state.update { it.copy(generating = true, message = null, progress = string(R.string.generating)) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    RouteGenerator(engine).generate(request) { progress ->
                        _state.update { it.copy(progress = progress.message) }
                    }
                }
            }
            outcome
                .onSuccess { route ->
                    _state.update { it.copy(generating = false, progress = null, route = route) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            generating = false,
                            progress = null,
                            message = error.message ?: string(R.string.error_title),
                        )
                    }
                }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private fun engineFor(state: RouteUiState): RoutingEngine = when (state.engine) {
        EngineChoice.BROUTER -> BRouterEngine(http)
        EngineChoice.OSRM -> OsrmEngine(http, state.osrmUrl.ifBlank { OsrmEngine.DEFAULT_BASE_URL })
        EngineChoice.STRAIGHT -> StraightLineEngine
    }

    private fun routeName(state: RouteUiState): String {
        val shape = state.shapeLabel ?: string(R.string.shape_custom)
        return "$shape ${String.format(Locale.ROOT, "%.1f", state.distanceKm)} km"
    }

    private fun string(@StringRes resId: Int, vararg args: Any): String =
        if (args.isEmpty()) getApplication<Application>().getString(resId)
        else getApplication<Application>().getString(resId, *args)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
    }
}

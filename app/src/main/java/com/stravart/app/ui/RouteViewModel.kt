package com.stravart.app.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stravart.app.BuildConfig
import com.stravart.app.R
import com.stravart.app.data.Preferences
import com.stravart.app.location.DeviceLocation
import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.geocode.NominatimGeocoder
import com.stravart.core.osm.OverpassClient
import com.stravart.core.placement.GuidedRouteGenerator
import com.stravart.core.placement.PlacementSearchOptions
import com.stravart.core.placement.RoadSource
import com.stravart.core.placement.RoadWay
import com.stravart.core.geocode.Place
import com.stravart.core.net.JdkHttpClient
import com.stravart.core.route.GeneratedRoute
import com.stravart.core.route.RouteGenerator
import com.stravart.core.route.RouteRequest
import com.stravart.core.route.UnsuitableAreaException
import com.stravart.core.routing.ActivityType
import com.stravart.core.routing.BRouterEngine
import com.stravart.core.routing.OsrmEngine
import com.stravart.core.routing.RoutingEngine
import com.stravart.core.routing.StraightLineEngine
import com.stravart.core.shape.AnchorMode
import com.stravart.core.shape.ShapeLibrary
import com.stravart.core.shape.ShapeProjector
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

/**
 * Jusqu'où l'application a le droit de déplacer la forme pour mieux coller aux rues.
 *
 * La recherche d'orientation est le défaut : c'est la moins coûteuse et celle qui
 * rapporte le plus. Le déplacement du départ est un choix distinct, car il change la
 * promesse — on ne part plus forcément de chez soi.
 */
enum class PlacementMode(@StringRes val labelRes: Int) {
    NONE(R.string.placement_none),
    ROTATION(R.string.placement_rotation),
    AREA(R.string.placement_area),
}

/** Ce que la recherche de placement a retenu, pour pouvoir le dire et le reprendre. */
data class PlacementOutcome(
    val moved: Boolean,
    val movedMeters: Double,
    val rotationDeg: Double,
    val distanceMeters: Double,
    val start: LatLon,
    val candidatesRouted: Int,
    val unavailableReason: String?,
)

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
    /** Vrai quand la forme personnalisée vient d'une image plutôt que d'un dessin. */
    val customFromImage: Boolean = false,
    val distanceKm: Float = 10f,
    val activity: ActivityType = ActivityType.RUN,
    val rotationDeg: Float = 0f,
    val mirrored: Boolean = false,
    val anchorMode: AnchorMode = AnchorMode.START,
    val engine: EngineChoice = EngineChoice.BROUTER,
    val osrmUrl: String = "",
    val placementMode: PlacementMode = PlacementMode.ROTATION,
    /** Rayon de recherche d'un meilleur départ, en kilomètres. */
    val searchRadiusKm: Float = 1f,
    /** Latitude accordée sur la distance pendant la recherche, en pour cent. */
    val distanceTolerancePercent: Float = 0f,
    val placement: PlacementOutcome? = null,
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
    /**
     * Motif du refus quand le quartier ne permet pas de boucler. Distinct de
     * [message] : celui-ci passe dans un bandeau fugace, alors qu'un refus doit
     * rester à l'écran, avec ce qu'il faut faire pour s'en sortir.
     */
    val blocker: String? = null,
) {
    val distanceMeters: Double get() = distanceKm.toDouble() * 1000.0

    val shapeLabel: String?
        get() = if (shapeId == CUSTOM_SHAPE_ID) null else ShapeLibrary.byId(shapeId)?.label

    /** La forme effectivement utilisée, ou `null` si le dessin personnalisé manque. */
    val shape: ShapePath?
        get() = if (shapeId == CUSTOM_SHAPE_ID) customShape else ShapeLibrary.byId(shapeId)?.path

    val canGenerate: Boolean get() = start != null && shape != null && !generating

    /**
     * La forme telle qu'elle se posera sur la carte, avant tout calcul d'itinéraire.
     *
     * L'afficher pendant que l'utilisateur règle la distance ou l'orientation lui
     * évite de lancer un calcul réseau pour découvrir que la forme tombe à côté.
     */
    val preview: List<LatLon>
        get() {
            val anchor = start ?: return emptyList()
            val path = shape ?: return emptyList()
            return runCatching {
                ShapeProjector.project(
                    shape = path,
                    anchor = anchor,
                    distanceMeters = distanceMeters,
                    rotationDeg = rotationDeg.toDouble(),
                    mode = anchorMode,
                    mirrored = mirrored,
                )
            }.getOrDefault(emptyList())
        }
}

class RouteViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = Preferences(application)
    private val http = JdkHttpClient(userAgent = "StravArt/${BuildConfig.VERSION_NAME} (Android)")
    private val geocoder = NominatimGeocoder(http)

    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    private val overpass = OverpassClient(http)

    private var searchJob: Job? = null
    private var generateJob: Job? = null

    /**
     * Dernier secteur de rues téléchargé.
     *
     * Overpass est un service public partagé : régler une orientation puis relancer
     * ne doit pas le solliciter à nouveau. On réutilise le secteur tant que le
     * nouveau départ y tient largement.
     */
    private var cachedRoads: CachedRoads? = null

    private data class CachedRoads(
        val center: LatLon,
        val radiusMeters: Double,
        val activity: ActivityType,
        val ways: List<RoadWay>,
    )

    private val roadSource = RoadSource { center, radius, activity ->
        val cache = cachedRoads
        val reusable = cache != null &&
            cache.activity == activity &&
            Geo.distance(cache.center, center) + radius <= cache.radiusMeters
        if (reusable) {
            cache.ways
        } else {
            overpass.fetch(center, radius, activity).also {
                cachedRoads = CachedRoads(center, radius, activity, it)
            }
        }
    }

    private fun restoreState(): RouteUiState {
        val customShape = preferences.customShape
        return RouteUiState(
            // Si le dessin mémorisé n'est plus lisible, mieux vaut rouvrir sur une
            // forme du catalogue que sur un choix vide et un bouton grisé.
            shapeId = preferences.shapeId
                .takeUnless { it == CUSTOM_SHAPE_ID && customShape == null }
                ?: ShapeLibrary.default.id,
            customShape = customShape,
            customFromImage = preferences.customFromImage,
            distanceKm = preferences.distanceKm,
            activity = preferences.activity,
            anchorMode = preferences.anchorMode,
            engine = EngineChoice.fromId(preferences.engineId),
            osrmUrl = preferences.osrmUrl,
            start = preferences.lastStart,
            startLabel = preferences.lastStartLabel,
        )
    }

    // --- Réglages du parcours ------------------------------------------------

    /**
     * Applique un changement de réglage. Toute modification d'entrée efface le refus
     * précédent : c'est précisément en changeant quelque chose que l'utilisateur en
     * sort, l'y laisser serait lui dire que rien n'a bougé.
     */
    private fun updateSettings(transform: (RouteUiState) -> RouteUiState) =
        _state.update { transform(it).copy(blocker = null) }

    fun selectShape(id: String) {
        preferences.shapeId = id
        updateSettings { it.copy(shapeId = id, route = null) }
    }

    /** @param fromImage sert seulement à savoir laquelle des deux tuiles surligner. */
    fun setCustomShape(shape: ShapePath, fromImage: Boolean = false) {
        preferences.shapeId = CUSTOM_SHAPE_ID
        preferences.customShape = shape
        preferences.customFromImage = fromImage
        updateSettings {
            it.copy(
                shapeId = CUSTOM_SHAPE_ID,
                customShape = shape,
                customFromImage = fromImage,
                route = null,
            )
        }
    }

    fun setDistance(km: Float) {
        preferences.distanceKm = km
        updateSettings { it.copy(distanceKm = km) }
    }

    fun setActivity(activity: ActivityType) {
        preferences.activity = activity
        updateSettings { it.copy(activity = activity) }
    }

    fun setRotation(degrees: Float) = updateSettings { it.copy(rotationDeg = degrees) }

    fun setMirrored(mirrored: Boolean) = updateSettings { it.copy(mirrored = mirrored) }

    fun setAnchorMode(mode: AnchorMode) {
        preferences.anchorMode = mode
        updateSettings { it.copy(anchorMode = mode) }
    }

    fun setEngine(choice: EngineChoice) {
        preferences.engineId = choice.id
        updateSettings { it.copy(engine = choice) }
    }

    fun setOsrmUrl(url: String) {
        preferences.osrmUrl = url
        updateSettings { it.copy(osrmUrl = url) }
    }

    // --- Point de départ -----------------------------------------------------

    fun setStart(location: LatLon, label: String? = null) {
        preferences.lastStart = location
        preferences.lastStartLabel = label
        updateSettings {
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

    /**
     * Revient à l'édition depuis l'état résultat.
     *
     * Les réglages sont conservés : on revient pour ajuster une orientation ou une
     * distance, pas pour tout ressaisir.
     */
    fun clearRoute() = _state.update { it.copy(route = null) }

    fun setPlacementMode(mode: PlacementMode) = updateSettings { it.copy(placementMode = mode) }

    fun setSearchRadius(km: Float) = updateSettings { it.copy(searchRadiusKm = km) }

    fun setDistanceTolerance(percent: Float) =
        updateSettings { it.copy(distanceTolerancePercent = percent) }

    /**
     * Reprend le placement retenu par la recherche comme réglage courant.
     *
     * L'adoption est explicite : la recherche propose, elle ne réécrit pas en douce
     * ce que l'utilisateur a saisi.
     */
    fun adoptPlacement() {
        val found = _state.value.placement ?: return
        preferences.lastStart = found.start
        preferences.distanceKm = (found.distanceMeters / 1000.0).toFloat()
        updateSettings {
            it.copy(
                start = found.start,
                startLabel = null,
                rotationDeg = found.rotationDeg.toFloat(),
                distanceKm = (found.distanceMeters / 1000.0).toFloat(),
                placement = null,
                route = null,
            )
        }
    }

    fun cancelGeneration() {
        generateJob?.cancel()
        generateJob = null
        _state.update { it.copy(generating = false, progress = null) }
    }

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

        val mode = current.placementMode
        val options = searchOptions(current)

        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    generating = true,
                    message = null,
                    blocker = null,
                    placement = null,
                    progress = string(R.string.generating),
                )
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (mode == PlacementMode.NONE) {
                        Guided(
                            RouteGenerator(engine).generate(request) { progress ->
                                _state.update { it.copy(progress = progress.message) }
                            },
                        )
                    } else {
                        guided(engine, request, options)
                    }
                }
            }
            outcome
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            generating = false,
                            progress = null,
                            route = result.route,
                            placement = result.placement,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            generating = false,
                            progress = null,
                            route = null,
                            placement = null,
                            // Un quartier qui ne boucle pas n'est pas une panne : le
                            // message doit rester lisible et dire quoi faire.
                            blocker = (error as? UnsuitableAreaException)?.message,
                            message = if (error is UnsuitableAreaException) null
                            else error.message ?: string(R.string.error_title),
                        )
                    }
                }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    /** Enveloppe commune aux deux chemins : avec ou sans recherche de placement. */
    private class Guided(val route: GeneratedRoute, val placement: PlacementOutcome? = null)

    private fun searchOptions(state: RouteUiState) = PlacementSearchOptions(
        radiusMeters = if (state.placementMode == PlacementMode.AREA) {
            state.searchRadiusKm.toDouble() * 1000.0
        } else {
            0.0
        },
        distanceTolerance = state.distanceTolerancePercent.toDouble() / 100.0,
        scaleSteps = if (state.distanceTolerancePercent > 0f) 2 else 0,
    )

    private fun guided(
        engine: RoutingEngine,
        request: RouteRequest,
        options: PlacementSearchOptions,
    ): Guided {
        val result = GuidedRouteGenerator(RouteGenerator(engine), roadSource).generate(
            request = request,
            options = options,
        ) { step, index, total ->
            _state.update { it.copy(progress = progressText(step, index, total)) }
        }
        val chosen = result.placement
        return Guided(
            route = result.route,
            placement = PlacementOutcome(
                moved = result.improved,
                movedMeters = Geo.distance(request.start, chosen.anchor),
                rotationDeg = chosen.rotationDeg,
                distanceMeters = chosen.distanceMeters,
                start = chosen.anchor,
                candidatesRouted = result.candidatesRouted,
                unavailableReason = result.unavailableReason,
            ),
        )
    }

    private fun progressText(step: GuidedRouteGenerator.Step, index: Int, total: Int): String =
        when (step) {
            GuidedRouteGenerator.Step.FETCHING_ROADS -> string(R.string.progress_roads)
            GuidedRouteGenerator.Step.SCORING -> string(R.string.progress_scoring)
            GuidedRouteGenerator.Step.ROUTING -> string(R.string.progress_routing, index + 1, total)
        }

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

package com.stravart.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravart.app.R
import com.stravart.app.export.GpxExporter
import com.stravart.app.ui.components.RouteMap
import com.stravart.app.ui.components.RouteMapColors
import com.stravart.app.ui.components.ShapeThumbnail
import com.stravart.app.ui.theme.LocalMapColors
import com.stravart.app.ui.theme.MonospaceFieldStyle
import com.stravart.core.geo.LatLon
import com.stravart.core.route.GeneratedRoute
import com.stravart.core.routing.ActivityType
import com.stravart.core.shape.AnchorMode
import com.stravart.core.shape.ShapeLibrary
import java.util.Locale
import kotlin.math.roundToInt

/** En dessous de cette note, mieux vaut prévenir que la forme ne ressort pas. */
private const val LOW_FIDELITY_THRESHOLD = 45

/** Seuils de couleur de la ressemblance, tels que fixés par la maquette. */
private const val FIDELITY_GOOD = 80
private const val FIDELITY_FAIR = 60

private val ScreenMargin = 20.dp
private val SectionGap = 16.dp
private val GroupGap = 10.dp

/** Hauteur laissée à la carte au repos ; la maquette la veut dominante. */
private val MapHeightEditing = 336.dp
private val MapHeightResult = 326.dp

/**
 * Hauteur minimale de la carte, panneau grand ouvert.
 *
 * En dessous, la carte cesse de renseigner : on ne distingue plus où tombe la forme,
 * et le panneau n'est plus une feuille posée sur un fond mais un écran à part entière.
 */
private val MapHeightMin = 120.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    state: RouteUiState,
    actions: RouteActions,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var recenterRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.dismissMessage()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) actions.locateMe() else actions.locationDenied()
    }

    val saveFailed = stringResource(R.string.export_failed, "")
    val saved = stringResource(R.string.export_saved)
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GpxExporter.MIME_TYPE),
    ) { uri: Uri? ->
        val route = state.route
        if (uri != null && route != null) {
            val outcome = runCatching { GpxExporter.writeTo(context, uri, route) }
            actions.showMessage(
                outcome.fold({ saved }, { saveFailed + (it.message ?: "") }),
            )
        }
    }

    val showingResult = state.route != null
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val mapHeight = if (showingResult) MapHeightResult else MapHeightEditing
    // La feuille est ancrée par sa hauteur de repos, celle qui laisse à la carte la
    // place voulue. Sur un écran court le calcul deviendrait négatif : on lui laisse
    // alors la moitié de la hauteur disponible, faute de mieux.
    val peekHeight = (screenHeight - mapHeight).coerceAtLeast(screenHeight / 2)
    val sheetMaxHeight = (screenHeight - MapHeightMin).coerceAtLeast(peekHeight)

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        ),
    )

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        sheetShadowElevation = 3.dp,
        sheetDragHandle = { PanelHandle() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            RouteTopBar(
                showingResult = showingResult,
                darkTheme = darkTheme,
                onToggleTheme = onToggleTheme,
                onBack = actions.clearRoute,
            )
        },
        sheetContent = {
            Column(
                modifier = Modifier
                    .heightIn(max = sheetMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                val route = state.route
                if (route != null) {
                    ResultPanel(
                        route = route,
                        onShare = {
                            runCatching { context.startActivity(GpxExporter.shareIntent(context, route)) }
                                .onFailure { actions.showMessage(saveFailed + (it.message ?: "")) }
                        },
                        onSave = { saveLauncher.launch(GpxExporter.fileName(route)) },
                        onRegenerate = actions.generate,
                        generating = state.generating,
                    )
                } else {
                    EditPanel(
                        state = state,
                        actions = actions,
                        onRequestLocation = {
                            permissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        MapArea(
            state = state,
            actions = actions,
            recenterRequest = recenterRequest,
            onRecenter = { recenterRequest++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

/** Regroupe les actions de l'écran pour garder les composables indépendants du ViewModel. */
data class RouteActions(
    val setStart: (LatLon) -> Unit,
    val selectPlace: (LatLon, String) -> Unit,
    val onQueryChange: (String) -> Unit,
    val locateMe: () -> Unit,
    val locationDenied: () -> Unit,
    val selectShape: (String) -> Unit,
    val openDrawing: () -> Unit,
    val openImage: () -> Unit,
    val setDistance: (Float) -> Unit,
    val setActivity: (ActivityType) -> Unit,
    val setRotation: (Float) -> Unit,
    val setMirrored: (Boolean) -> Unit,
    val setAnchorMode: (AnchorMode) -> Unit,
    val setEngine: (EngineChoice) -> Unit,
    val setOsrmUrl: (String) -> Unit,
    val generate: () -> Unit,
    val clearRoute: () -> Unit,
    val showMessage: (String) -> Unit,
    val dismissMessage: () -> Unit,
)

// --- Chrome --------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteTopBar(
    showingResult: Boolean,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(
                    if (showingResult) R.string.result_screen_title else R.string.app_name,
                ),
                style = if (showingResult) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
            )
        },
        navigationIcon = {
            if (showingResult) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = stringResource(R.string.theme_toggle),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun PanelHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

// --- Carte ---------------------------------------------------------------------

@Composable
private fun MapArea(
    state: RouteUiState,
    actions: RouteActions,
    recenterRequest: Int,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapColors.current
    val mapColors = remember(palette) {
        RouteMapColors(
            route = palette.route,
            casing = palette.routeCasing,
            shape = palette.targetShape,
            stray = palette.unfollowed,
            markerRing = palette.startMarkerRing,
            markerCore = palette.startMarkerCore,
        )
    }

    // `preview` reprojette la forme à chaque lecture : on la calcule une fois.
    val preview = state.preview

    Box(modifier) {
        RouteMap(
            start = state.start,
            route = state.route?.points.orEmpty(),
            idealShape = state.route?.idealShape ?: preview,
            unfollowed = state.route?.unfollowed?.map { it.shapePoints }.orEmpty(),
            colors = mapColors,
            startTitle = stringResource(R.string.start_marker_title),
            onLongPress = actions.setStart,
            recenterRequest = recenterRequest,
            modifier = Modifier.fillMaxSize(),
        )

        if (state.route != null || preview.size >= 2) {
            Surface(
                onClick = onRecenter,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = stringResource(R.string.map_recenter),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        state.route?.let { route ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LegendChip(stringResource(R.string.map_legend_route), palette.route)
                LegendChip(stringResource(R.string.map_legend_shape), palette.targetShape)
                if (route.unfollowed.isNotEmpty()) {
                    LegendChip(stringResource(R.string.map_legend_stray), palette.unfollowed)
                }
            }
        }

        // L'attribution est dessinée ici plutôt que par l'overlay d'osmdroid : sa
        // place est imposée par la licence, pas sa forme, et la maquette la veut
        // à droite, là où la légende ne passe pas.
        Text(
            text = stringResource(R.string.map_attribution),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun LegendChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dot(color, 8.dp)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Dot(color: Color, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

// --- Panneau d'édition ---------------------------------------------------------

@Composable
private fun EditPanel(
    state: RouteUiState,
    actions: RouteActions,
    onRequestLocation: () -> Unit,
) {
    StartSection(state, actions, onRequestLocation)
    ShapeSection(state, actions)
    DistanceSection(state, actions)
    ActivitySection(state, actions)
    AdvancedSection(state, actions)

    Spacer(Modifier.height(2.dp))

    PrimaryButton(
        label = stringResource(if (state.generating) R.string.generating_button else R.string.generate),
        enabled = state.canGenerate,
        loading = state.generating,
        onClick = actions.generate,
        modifier = Modifier.padding(horizontal = ScreenMargin),
    )

    state.blocker?.let { reason ->
        BlockedCard(reason, Modifier.padding(horizontal = ScreenMargin))
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenMargin),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        trailing?.invoke()
    }
}

@Composable
private fun StartSection(
    state: RouteUiState,
    actions: RouteActions,
    onRequestLocation: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        SectionHeader(stringResource(R.string.section_start))

        Row(
            modifier = Modifier.padding(horizontal = ScreenMargin),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                placeholder = {
                    Text(
                        stringResource(R.string.start_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (state.searching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
            )
            Surface(
                onClick = onRequestLocation,
                enabled = !state.locating,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.locating) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = stringResource(R.string.start_use_my_location),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        if (state.results.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenMargin),
            ) {
                Column {
                    state.results.take(5).forEach { place ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { actions.selectPlace(place.location, place.name) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Dot(MaterialTheme.colorScheme.outlineVariant, 8.dp)
                            Text(
                                text = place.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenMargin),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Dot(LocalMapColors.current.route, 8.dp)
                    Text(
                        text = state.startLabel ?: stringResource(R.string.start_map_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShapeSection(state: RouteUiState, actions: RouteActions) {
    val listState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        SectionHeader(stringResource(R.string.section_shape)) {
            Text(
                text = stringResource(R.string.shape_count, ShapeLibrary.presets.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            horizontalArrangement = Arrangement.spacedBy(GroupGap),
            contentPadding = PaddingValues(horizontal = ScreenMargin),
        ) {
            item {
                val drawn = state.customShape.takeIf { !state.customFromImage }
                ShapeTile(
                    label = stringResource(R.string.shape_draw),
                    selected = state.shapeId == CUSTOM_SHAPE_ID && !state.customFromImage,
                    onClick = actions.openDrawing,
                ) { color ->
                    if (drawn == null) {
                        Icon(Icons.Default.Brush, contentDescription = null, tint = color)
                    } else {
                        ShapeThumbnail(drawn, color, Modifier.fillMaxSize())
                    }
                }
            }
            item {
                val imported = state.customShape.takeIf { state.customFromImage }
                ShapeTile(
                    label = stringResource(R.string.shape_image),
                    selected = state.shapeId == CUSTOM_SHAPE_ID && state.customFromImage,
                    onClick = actions.openImage,
                ) { color ->
                    if (imported == null) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = color)
                    } else {
                        ShapeThumbnail(imported, color, Modifier.fillMaxSize())
                    }
                }
            }
            items(ShapeLibrary.presets.size) { index ->
                val preset = ShapeLibrary.presets[index]
                ShapeTile(
                    label = preset.label,
                    selected = state.shapeId == preset.id,
                    onClick = { actions.selectShape(preset.id) },
                ) { color ->
                    ShapeThumbnail(preset.path, color, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun ShapeTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val accent = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(76.dp),
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .border(if (selected) 2.dp else 1.5.dp, border, RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            content(accent)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DistanceSection(state: RouteUiState, actions: RouteActions) {
    Column {
        SectionHeader(stringResource(R.string.section_distance)) {
            Text(
                text = stringResource(
                    R.string.distance_value,
                    String.format(Locale.getDefault(), "%.1f", state.distanceKm),
                ),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = state.distanceKm,
            onValueChange = actions.setDistance,
            valueRange = 1f..60f,
            steps = 117, // pas de 0,5 km
            modifier = Modifier.padding(horizontal = ScreenMargin),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenMargin),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.distance_min),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.distance_max),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActivitySection(state: RouteUiState, actions: RouteActions) {
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        SectionHeader(stringResource(R.string.section_activity))
        Row(
            modifier = Modifier.padding(horizontal = ScreenMargin),
            horizontalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            ActivityChip(
                label = stringResource(R.string.activity_run),
                selected = state.activity == ActivityType.RUN,
                onClick = { actions.setActivity(ActivityType.RUN) },
                icon = { tint -> Icon(Icons.Default.DirectionsRun, null, tint = tint, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
            )
            ActivityChip(
                label = stringResource(R.string.activity_bike),
                selected = state.activity == ActivityType.BIKE,
                onClick = { actions.setActivity(ActivityType.BIKE) },
                icon = { tint -> Icon(Icons.Default.DirectionsBike, null, tint = tint, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActivityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier.height(52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Dot(MaterialTheme.colorScheme.primary, 8.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                icon(content)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = content,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSection(state: RouteUiState, actions: RouteActions) {
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable { expanded = !expanded }
                .padding(horizontal = ScreenMargin),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.section_advanced),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevron),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenMargin),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            stringResource(R.string.option_rotation),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.option_rotation_value, state.rotationDeg.roundToInt()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = state.rotationDeg,
                        onValueChange = actions.setRotation,
                        valueRange = 0f..350f,
                        steps = 34,
                    )

                    OptionDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.option_mirror),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.option_mirror_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.mirrored, onCheckedChange = actions.setMirrored)
                    }

                    OptionDivider()

                    Text(
                        stringResource(R.string.option_anchor),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(GroupGap)) {
                        AnchorChip(
                            label = stringResource(R.string.anchor_start),
                            selected = state.anchorMode == AnchorMode.START,
                            onClick = { actions.setAnchorMode(AnchorMode.START) },
                            modifier = Modifier.weight(1f),
                        )
                        AnchorChip(
                            label = stringResource(R.string.anchor_center),
                            selected = state.anchorMode == AnchorMode.CENTER,
                            onClick = { actions.setAnchorMode(AnchorMode.CENTER) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    OptionDivider()

                    Text(
                        stringResource(R.string.option_engine),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    EnginePicker(state.engine, actions.setEngine)

                    if (state.engine == EngineChoice.OSRM) {
                        Spacer(Modifier.height(GroupGap))
                        OutlinedTextField(
                            value = state.osrmUrl,
                            onValueChange = actions.setOsrmUrl,
                            placeholder = { Text("https://router.project-osrm.org", style = MonospaceFieldStyle) },
                            textStyle = MonospaceFieldStyle,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            supportingText = {
                                Text(
                                    stringResource(R.string.option_osrm_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                        )
                    }
                    if (state.engine == EngineChoice.STRAIGHT) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.engine_straight_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun AnchorChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier.height(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnginePicker(current: EngineChoice, onSelect: (EngineChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
    ) {
        OutlinedTextField(
            value = stringResource(current.labelRes),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EngineChoice.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(choice.labelRes), style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelect(choice)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

// --- Panneau de résultat -------------------------------------------------------

@Composable
private fun ResultPanel(
    route: GeneratedRoute,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRegenerate: () -> Unit,
    generating: Boolean,
) {
    Column(
        modifier = Modifier.padding(horizontal = ScreenMargin),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        MetricsCard(route)

        Text(
            text = technicalLine(route),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (route.unfollowed.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Dot(LocalMapColors.current.unfollowed, 8.dp)
                Text(
                    text = stringResource(
                        R.string.result_unfollowed,
                        (route.unfollowedRatio * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.result_unfollowed_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (route.fidelity.score < LOW_FIDELITY_THRESHOLD) {
            Text(
                text = stringResource(R.string.result_low_fidelity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(GroupGap)) {
            Button(
                onClick = onShare,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_share), style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onSave,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .width(140.dp)
                    .height(56.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_save), maxLines = 1)
            }
        }

        PrimaryButton(
            label = stringResource(if (generating) R.string.generating_button else R.string.generate_again),
            enabled = !generating,
            loading = generating,
            onClick = onRegenerate,
        )

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "i",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                }
                Text(
                    text = stringResource(R.string.export_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * Trois chiffres, trois colonnes de même largeur.
 *
 * L'unité est portée par le libellé et non par la valeur : « 10,24 » sur « km
 * parcourus » se compare d'un coup d'œil d'une génération à l'autre, ce qu'un
 * « 10,24 km » collé ne permet pas aussi vite.
 */
@Composable
private fun MetricsCard(route: GeneratedRoute) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Metric(
                value = String.format(Locale.getDefault(), "%.2f", route.distanceKm),
                label = stringResource(R.string.result_distance_label),
                modifier = Modifier.weight(1f),
            )
            MetricDivider()
            Metric(
                value = stringResource(R.string.result_fidelity_value, route.fidelity.score),
                label = stringResource(R.string.result_fidelity_label),
                color = fidelityColor(route.fidelity.score),
                modifier = Modifier.weight(1f),
            )
            MetricDivider()
            Metric(
                value = route.ascentMeters?.let { "+${it.roundToInt()}" } ?: "—",
                label = stringResource(R.string.result_ascent_label),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricDivider() {
    VerticalDivider(
        modifier = Modifier.fillMaxHeight(),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun fidelityColor(score: Int): Color = when {
    score >= FIDELITY_GOOD -> MaterialTheme.colorScheme.primary
    score >= FIDELITY_FAIR -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun Metric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = color,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Ce que le moteur a réellement fait, sur une ligne.
 *
 * Un repli sur un autre profil ou sur moins de points change tout le résultat : le
 * taire condamnerait à chercher pourquoi le parcours ne ressemble pas à l'attendu.
 */
@Composable
private fun technicalLine(route: GeneratedRoute): String {
    val diagnostics = route.diagnostics
    val parts = buildList {
        add(stringResource(R.string.result_engine, route.engineName))
        diagnostics.profileUsed?.let { add(stringResource(R.string.result_profile, it)) }
        add(
            if (diagnostics.usedWaypoints < diagnostics.requestedWaypoints) {
                stringResource(
                    R.string.result_waypoints_reduced,
                    diagnostics.usedWaypoints,
                    diagnostics.requestedWaypoints,
                )
            } else {
                stringResource(R.string.result_waypoints, diagnostics.usedWaypoints)
            },
        )
        if (diagnostics.relocatedWaypoints > 0) {
            add(stringResource(R.string.result_relocated, diagnostics.relocatedWaypoints))
        }
        if (diagnostics.discardedWaypoints > 0) {
            add(stringResource(R.string.result_discarded, diagnostics.discardedWaypoints))
        }
        if (route.removedSpurs > 0) {
            add(stringResource(R.string.result_spurs, route.removedSpurs))
        }
        add(stringResource(R.string.result_deviation, route.fidelity.meanDeviationMeters.roundToInt()))
        add(stringResource(R.string.result_attempts, route.attempts))
    }
    return parts.joinToString(" · ")
}

/**
 * Refus explicite quand le quartier ne se prête pas à une boucle.
 *
 * Un bandeau fugace ne conviendrait pas : l'utilisateur vient d'attendre un calcul
 * pour ne rien obtenir, il lui faut la raison et la marche à suivre, affichées
 * jusqu'à ce qu'il change quelque chose.
 */
@Composable
private fun BlockedCard(reason: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.blocked_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.blocked_advice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.blocked_suggestion),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

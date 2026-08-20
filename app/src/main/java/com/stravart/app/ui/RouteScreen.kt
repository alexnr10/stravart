package com.stravart.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stravart.app.R
import com.stravart.app.export.GpxExporter
import com.stravart.app.ui.components.RouteMap
import com.stravart.app.ui.components.ShapeThumbnail
import com.stravart.app.ui.theme.RouteGreen
import com.stravart.app.ui.theme.ShapeOrange
import com.stravart.app.ui.theme.StrayRed
import com.stravart.core.geo.LatLon
import com.stravart.core.route.GeneratedRoute
import com.stravart.core.routing.ActivityType
import com.stravart.core.shape.AnchorMode
import com.stravart.core.shape.ShapeLibrary
import java.util.Locale
import kotlin.math.roundToInt

/** En dessous de cette note, mieux vaut prévenir que la forme ne ressort pas. */
private const val LOW_FIDELITY_THRESHOLD = 45

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    state: RouteUiState,
    actions: RouteActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                RouteMap(
                    start = state.start,
                    route = state.route?.points.orEmpty(),
                    idealShape = state.route?.idealShape ?: state.preview,
                    unfollowed = state.route?.unfollowed?.map { it.shapePoints }.orEmpty(),
                    routeColor = RouteGreen,
                    shapeColor = ShapeOrange,
                    strayColor = StrayRed,
                    startTitle = stringResource(R.string.start_marker_title),
                    onLongPress = actions.setStart,
                    modifier = Modifier.fillMaxSize(),
                )
                MapOverlay(state = state, modifier = Modifier.align(Alignment.BottomStart))
            }

            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.15f),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StartSection(
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
                    HorizontalDivider()
                    ShapeSection(state = state, actions = actions)
                    DistanceSection(state = state, actions = actions)
                    ActivitySection(state = state, actions = actions)
                    AdvancedSection(state = state, actions = actions)

                    Button(
                        onClick = actions.generate,
                        enabled = state.canGenerate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.generating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(state.progress ?: stringResource(R.string.generating))
                        } else {
                            Text(
                                stringResource(
                                    if (state.route == null) R.string.generate else R.string.generate_again,
                                ),
                            )
                        }
                    }

                    state.blocker?.let { reason -> BlockedCard(reason) }

                    state.route?.let { route ->
                        ResultCard(
                            route = route,
                            onShare = {
                                runCatching { context.startActivity(GpxExporter.shareIntent(context, route)) }
                                    .onFailure { actions.showMessage(saveFailed + (it.message ?: "")) }
                            },
                            onSave = { saveLauncher.launch(GpxExporter.fileName(route)) },
                        )
                    }
                }
            }
        }
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
    val setDistance: (Float) -> Unit,
    val setActivity: (ActivityType) -> Unit,
    val setRotation: (Float) -> Unit,
    val setMirrored: (Boolean) -> Unit,
    val setAnchorMode: (AnchorMode) -> Unit,
    val setEngine: (EngineChoice) -> Unit,
    val setOsrmUrl: (String) -> Unit,
    val generate: () -> Unit,
    val showMessage: (String) -> Unit,
    val dismissMessage: () -> Unit,
)

@Composable
private fun MapOverlay(state: RouteUiState, modifier: Modifier = Modifier) {
    val route = state.route ?: return
    Row(
        modifier = modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LegendChip(stringResource(R.string.map_legend_route), RouteGreen)
        LegendChip(stringResource(R.string.map_legend_shape), ShapeOrange)
        if (route.unfollowed.isNotEmpty()) {
            LegendChip(stringResource(R.string.map_legend_stray), StrayRed)
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun StartSection(
    state: RouteUiState,
    actions: RouteActions,
    onRequestLocation: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.section_start))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                label = { Text(stringResource(R.string.start_search_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                trailingIcon = {
                    if (state.searching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRequestLocation, enabled = !state.locating) {
                if (state.locating) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.MyLocation, stringResource(R.string.start_use_my_location))
                }
            }
        }

        state.results.take(5).forEach { place ->
            Text(
                text = place.name,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.selectPlace(place.location, place.name) }
                    .padding(vertical = 6.dp),
            )
        }

        Text(
            text = state.startLabel ?: stringResource(R.string.start_map_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShapeSection(state: RouteUiState, actions: RouteActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.section_shape))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShapeTile(
                label = stringResource(R.string.shape_draw),
                selected = state.shapeId == CUSTOM_SHAPE_ID,
                onClick = actions.openDrawing,
            ) { color ->
                val custom = state.customShape
                if (custom == null) {
                    Icon(Icons.Default.Brush, contentDescription = null, tint = color)
                } else {
                    ShapeThumbnail(custom, color, Modifier.fillMaxSize())
                }
            }

            ShapeLibrary.presets.forEach { preset ->
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
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(72.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            content(tint)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun DistanceSection(state: RouteUiState, actions: RouteActions) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(stringResource(R.string.section_distance))
            Text(
                text = stringResource(
                    R.string.distance_value,
                    String.format(Locale.getDefault(), "%.1f", state.distanceKm),
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = state.distanceKm,
            onValueChange = actions.setDistance,
            valueRange = 1f..60f,
            steps = 117, // pas de 0,5 km
        )
    }
}

@Composable
private fun ActivitySection(state: RouteUiState, actions: RouteActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.section_activity))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.activity == ActivityType.RUN,
                onClick = { actions.setActivity(ActivityType.RUN) },
                label = { Text(stringResource(R.string.activity_run)) },
                leadingIcon = { Icon(Icons.Default.DirectionsRun, contentDescription = null) },
            )
            FilterChip(
                selected = state.activity == ActivityType.BIKE,
                onClick = { actions.setActivity(ActivityType.BIKE) },
                label = { Text(stringResource(R.string.activity_bike)) },
                leadingIcon = { Icon(Icons.Default.DirectionsBike, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun AdvancedSection(state: RouteUiState, actions: RouteActions) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(stringResource(R.string.section_advanced))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.option_rotation), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.option_rotation_value, state.rotationDeg.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Slider(
                    value = state.rotationDeg,
                    onValueChange = actions.setRotation,
                    valueRange = 0f..350f,
                    steps = 34,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.option_mirror), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.mirrored, onCheckedChange = actions.setMirrored)
                }

                Text(stringResource(R.string.option_anchor), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.anchorMode == AnchorMode.START,
                        onClick = { actions.setAnchorMode(AnchorMode.START) },
                        label = { Text(stringResource(R.string.anchor_start)) },
                    )
                    FilterChip(
                        selected = state.anchorMode == AnchorMode.CENTER,
                        onClick = { actions.setAnchorMode(AnchorMode.CENTER) },
                        label = { Text(stringResource(R.string.anchor_center)) },
                    )
                }

                Text(stringResource(R.string.option_engine), style = MaterialTheme.typography.bodyMedium)
                EngineChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = state.engine == choice,
                        onClick = { actions.setEngine(choice) },
                        label = { Text(stringResource(choice.labelRes)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.engine == EngineChoice.OSRM) {
                    OutlinedTextField(
                        value = state.osrmUrl,
                        onValueChange = actions.setOsrmUrl,
                        label = { Text(stringResource(R.string.option_osrm_url)) },
                        placeholder = { Text("https://router.project-osrm.org") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.engine == EngineChoice.STRAIGHT) {
                    Text(
                        text = stringResource(R.string.engine_straight_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    route: GeneratedRoute,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.result_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Metric(
                    label = stringResource(R.string.result_distance),
                    value = stringResource(
                        R.string.result_distance_value,
                        String.format(Locale.getDefault(), "%.2f", route.distanceKm),
                    ),
                )
                Metric(
                    label = stringResource(R.string.result_fidelity),
                    value = stringResource(R.string.result_fidelity_value, route.fidelity.score),
                )
                route.ascentMeters?.let {
                    Metric(
                        label = stringResource(R.string.result_ascent),
                        value = stringResource(R.string.result_ascent_value, it.roundToInt()),
                    )
                }
            }

            val deviation = stringResource(
                R.string.result_deviation,
                route.fidelity.meanDeviationMeters.roundToInt(),
            )
            val spurs = if (route.removedSpurs > 0) {
                stringResource(R.string.result_spurs, route.removedSpurs)
            } else {
                null
            }
            val attempts = stringResource(R.string.result_attempts, route.attempts)
            val engine = stringResource(R.string.result_engine, route.engineName)
            Text(
                text = listOfNotNull(deviation, spurs, attempts, engine).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Ce que le moteur a réellement fait. Un repli sur un autre profil ou sur
            // moins de points change tout le résultat : le taire condamnerait à
            // chercher pourquoi le parcours ne ressemble pas à ce qu'on attendait.
            val diagnostics = route.diagnostics
            val profile = diagnostics.profileUsed?.let { stringResource(R.string.result_profile, it) }
            val waypoints = if (diagnostics.waypoints.size < diagnostics.requestedWaypoints) {
                stringResource(
                    R.string.result_waypoints_reduced,
                    diagnostics.waypoints.size,
                    diagnostics.requestedWaypoints,
                )
            } else {
                stringResource(R.string.result_waypoints, diagnostics.waypoints.size)
            }
            val relocated = if (diagnostics.relocatedWaypoints > 0) {
                stringResource(R.string.result_relocated, diagnostics.relocatedWaypoints)
            } else {
                null
            }
            Text(
                text = listOfNotNull(profile, waypoints, relocated).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (route.unfollowed.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(StrayRed),
                        )
                        Text(
                            text = stringResource(
                                R.string.result_unfollowed,
                                (route.unfollowedRatio * 100).roundToInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = stringResource(R.string.result_unfollowed_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (route.fidelity.score < LOW_FIDELITY_THRESHOLD) {
                Text(
                    text = stringResource(R.string.result_low_fidelity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_share))
                }
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_save))
                }
            }

            Text(
                text = stringResource(R.string.export_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Refus explicite quand le quartier ne se prête pas à une boucle.
 *
 * Un bandeau fugace ne conviendrait pas : l'utilisateur vient d'attendre un calcul
 * pour ne rien obtenir, il lui faut la raison et la marche à suivre, affichées
 * jusqu'à ce qu'il change quelque chose.
 */
@Composable
private fun BlockedCard(reason: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.blocked_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.blocked_advice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.blocked_suggestion),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

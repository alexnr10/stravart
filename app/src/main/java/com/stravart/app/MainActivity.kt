package com.stravart.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stravart.app.ui.DrawShapeScreen
import com.stravart.app.ui.ImportImageScreen
import com.stravart.app.ui.RouteActions
import com.stravart.app.ui.RouteScreen
import com.stravart.app.ui.RouteViewModel
import com.stravart.app.ui.theme.StravArtTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // La bascule est un choix de séance, pas un réglage : on court au soleil
            // ou de nuit, et le système ne le sait pas toujours. Elle prime donc sur
            // le thème système tant que l'application vit, sans être enregistrée.
            var darkOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
            val dark = darkOverride ?: isSystemInDarkTheme()
            StravArtTheme(darkTheme = dark) {
                StravArtApp(darkTheme = dark, onToggleTheme = { darkOverride = !dark })
            }
        }
    }
}

@Composable
private fun StravArtApp(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    viewModel: RouteViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Route) }

    val actions = remember(viewModel) {
        RouteActions(
            setStart = { viewModel.setStart(it) },
            selectPlace = { location, label -> viewModel.setStart(location, label) },
            onQueryChange = viewModel::onQueryChange,
            locateMe = viewModel::locateMe,
            locationDenied = viewModel::onLocationPermissionDenied,
            selectShape = viewModel::selectShape,
            openDrawing = { screen = Screen.Draw },
            openImage = { screen = Screen.Image },
            setDistance = viewModel::setDistance,
            setActivity = viewModel::setActivity,
            setRotation = viewModel::setRotation,
            setMirrored = viewModel::setMirrored,
            setAnchorMode = viewModel::setAnchorMode,
            setEngine = viewModel::setEngine,
            setOsrmUrl = viewModel::setOsrmUrl,
            generate = viewModel::generate,
            clearRoute = viewModel::clearRoute,
            showMessage = viewModel::showMessage,
            dismissMessage = viewModel::dismissMessage,
        )
    }

    when (screen) {
        Screen.Draw -> DrawShapeScreen(
            onCancel = { screen = Screen.Route },
            onValidate = { shape ->
                viewModel.setCustomShape(shape)
                screen = Screen.Route
            },
            onError = viewModel::showMessage,
        )

        Screen.Image -> ImportImageScreen(
            onCancel = { screen = Screen.Route },
            onValidate = { shape ->
                viewModel.setCustomShape(shape, fromImage = true)
                screen = Screen.Route
            },
        )

        Screen.Route -> RouteScreen(
            state = state,
            actions = actions,
            darkTheme = darkTheme,
            onToggleTheme = onToggleTheme,
        )
    }
}

/** Les trois écrans de l'application ; la navigation tient en un état. */
private enum class Screen { Route, Draw, Image }

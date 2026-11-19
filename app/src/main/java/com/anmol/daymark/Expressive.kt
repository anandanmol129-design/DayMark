package com.anmol.daymark

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.graphics.shapes.Morph

/**
 * The app's Material theme. On the light theme it uses Material 3 Expressive's springy motion and a
 * colour scheme built from the current tab's accent. It also keeps the status bar icons readable
 * and paints the window so nothing flashes the other theme's colour.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DaymarkTheme(content: @Composable () -> Unit) {
    val light = Look.light
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    val background = TodoBackground
    SideEffect {
        window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
    LaunchedEffect(light) { window?.setBackgroundDrawable(ColorDrawable(background.toArgb())) }
    // One call site for both looks, so flipping the theme recolours the app instead of rebuilding it (and losing its state).
    MaterialTheme(
        colorScheme = if (light) lightScheme() else darkScheme(),
        motionScheme = if (light) MotionScheme.expressive() else MotionScheme.standard(),
        content = content
    )
}

private fun darkScheme(): ColorScheme = darkColorScheme(
    primary = TodoBlue,
    onPrimary = Color.White,
    background = TodoBackground,
    surface = GlassDialog,
    onSurface = TodoText,
    onBackground = TodoText,
    surfaceVariant = TodoCardRaised,
    onSurfaceVariant = TodoMuted,
    // Snackbars: a quiet warm card instead of Material's pale lilac.
    inverseSurface = Color(0xFF2C2B28),
    inverseOnSurface = TodoText,
    inversePrimary = TodoBlue
)

private fun lightScheme(): ColorScheme {
    val a = Look.accent
    return lightColorScheme(
        primary = a.fill,
        onPrimary = a.onFill,
        primaryContainer = a.container,
        onPrimaryContainer = a.ink,
        secondaryContainer = a.container,
        onSecondaryContainer = a.ink,
        background = TodoBackground,
        onBackground = TodoText,
        surface = GlassDialog,
        onSurface = TodoText,
        surfaceVariant = TodoCardRaised, // neutral: only buttons and icons carry the tab colour
        onSurfaceVariant = TodoMuted,
        surfaceContainer = Color(0xFFFFFBF7),
        surfaceContainerHigh = Color(0xFFF6F6F6),
        outline = TodoMuted,
        outlineVariant = Color(0x262A1F1A),
        error = Danger,
        inverseSurface = Color(0xFF2A211C),
        inverseOnSurface = Color(0xFFFFF1E6),
        inversePrimary = a.container
    )
}

/** Sets the light theme's accent to the colour of whichever tab is showing, straight away (no fade: fading recoloured every screen on every frame). */
@Composable
internal fun FollowAccent(workspace: Workspace?, private: Boolean) {
    val target = Accents.of(workspace, private)
    SideEffect { if (Look.accent != target) Look.accent = target }
}

/** A [Morph] drawn at a fixed [progress], scaled from the unit square to whatever it clips. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, Path())
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val CheckMorph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }

/**
 * The outline of a round checkbox. On the light theme a tick springs it from a circle into a
 * scalloped cookie (Material 3 Expressive shape morphing); the dark theme keeps a plain circle.
 */
@Composable
internal fun checkShape(checked: Boolean): Shape {
    if (!Look.light) return CircleShape
    val progress by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessMediumLow), label = "checkMorph")
    val p = progress.coerceIn(0f, 1f)
    return remember(p) { if (p == 0f) CircleShape else MorphShape(CheckMorph, p) }
}

/** How far through the day's to-dos and habits you are, as a Material 3 Expressive wavy bar (light theme only). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DayProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    if (!Look.light || total == 0) return
    val progress by animateFloatAsState(done / total.toFloat(), spring(dampingRatio = .7f, stiffness = Spring.StiffnessLow), label = "dayProgress")
    LinearWavyProgressIndicator(progress = { progress }, modifier = modifier, color = CalendarBlue, trackColor = TodoCardRaised)
}

package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHigh,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = StatusRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHigh,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = StatusRed,
    onError = Color.White
)

@Immutable
data class TelemetryColors(
    val connected: Color,
    val listening: Color,
    val warning: Color,
    val signalStrong: Color,
    val signalFair: Color,
    val signalWeak: Color,
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    val incomingBubble: Color,
    val onIncomingBubble: Color
)

val LocalTelemetryColors = staticCompositionLocalOf {
    TelemetryColors(
        connected = StatusEmeraldLight,
        listening = LightPrimary,
        warning = StatusAmberLight,
        signalStrong = StatusEmeraldLight,
        signalFair = StatusAmberLight,
        signalWeak = StatusRed,
        outgoingBubble = LightPrimary,
        onOutgoingBubble = Color.White,
        incomingBubble = LightSurface,
        onIncomingBubble = LightTextPrimary
    )
}

val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MeshNetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val telemetryColors = TelemetryColors(
        connected = if (darkTheme) StatusEmeraldDark else StatusEmeraldLight,
        listening = if (darkTheme) DarkPrimary else LightPrimary,
        warning = if (darkTheme) StatusAmberDark else StatusAmberLight,
        signalStrong = if (darkTheme) StatusEmeraldDark else StatusEmeraldLight,
        signalFair = if (darkTheme) StatusAmberDark else StatusAmberLight,
        signalWeak = StatusRed,
        outgoingBubble = if (darkTheme) DarkPrimary else LightPrimary,
        onOutgoingBubble = Color.White,
        incomingBubble = if (darkTheme) DarkSurfaceContainer else LightSurface,
        onIncomingBubble = if (darkTheme) DarkTextPrimary else LightTextPrimary
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalTelemetryColors provides telemetryColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}

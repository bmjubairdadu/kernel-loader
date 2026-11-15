package com.kernelloader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.min

private val DarkColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = TextOnAccent,
    primaryContainer = FamilyRtDeep,
    onPrimaryContainer = AccentGreen,

    secondary = AccentBlue,
    onSecondary = TextOnAccent,
    secondaryContainer = Surface2,
    onSecondaryContainer = AccentBlue,

    tertiary = AccentViolet,
    onTertiary = TextOnAccent,
    tertiaryContainer = Surface3,
    onTertiaryContainer = AccentViolet,

    background = Surface0,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,

    error = AccentRed,
    onError = TextOnAccent,
    errorContainer = Surface2,
    onErrorContainer = AccentRed,

    outline = BorderStrong,
    outlineVariant = BorderSubtle
)

private val LightColorScheme = lightColorScheme(
    primary = Color_LightPrimary,
    onPrimary = Color_White,
    primaryContainer = Color_LightPrimaryContainer,
    onPrimaryContainer = Color_LightOnPrimaryContainer,

    secondary = Color_LightSecondary,
    onSecondary = Color_White,
    secondaryContainer = Color_LightSecondaryContainer,
    onSecondaryContainer = Color_LightOnSecondaryContainer,

    background = Color_LightBackground,
    onBackground = Color_LightOnBackground,
    surface = Color_LightSurface,
    onSurface = Color_LightOnBackground,
    surfaceVariant = Color_LightSurfaceVariant,
    onSurfaceVariant = Color_LightMuted,

    error = Color_LightError,
    onError = Color_White
)

@Composable
fun KernelLoderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemDensity = LocalDensity.current

    // Adaptive UI scaling: Ensures consistent layout across different screen sizes and densities.
    // 1. Normalizes fontScale to 1.0f so system "Large Font" settings don't deform UI cards and buttons.
    // 2. Calculates proportional density based on standard mobile width (392dp) so budget 720p, 1080p,
    //    and 2K devices render cards and text at the exact same proportional scale.
    val customDensity = remember(configuration.screenWidthDp, configuration.screenHeightDp, systemDensity) {
        val displayMetrics = context.resources.displayMetrics
        val isTablet = configuration.smallestScreenWidthDp >= 600

        if (isTablet) {
            Density(density = systemDensity.density, fontScale = 1.0f)
        } else {
            val shortDimensionPx = min(displayMetrics.widthPixels, displayMetrics.heightPixels).toFloat()
            val targetDensity = if (shortDimensionPx > 0f) {
                (shortDimensionPx / 392f).coerceIn(1.0f, 6.0f)
            } else {
                systemDensity.density
            }
            Density(density = targetDensity, fontScale = 1.0f)
        }
    }

    CompositionLocalProvider(LocalDensity provides customDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

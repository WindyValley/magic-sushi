package top.windyvalley.magicsushi.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================================================
// Color schemes
// ----------------------------------------------------------------------------
// Both schemes map Material 3 roles to our brand palette. On / off colors are
// chosen for AA contrast against the warm backgrounds.
// ============================================================================

/** Dark color scheme — deep brown bg + warm orange/red/green accents. */
private val DarkColorScheme = darkColorScheme(
    primary = SushiPrimary,
    secondary = SushiSecondary,
    tertiary = SushiTertiary,
    background = SushiBgDark,
    surface = SushiBgDark,
    onPrimary = SushiBgLight,
    onSecondary = SushiBgDark,
    onBackground = SushiBgLight,
    onSurface = SushiBgLight,
)

/** Light color scheme — cream bg + same warm accents. */
private val LightColorScheme = lightColorScheme(
    primary = SushiPrimary,
    secondary = SushiSecondary,
    tertiary = SushiTertiary,
    background = SushiBgLight,
    surface = SushiBgLight,
    onPrimary = SushiBgLight,
    onSecondary = SushiBgDark,
    onBackground = SushiBgDark,
    onSurface = SushiBgDark,
)

// ============================================================================
// MagicSushiTheme
// ----------------------------------------------------------------------------
// Root composable theme for the entire app.
//
// Behavior:
//  - On Android 12+ (`Build.VERSION_CODES.S`): when [dynamicColor] is true,
//    uses the system's Material You wallpaper-derived palette and overlays
//    our brand colors as accents.
//  - Otherwise (or when dynamic color is disabled): falls back to the static
//    light/dark [SushiColorScheme] above.
//  - Also paints the system status bar with the primary color for a cohesive
//    full-screen feel.
// ============================================================================

@Composable
fun MagicSushiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Disable to force the static brand palette (useful for screenshots / QA). */
    dynamicColor: Boolean = true,
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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SushiTypography,
        content = content
    )
}
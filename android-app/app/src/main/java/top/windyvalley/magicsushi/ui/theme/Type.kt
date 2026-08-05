package top.windyvalley.magicsushi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ============================================================================
// SushiTypography
// ----------------------------------------------------------------------------
// Default Roboto family — keeps the pixel-art feel without shipping custom
// font files. All sizes are tuned for a 7x7 board with HUD on top: large titles
// for the score/timer, medium for buttons, body for dialogs, label for chips.
// ============================================================================

val SushiTypography = Typography(
    /** Score / timer / game-over title — large bold display. */
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp
    ),

    /** Section title / pause dialog heading. */
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp
    ),

    /** Dialog body / menu items / button text. */
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),

    /** Secondary text, hints, toasts. */
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),

    /** Buttons / chips / overline tags. */
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
    )
)
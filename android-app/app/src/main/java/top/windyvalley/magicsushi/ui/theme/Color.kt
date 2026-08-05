package top.windyvalley.magicsushi.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// Background colors
// ============================================================================

/** Light theme background — warm cream / rice color, matches MTK pixel-art vibe. */
val SushiBgLight = Color(0xFFFFE8C5)

/** Dark theme background — deep warm brown (not pure black, preserves warm feel). */
val SushiBgDark = Color(0xFF2A1810)

// ============================================================================
// Brand / theme colors (Material 3 role mapping)
// ============================================================================

/** Primary — orange-red. Matches the dominant sushi palette in MTK original. */
val SushiPrimary = Color(0xFFE85D2F)

/** Secondary — warm orange. Used for accents and secondary actions. */
val SushiSecondary = Color(0xFFFFB347)

/** Tertiary — sushi seaweed green. Used for highlights and positive feedback. */
val SushiTertiary = Color(0xFF8BC34A)

// ============================================================================
// Sushi piece colors — 6 types, one per SushiType (SUSHI1..SUSHI6)
// ============================================================================

/** SUSHI1 — Salmon (red). */
val SushiColor1 = Color(0xFFE85D2F)

/** SUSHI2 — Sea urchin (orange). */
val SushiColor2 = Color(0xFFFFB347)

/** SUSHI3 — Cucumber / avocado (green). */
val SushiColor3 = Color(0xFF8BC34A)

/** SUSHI4 — Shrimp (blue / indigo). */
val SushiColor4 = Color(0xFF5C6BC0)

/** SUSHI5 — Octopus (purple). */
val SushiColor5 = Color(0xFFAB47BC)

/** SUSHI6 — Tuna (pink / magenta). */
val SushiColor6 = Color(0xFFEC407A)

// ============================================================================
// Status / feedback colors
// ============================================================================

/** Score & combo highlights — gold. */
val ScoreColor = Color(0xFFFFD700)

/** Timer low-warning (<10s remaining) — red flash. */
val TimerWarningColor = Color(0xFFD32F2F)
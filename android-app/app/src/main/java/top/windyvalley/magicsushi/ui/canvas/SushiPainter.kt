package top.windyvalley.magicsushi.ui.canvas

import androidx.compose.ui.graphics.Color
import top.windyvalley.magicsushi.engine.SushiType

/**
 * Fallback renderers for sushi tiles.
 *
 * The primary rendering path is **PNG-based** (see `GameCanvas.drawSushi` →
 * `imageResource(R.drawable.sushi_*)`). This object provides:
 *
 *   - [sushiColor]: a flat-color mapping used for debug overlays or previews
 *     where the PNG is not yet attached (e.g. Compose previews without the
 *     drawable loaded).
 *   - [getSushiEmoji]: an emoji fallback for cases where a sushi image cannot
 *     be drawn at all (e.g. unit-test renderers, future text-only fallback).
 *
 * The color values mirror `SushiColor1`..`SushiColor6` in `Color.kt`; we keep
 * them duplicated here so this file has no transitive dependency on the theme
 * module and can be unit-tested without bringing in Compose Material.
 */
object SushiPainter {

    /** Flat-color palette, parallel to the PNG set. */
    val sushiColor: Map<SushiType, Color> = mapOf(
        SushiType.SUSHI1 to Color(0xFFE85D2F), // 橙红 — salmon
        SushiType.SUSHI2 to Color(0xFFFFB347), // 橙黄 — sea urchin
        SushiType.SUSHI3 to Color(0xFF8BC34A), // 绿 — cucumber/avocado
        SushiType.SUSHI4 to Color(0xFF5C6BC0), // 蓝 — shrimp
        SushiType.SUSHI5 to Color(0xFFAB47BC), // 紫 — octopus
        SushiType.SUSHI6 to Color(0xFFEC407A), // 粉 — tuna
    )

    /**
     * Emoji representation for a sushi type, used when a bitmap is unavailable.
     *
     * Mapping notes:
     *   - SUSHI1 → 🍣 (sushi) — primary
     *   - SUSHI2 → 🍙 (rice ball) — secondary
     *   - SUSHI3 → 🍟 (placeholder; was meant to be a veggie side)
     *   - SUSHI4 → 🦐 (shrimp)
     *   - SUSHI5 → 🥡 (takeout box — placeholder)
     *   - SUSHI6 → 🍤 (fried shrimp)
     *
     * The emoji mapping is intentionally NOT meant to ship to users — it exists
     * for debug previews and headless rendering tests. Production always uses
     * the PNGs in `res/drawable/`.
     */
    fun getSushiEmoji(type: SushiType): String = when (type) {
        SushiType.SUSHI1 -> "\uD83C\uDF63" // 🍣
        SushiType.SUSHI2 -> "\uD83C\uDF59" // 🍙
        SushiType.SUSHI3 -> "\uD83C\uDF5F" // 🍟 (placeholder)
        SushiType.SUSHI4 -> "\uD83E\uDD90" // 🦐
        SushiType.SUSHI5 -> "\uD83E\uDD61" // 🥡 (placeholder)
        SushiType.SUSHI6 -> "\uD83C\uDF64" // 🍤
    }
}
package com.winlator.star.widget.fusionhud

/**
 * Size modes for the Fusion HUD (the 4th selectable in-game overlay style). One shared color-coded
 * visual language, four amounts of detail — see the approved mockup. The config value is the lower-case
 * token stored under the `hudSize` key; the label is the user-facing string.
 */
enum class FusionSize(val token: String, val label: String) {
    FULL("full", "Full"),
    TILES("tiles", "Tiles"),
    PILL("pill", "Pill"),
    MINIMAL("minimal", "Minimal");

    /** Next size in the tap-cycle: Full → Tiles → Pill → Minimal → Full. */
    fun next(): FusionSize {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        fun from(token: String?): FusionSize =
            values().firstOrNull { it.token.equals(token, ignoreCase = true) } ?: FULL
    }
}

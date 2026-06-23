package com.audiofetch

import android.graphics.Color

data class VibeTheme(
    val id: String,
    val name: String,
    val bgColor: Int,
    val accentColor: Int,
    val vibeColor: Int,
    val desc: String
)

object VibeThemes {
    val all = listOf(
        VibeTheme(
            id = "emerald",
            name = "Emerald",
            bgColor = Color.parseColor("#020503"),
            accentColor = Color.parseColor("#00FFA2"),
            vibeColor = Color.parseColor("#0A1F11"),
            desc = "A deep crystalline forest at night."
        ),
        VibeTheme(
            id = "nebula",
            name = "Nebula",
            bgColor = Color.parseColor("#050A1F"),
            accentColor = Color.parseColor("#00D2FF"),
            vibeColor = Color.parseColor("#0A1A3E"),
            desc = "Electric cosmic blue, sky-blue gases."
        ),
        VibeTheme(
            id = "solaris",
            name = "Solaris",
            bgColor = Color.parseColor("#1F150A"),
            accentColor = Color.parseColor("#FFCC33"),
            vibeColor = Color.parseColor("#2E1D0A"),
            desc = "Golden hour meets solar yellow."
        ),
        VibeTheme(
            id = "midnight",
            name = "Midnight",
            bgColor = Color.parseColor("#020208"),
            accentColor = Color.parseColor("#0077FF"),
            vibeColor = Color.parseColor("#05051A"),
            desc = "Cool, calm, electrically sharp sapphire."
        ),
        VibeTheme(
            id = "cyberpunk",
            name = "Cyberpunk",
            bgColor = Color.parseColor("#0A0B1E"),
            accentColor = Color.parseColor("#FF00FF"),
            vibeColor = Color.parseColor("#190A1E"),
            desc = "Neon-drenched magenta dystopia."
        ),
        VibeTheme(
            id = "flare",
            name = "Flare",
            bgColor = Color.parseColor("#120505"),
            accentColor = Color.parseColor("#FF4D00"),
            vibeColor = Color.parseColor("#2E0B0B"),
            desc = "Aggressive reds. An explosion of sound."
        ),
        VibeTheme(
            id = "hob",
            name = "H.O.B",
            bgColor = Color.parseColor("#000000"),
            accentColor = Color.parseColor("#FFFFFF"),
            vibeColor = Color.parseColor("#111111"),
            desc = "Monochromatic. Pitch black, sharp white."
        )
    )

    fun byId(id: String) = all.find { it.id == id } ?: all[0]
}

package com.example.musicapp.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The nine base colors the user can pick; [displayName] is shown in Settings. */
enum class ThemeColor(val displayName: String, val base: Int) {
    SLATE_PURPLE("Arduvaz moru", 0xFF6A5ACD.toInt()),
    LAVENDER("Lavanta moru", 0xFF7B68EE.toInt()),
    DENIM("Denim mavisi", 0xFF1560BD.toInt()),
    AQUAMARINE("Akuamarin", 0xFF7FFFD4.toInt()),
    STEEL_BLUE("Çelik mavisi", 0xFFB0C4DE.toInt()),
    PEACH("Şeftali", 0xFFFFDAB9.toInt()),
    BRICK("Kiremit kahve", 0xFFA52A2A.toInt()),
    MUSTARD("Hardal", 0xFFC68E17.toInt()),
    DARK_SLATE("Koyu arduvaz", 0xFF2F4F4F.toInt()),
}

enum class Appearance { DARK, LIGHT }

/**
 * Every color a screen needs, as ARGB ints. Backgrounds are neutral (dark grey or near white);
 * the theme color only appears in [primary], [fill], [primaryText] and [selected].
 */
data class ThemePalette(
    val ground: Int,
    val surface: Int,
    val surface2: Int,
    val line: Int,
    val text: Int,
    val muted: Int,
    val scrim: Int,
    val success: Int,
    /** Icons, borders, progress bars: at least 3:1 against [ground]. */
    val primary: Int,
    /** Filled buttons: at least 4.5:1 against [onPrimary]. */
    val fill: Int,
    val onPrimary: Int,
    /** Small colored text: at least 4.5:1 against [ground]. */
    val primaryText: Int,
    /** Background of a selected card or chip. */
    val selected: Int,
)

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val INK = 0xFF141416.toInt()

private val darkNeutrals = ThemePalette(
    ground = 0xFF121214.toInt(), surface = 0xFF1C1C1F.toInt(), surface2 = 0xFF2A2A2E.toInt(),
    line = 0xFF3A3A3F.toInt(), text = 0xFFF2F2F3.toInt(), muted = 0xFFA1A1A8.toInt(),
    scrim = 0xFF0A0A0B.toInt(), success = 0xFF3FD0A4.toInt(),
    primary = 0, fill = 0, onPrimary = 0, primaryText = 0, selected = 0,
)

private val lightNeutrals = ThemePalette(
    ground = 0xFFF7F7F8.toInt(), surface = 0xFFECECEF.toInt(), surface2 = 0xFFDEDEE2.toInt(),
    line = 0xFFCACACF.toInt(), text = INK, muted = 0xFF5E5E66.toInt(),
    scrim = 0xFFC9C9CE.toInt(), success = 0xFF1B7F5E.toInt(),
    primary = 0, fill = 0, onPrimary = 0, primaryText = 0, selected = 0,
)

fun themePalette(color: ThemeColor, appearance: Appearance): ThemePalette {
    val n = if (appearance == Appearance.DARK) darkNeutrals else lightNeutrals
    // On a dark ground a too-dark base is lightened; on a light ground a too-light base is darkened.
    val toward = if (appearance == Appearance.DARK) WHITE else BLACK
    val primary = ColorMath.adjust(color.base, n.ground, 3.0, toward)
    val onPrimary = if (ColorMath.contrast(primary, WHITE) >= ColorMath.contrast(primary, INK)) WHITE else INK
    val fill = if (ColorMath.contrast(primary, onPrimary) >= 4.5) primary
    else ColorMath.adjust(primary, onPrimary, 4.5, if (onPrimary == WHITE) BLACK else WHITE)
    return n.copy(
        primary = primary,
        fill = fill,
        onPrimary = onPrimary,
        primaryText = ColorMath.adjust(primary, n.ground, 4.5, toward),
        selected = ColorMath.mix(primary, n.ground, 0.84),
    )
}

/** WCAG relative luminance and contrast on ARGB ints; kept free of Android types so it is unit-testable. */
object ColorMath {
    private fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF

    fun luminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(channel(c, 16)) + 0.7152 * lin(channel(c, 8)) + 0.0722 * lin(channel(c, 0))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Linear blend from [a] (k = 0) to [b] (k = 1). */
    fun mix(a: Int, b: Int, k: Double): Int {
        fun m(shift: Int) = (channel(a, shift) * (1 - k) + channel(b, shift) * k).roundToInt()
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** Moves [c] toward [toward] in 5 % steps until it reaches [target] contrast against [bg]. */
    fun adjust(c: Int, bg: Int, target: Double, toward: Int): Int {
        if (contrast(c, bg) >= target) return c
        for (i in 1..19) {
            val m = mix(c, toward, i * 0.05)
            if (contrast(m, bg) >= target) return m
        }
        return toward
    }
}

package com.example.musicapp.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {
    private val all = ThemeColor.entries.flatMap { c -> Appearance.entries.map { a -> c to a } }

    @Test
    fun everyThemeMeetsContrastMinimums() {
        for ((color, appearance) in all) {
            val p = themePalette(color, appearance)
            val name = "$color/$appearance"
            assertTrue("$name primary on ground", ColorMath.contrast(p.primary, p.ground) >= 3.0)
            assertTrue("$name button text", ColorMath.contrast(p.fill, p.onPrimary) >= 4.5)
            assertTrue("$name colored text", ColorMath.contrast(p.primaryText, p.ground) >= 4.5)
            assertTrue("$name body text", ColorMath.contrast(p.text, p.ground) >= 4.5)
            assertTrue("$name secondary text", ColorMath.contrast(p.muted, p.ground) >= 4.5)
        }
    }

    @Test
    fun baseColorIsKeptWhenItAlreadyReadsWell() {
        assertEquals(ThemeColor.LAVENDER.base, themePalette(ThemeColor.LAVENDER, Appearance.DARK).primary)
        assertEquals(ThemeColor.BRICK.base, themePalette(ThemeColor.BRICK, Appearance.LIGHT).primary)
    }

    @Test
    fun tooDarkOrTooLightBasesAreAdjusted() {
        val brickDark = themePalette(ThemeColor.BRICK, Appearance.DARK).primary
        val peachLight = themePalette(ThemeColor.PEACH, Appearance.LIGHT).primary
        assertTrue(ColorMath.luminance(brickDark) > ColorMath.luminance(ThemeColor.BRICK.base))
        assertTrue(ColorMath.luminance(peachLight) < ColorMath.luminance(ThemeColor.PEACH.base))
    }
}

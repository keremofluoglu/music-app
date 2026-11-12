package com.example.musicapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Theme colors that have no Material slot; read them with `AppTheme.colors`. */
@Immutable
data class AppColors(
    val primary: Color,
    val primaryText: Color,
    val selected: Color,
    val surface2: Color,
    val line: Color,
    val muted: Color,
    val scrim: Color,
    val success: Color,
)

private fun ThemePalette.toAppColors() = AppColors(
    primary = Color(primary),
    primaryText = Color(primaryText),
    selected = Color(selected),
    surface2 = Color(surface2),
    line = Color(line),
    muted = Color(muted),
    scrim = Color(scrim),
    success = Color(success),
)

private val LocalAppColors = staticCompositionLocalOf {
    themePalette(ThemeColor.LAVENDER, Appearance.DARK).toAppColors()
}

object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}

@Composable
fun MusicAppTheme(
    themeColor: ThemeColor = ThemeColor.LAVENDER,
    appearance: Appearance = Appearance.DARK,
    content: @Composable () -> Unit,
) {
    val p = remember(themeColor, appearance) { themePalette(themeColor, appearance) }
    val scheme = if (appearance == Appearance.DARK) {
        darkColorScheme(
            primary = Color(p.fill), onPrimary = Color(p.onPrimary),
            secondaryContainer = Color(p.selected), onSecondaryContainer = Color(p.text),
            background = Color(p.ground), onBackground = Color(p.text),
            surface = Color(p.ground), onSurface = Color(p.text),
            surfaceVariant = Color(p.surface), onSurfaceVariant = Color(p.muted),
            surfaceContainer = Color(p.surface), surfaceContainerHigh = Color(p.surface2),
            outline = Color(p.line), outlineVariant = Color(p.surface2),
        )
    } else {
        lightColorScheme(
            primary = Color(p.fill), onPrimary = Color(p.onPrimary),
            secondaryContainer = Color(p.selected), onSecondaryContainer = Color(p.text),
            background = Color(p.ground), onBackground = Color(p.text),
            surface = Color(p.ground), onSurface = Color(p.text),
            surfaceVariant = Color(p.surface), onSurfaceVariant = Color(p.muted),
            surfaceContainer = Color(p.surface), surfaceContainerHigh = Color(p.surface2),
            outline = Color(p.line), outlineVariant = Color(p.surface2),
        )
    }
    CompositionLocalProvider(LocalAppColors provides p.toAppColors()) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}

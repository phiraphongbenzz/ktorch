package com.phiraphongbenzz.ktorch.internal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction

/** Foreground and tinted fill for a status badge. */
@Immutable
internal data class Tone(val text: Color, val fill: Color)

@Immutable
internal data class JsonColors(val key: Color, val string: Color, val number: Color, val literal: Color)

/**
 * Apple-style palette. Black and white carry the interface; color only carries meaning: status
 * classes, JSON syntax and search hits. Values are iOS system colors, using Apple's higher-contrast
 * variants for text on light backgrounds so badges stay readable.
 */
@Immutable
internal data class KtorchPalette(
    val isDark: Boolean,
    val background: Color,
    val groupedBackground: Color,
    val card: Color,
    val elevated: Color,
    val segmentThumb: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val pressed: Color,
    val success: Tone,
    val redirect: Tone,
    val clientError: Tone,
    val serverError: Tone,
    val neutral: Tone,
    val json: JsonColors,
    /** Key search: every hit, and the current one, which is drawn with black text. */
    val match: Color,
    val currentMatch: Color,
) {
    fun tone(t: HttpTransaction): Tone {
        val code = t.responseCode
        return when {
            code == null -> if (t.error != null) serverError else neutral
            code < 300 -> success
            code < 400 -> redirect
            code < 500 -> clientError
            else -> serverError
        }
    }
}

private val LightPalette = KtorchPalette(
    isDark = false,
    background = Color(0xFFFFFFFF),
    groupedBackground = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    elevated = Color(0xFFFFFFFF),
    segmentThumb = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x1F767680),
    pressed = Color(0xFFE5E5EA),
    success = Tone(Color(0xFF248A3D), Color(0x2634C759)),
    redirect = Tone(Color(0xFF0040DD), Color(0x1F007AFF)),
    clientError = Tone(Color(0xFFC93400), Color(0x26FF9500)),
    serverError = Tone(Color(0xFFD70015), Color(0x21FF3B30)),
    neutral = Tone(Color(0xFF6C6C70), Color(0x298E8E93)),
    // Xcode "Default (Light)" syntax colors.
    json = JsonColors(
        key = Color(0xFF326D74),
        string = Color(0xFFC41A16),
        number = Color(0xFF1C00CF),
        literal = Color(0xFF9B2393),
    ),
    match = Color(0x59FFCC00),
    currentMatch = Color(0xFFFFCC00),
)

private val DarkPalette = KtorchPalette(
    isDark = true,
    background = Color(0xFF000000),
    groupedBackground = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    elevated = Color(0xFF2C2C2E),
    segmentThumb = Color(0xFF636366),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xA6545458),
    fill = Color(0x3D767680),
    pressed = Color(0xFF2C2C2E),
    success = Tone(Color(0xFF30D158), Color(0x3330D158)),
    redirect = Tone(Color(0xFF409CFF), Color(0x380A84FF)),
    clientError = Tone(Color(0xFFFF9F0A), Color(0x33FF9F0A)),
    serverError = Tone(Color(0xFFFF6961), Color(0x38FF453A)),
    neutral = Tone(Color(0xFFAEAEB2), Color(0x3D8E8E93)),
    // Xcode "Default (Dark)" syntax colors.
    json = JsonColors(
        key = Color(0xFF67B7A4),
        string = Color(0xFFFC6A5D),
        number = Color(0xFFD0BF69),
        literal = Color(0xFFFC5FA3),
    ),
    match = Color(0x4DFFD60A),
    currentMatch = Color(0xFFFFD60A),
)

private val LocalPalette = staticCompositionLocalOf { LightPalette }

internal val palette: KtorchPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalPalette.current

/**
 * iOS text styles at the default Dynamic Type size. SF Pro cannot be shipped on Android, so the
 * system font is used with the same sizes, weights and tracking.
 */
internal object KType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.4).sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.2).sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.1).sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

    /** Tabular figures keep times, sizes and status codes aligned. */
    const val TABULAR = "tnum"
}

@Composable
internal fun KtorchTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    // Monochrome Material scheme for the few Material pieces left (menus, text selection).
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.label, onPrimary = p.background, background = p.background, onBackground = p.label,
            surface = p.elevated, onSurface = p.label, surfaceContainer = p.elevated,
            onSurfaceVariant = p.secondaryLabel, outline = p.separator, outlineVariant = p.separator,
        )
    } else {
        lightColorScheme(
            primary = p.label, onPrimary = p.background, background = p.background, onBackground = p.label,
            surface = p.elevated, onSurface = p.label, surfaceContainer = p.elevated,
            onSurfaceVariant = p.secondaryLabel, outline = p.separator, outlineVariant = p.separator,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalContentColor provides p.label,
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = p.label,
                    backgroundColor = p.label.copy(alpha = 0.18f),
                ),
                content = content,
            )
        }
    }
}

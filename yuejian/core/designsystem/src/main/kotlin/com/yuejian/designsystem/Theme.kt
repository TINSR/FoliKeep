package com.yuejian.designsystem

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable fun YuejianTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Color.White, onPrimary = Color.Black, background = Color.Black,
        onBackground = Color.White, surface = Color.Black, onSurface = Color.White,
        surfaceVariant = Color(0xff191919), onSurfaceVariant = Color(0xffbbbbbb),
        outline = Color(0xff555555), secondary = Color.White
    ) else lightColorScheme(
        primary = Color.Black, onPrimary = Color.White, background = Color.White,
        onBackground = Color.Black, surface = Color.White, onSurface = Color.Black,
        surfaceVariant = Color(0xfff2f2f2), onSurfaceVariant = Color(0xff666666),
        outline = Color(0xffcccccc), secondary = Color.Black
    )
    val foreground = if (dark) Color.White else Color.Black
    val background = if (dark) Color.Black else Color.White
    val soft = if (dark) Color(0xff191919) else Color(0xfff2f2f2)
    MaterialTheme(colorScheme = scheme.copy(
        primaryContainer = soft, onPrimaryContainer = foreground,
        secondaryContainer = soft, onSecondaryContainer = foreground,
        onSecondary = background, tertiary = foreground, onTertiary = background,
        tertiaryContainer = soft, onTertiaryContainer = foreground,
        inverseSurface = foreground, inverseOnSurface = background, inversePrimary = background,
        surfaceTint = foreground, outlineVariant = if (dark) Color(0xff444444) else Color(0xffdddddd),
        surfaceDim = soft, surfaceBright = background,
        surfaceContainerLowest = background, surfaceContainerLow = background,
        surfaceContainer = soft, surfaceContainerHigh = soft, surfaceContainerHighest = soft
    ), content = content)
}

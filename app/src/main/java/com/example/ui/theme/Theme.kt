package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = StudioObsidian,
    primaryContainer = ElectricCyanDim,
    onPrimaryContainer = StudioTextPrimary,
    secondary = AmberKeyframe,
    onSecondary = StudioObsidian,
    secondaryContainer = AmberKeyframeDim,
    onSecondaryContainer = StudioTextPrimary,
    tertiary = CoralPlayhead,
    onTertiary = StudioTextPrimary,
    background = StudioObsidian,
    onBackground = StudioTextPrimary,
    surface = StudioCharcoal,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioPanel,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainerLowest = StudioObsidian,
    surfaceContainerLow = StudioCharcoal,
    surfaceContainer = StudioPanel,
    surfaceContainerHigh = StudioCard,
    surfaceContainerHighest = StudioCardElevated,
    outline = StudioBorder,
    outlineVariant = StudioBorderSubtle,
    error = CoralPlayhead,
    onError = StudioTextPrimary
)

val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun FrameCraftTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = StudioTypography,
        shapes = StudioShapes,
        content = content
    )
}

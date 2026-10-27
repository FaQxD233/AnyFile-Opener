package com.anyfile.x.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand color: the accent in light mode and the background family in dark mode. */
private val Midnight = Color(0xFF1F1E33)

/** The same hue lightened so it stays readable on a dark background. */
private val MidnightLight = Color(0xFFC4C2EA)

private val LightColors = lightColorScheme(
    primary = Midnight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1F7),
    onPrimaryContainer = Midnight,
    inversePrimary = MidnightLight,
    secondary = Color(0xFF5C5B72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E0F0),
    onSecondaryContainer = Color(0xFF1C1B2B),
    // Amber is reserved for warnings such as an extension that does not fit the content.
    tertiary = Color(0xFF7A5900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261900),
    background = Color(0xFFFBFAFF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBFAFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceTint = Midnight,
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2F0F7),
    outline = Color(0xFF78767F),
    outlineVariant = Color(0xFFC8C5D0),
    surfaceBright = Color(0xFFFBFAFF),
    surfaceDim = Color(0xFFDBD9E1),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F3FA),
    surfaceContainer = Color(0xFFEFEDF5),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1EA)
)

private val DarkColors = darkColorScheme(
    primary = MidnightLight,
    onPrimary = Midnight,
    primaryContainer = Color(0xFF3A3954),
    onPrimaryContainer = Color(0xFFE3E1F7),
    inversePrimary = Midnight,
    secondary = Color(0xFFC6C4DB),
    onSecondary = Color(0xFF2E2D41),
    secondaryContainer = Color(0xFF45445A),
    onSecondaryContainer = Color(0xFFE2E0F0),
    tertiary = Color(0xFFF5BF48),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = Midnight,
    onBackground = Color(0xFFE5E3F0),
    surface = Midnight,
    onSurface = Color(0xFFE5E3F0),
    surfaceVariant = Color(0xFF46455C),
    onSurfaceVariant = Color(0xFFC8C5D8),
    surfaceTint = MidnightLight,
    inverseSurface = Color(0xFFE5E3F0),
    inverseOnSurface = Color(0xFF303036),
    outline = Color(0xFF918FA4),
    outlineVariant = Color(0xFF46455C),
    surfaceBright = Color(0xFF45445C),
    surfaceDim = Midnight,
    surfaceContainerLowest = Color(0xFF18172A),
    surfaceContainerLow = Color(0xFF25243A),
    surfaceContainer = Color(0xFF29283F),
    surfaceContainerHigh = Color(0xFF333249),
    surfaceContainerHighest = Color(0xFF3E3D55)
)

/** The app's colors for code outside composition, such as a bottom sheet container. */
fun appColorScheme(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors

/** Light or dark follows the configuration, which [ThemePreference] sets app-wide. */
@Composable
fun AnyFileOpenerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = appColorScheme(darkTheme), content = content)
}

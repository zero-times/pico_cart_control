package com.zerotimes.picocart.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val CyberDarkColorScheme = darkColorScheme(
    primary = Color(0xFF39A7FF),
    onPrimary = Color(0xFF001D35),
    primaryContainer = Color(0xFF063A5D),
    onPrimaryContainer = Color(0xFFD1E9FF),
    secondary = Color(0xFF2EF2FF),
    onSecondary = Color(0xFF00363A),
    secondaryContainer = Color(0xFF014D55),
    onSecondaryContainer = Color(0xFFC6FAFF),
    tertiary = Color(0xFFB8A7FF),
    onTertiary = Color(0xFF211453),
    tertiaryContainer = Color(0xFF392A72),
    onTertiaryContainer = Color(0xFFE7DEFF),
    background = Color(0xFF070A12),
    onBackground = Color(0xFFEAF0FF),
    surface = Color(0xFF101624),
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0xFF141C2E),
    onSurfaceVariant = Color(0xFFB8C3D9),
    outline = Color(0xFF536079),
    outlineVariant = Color(0xFF263248),
    error = Color(0xFFFF7B8F),
    onError = Color(0xFF4D0010),
    errorContainer = Color(0xFF7A1026),
    onErrorContainer = Color(0xFFFFD9DE),
    scrim = Color(0xFF000000),
)

private val CyberLightColorScheme = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE5FF),
    onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF00696F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CF1F8),
    onSecondaryContainer = Color(0xFF002023),
    tertiary = Color(0xFF65558F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF211453),
    background = Color(0xFFF7F9FF),
    onBackground = Color(0xFF171B24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171B24),
    surfaceVariant = Color(0xFFE1E7F5),
    onSurfaceVariant = Color(0xFF424B5D),
    outline = Color(0xFF737D92),
    outlineVariant = Color(0xFFC4CAD8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun PicoCartTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> CyberDarkColorScheme
        else -> CyberLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

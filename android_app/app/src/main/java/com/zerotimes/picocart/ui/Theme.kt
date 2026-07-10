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
    primary = Color(0xFFFACC15),
    onPrimary = Color(0xFF181500),
    primaryContainer = Color(0xFF4B4200),
    onPrimaryContainer = Color(0xFFFFE66A),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF104A43),
    onSecondaryContainer = Color(0xFF9EF2E5),
    tertiary = Color(0xFFFFC857),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5A4208),
    onTertiaryContainer = Color(0xFFFFDEA0),
    background = Color(0xFF0D0F10),
    onBackground = Color(0xFFE7E9E8),
    surface = Color(0xFF151819),
    onSurface = Color(0xFFE7E9E8),
    surfaceVariant = Color(0xFF202425),
    onSurfaceVariant = Color(0xFFB7BEBC),
    outline = Color(0xFF737B79),
    outlineVariant = Color(0xFF343A39),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF450006),
    errorContainer = Color(0xFF65151B),
    onErrorContainer = Color(0xFFFFDADA),
    scrim = Color(0xFF000000),
)

private val CyberLightColorScheme = lightColorScheme(
    primary = Color(0xFF755F00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE16B),
    onPrimaryContainer = Color(0xFF241A00),
    secondary = Color(0xFF006B5F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9EF2E5),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF765A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA0),
    onTertiaryContainer = Color(0xFF251A00),
    background = Color(0xFFF7F9F8),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFE1E5E3),
    onSurfaceVariant = Color(0xFF414846),
    outline = Color(0xFF717976),
    outlineVariant = Color(0xFFC1C9C6),
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

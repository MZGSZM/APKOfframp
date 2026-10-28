package com.mzgszm.apkofframp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mzgszm.apkofframp.data.ThemeMode

// Fallback schemes for Android 8-11 and for dynamic color turned off.
// Built from the logo: petrol (background), pale green-grey (road), amber (arrowhead).
// These are the only fixed colors in the app; screens use color roles only.

val FallbackLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F5F6E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6EAF8),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF4B6268),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE7EE),
    onSecondaryContainer = Color(0xFF061F24),
    tertiary = Color(0xFF825500),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF291800),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FAFC),
    onBackground = Color(0xFF171C1E),
    surface = Color(0xFFF5FAFC),
    onSurface = Color(0xFF171C1E),
    surfaceVariant = Color(0xFFDBE4E7),
    onSurfaceVariant = Color(0xFF3F484B),
    outline = Color(0xFF6F797B),
    outlineVariant = Color(0xFFBFC8CB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF4F6),
    surfaceContainer = Color(0xFFE9EFF1),
    surfaceContainerHigh = Color(0xFFE3E9EB),
    surfaceContainerHighest = Color(0xFFDEE3E5),
)

val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF8BD1E4),
    onPrimary = Color(0xFF003641),
    primaryContainer = Color(0xFF004E5D),
    onPrimaryContainer = Color(0xFFB6EAF8),
    secondary = Color(0xFFB2CBD2),
    onSecondary = Color(0xFF1D343A),
    secondaryContainer = Color(0xFF344A50),
    onSecondaryContainer = Color(0xFFCEE7EE),
    tertiary = Color(0xFFFFB950),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF633F00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1416),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0F1416),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484B),
    onSurfaceVariant = Color(0xFFBFC8CB),
    outline = Color(0xFF899295),
    outlineVariant = Color(0xFF3F484B),
    surfaceContainerLowest = Color(0xFF0A0F11),
    surfaceContainerLow = Color(0xFF171C1E),
    surfaceContainer = Color(0xFF1B2022),
    surfaceContainerHigh = Color(0xFF252B2D),
    surfaceContainerHighest = Color(0xFF303638),
)

@androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun supportsDynamic(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun OfframpTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && supportsDynamic() -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

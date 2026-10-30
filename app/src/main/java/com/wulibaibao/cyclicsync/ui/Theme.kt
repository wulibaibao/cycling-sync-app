package com.wulibaibao.cyclicsync.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// 骑行主题：深青绿（专业/沉稳）+ 活力橙（运动/速度）
val Teal900 = Color(0xFF00363A)
val Teal700 = Color(0xFF00696D)
val Teal400 = Color(0xFF4DDADA)
val Teal100 = Color(0xFFB4EBEA)
val Orange600 = Color(0xFFE8590C)
val Orange300 = Color(0xFFFFB68F)
val Sand50 = Color(0xFFF7FAF8)
val Slate900 = Color(0xFF101C1D)

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = Orange600,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC8),
    onSecondaryContainer = Color(0xFF3A1500),
    tertiary = Color(0xFF4A6265),
    background = Sand50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F484A),
    outline = Color(0xFF6F797A),
    outlineVariant = Color(0xFFBEC9CA),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Teal400,
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Teal100,
    secondary = Orange300,
    onSecondary = Color(0xFF5A1D00),
    secondaryContainer = Color(0xFF833000),
    onSecondaryContainer = Color(0xFFFFDBC9),
    tertiary = Color(0xFFB1C9CB),
    background = Color(0xFF0D1415),
    onBackground = Color(0xFFDDE4E4),
    surface = Color(0xFF141C1D),
    onSurface = Color(0xFFDDE4E4),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBEC9CA),
    outline = Color(0xFF889293),
    outlineVariant = Color(0xFF3F484A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Material 3 Expressive 风格：大圆角 */
private val CyclingShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun CyclingSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, shapes = CyclingShapes, content = content)
}

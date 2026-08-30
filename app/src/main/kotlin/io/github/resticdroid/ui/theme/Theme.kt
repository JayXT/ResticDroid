package io.github.resticdroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF33691E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC5E1A5),
    onPrimaryContainer = Color(0xFF102000),
    secondary = Color(0xFF55624C),
    tertiary = Color(0xFF386667),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFFDFDF5),
    background = Color(0xFFFDFDF5),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFAAD08B),
    onPrimary = Color(0xFF1C3700),
    primaryContainer = Color(0xFF2A4F0A),
    onPrimaryContainer = Color(0xFFC5E1A5),
    secondary = Color(0xFFBCCBB0),
    tertiary = Color(0xFFA0CFD0),
    error = Color(0xFFFFB4AB),
    surface = Color(0xFF11140E),
    background = Color(0xFF11140E),
)

@Composable
fun ResticDroidTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

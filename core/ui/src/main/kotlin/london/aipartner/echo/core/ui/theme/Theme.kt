package london.aipartner.echo.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Echo's Material 3 theme. Dynamic color where available (Android 12+),
 * falling back to a defined, restrained editorial palette + one signature
 * accent. Fleshed out across Phase 6; Phase 0 ships a considered baseline,
 * not the default purple.
 */
@Composable
fun EchoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> EchoDarkColors
        else -> EchoLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = EchoTypography,
        content = content,
    )
}

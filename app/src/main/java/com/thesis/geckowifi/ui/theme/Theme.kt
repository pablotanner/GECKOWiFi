package com.thesis.geckowifi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Spacing scale (4 dp rhythm). Section gaps jump to [xl]/[xxl] so groups read as groups. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radii: small for chips and badges, large for the status hero and sheets. */
object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 24.dp
}

@Composable
fun GeckoWifiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = GeckoTypography,
        content = content
    )
}

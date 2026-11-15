package com.cannonremote.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 应用固定使用暗色主题（相机遥控场景）
private val CanonColorScheme = darkColorScheme(
    primary = Color(0xFFFF4B44), // 快门红
    onPrimary = Color.White,
    secondary = Color(0xFF8AB4F8), // 蓝牙蓝
    onSecondary = Color(0xFF101828),
    tertiary = Color(0xFFFFB300), // 倒计时琥珀色
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1C21),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2A2A31),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFFF7A6E),
    outline = Color(0xFF3A3A42),
)

@Composable
fun CanonRemoteTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // 预留：如需跟随系统可在此切换
    MaterialTheme(
        colorScheme = CanonColorScheme,
        content = content,
    )
}

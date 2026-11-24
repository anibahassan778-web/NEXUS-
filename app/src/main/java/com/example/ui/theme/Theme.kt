package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.model.NexusThemeMode

@Immutable
data class NexusExtendedColors(
    val glassSurface: Color,
    val glassBorder: Color,
    val subtleBorder: Color,
    val cyanGlow: Color,
    val blueAccent: Color,
    val emeraldOnline: Color,
    val amberWarning: Color,
    val bluetoothAccent: Color,
    val incomingBubble: Color,
    val outgoingBubbleStart: Color,
    val outgoingBubbleEnd: Color,
    val isDark: Boolean
)

val LocalNexusColors = staticCompositionLocalOf {
    NexusExtendedColors(
        glassSurface = GlassSurfaceDark,
        glassBorder = GlassBorderDark,
        subtleBorder = SubtleBorderDark,
        cyanGlow = NeonCyan,
        blueAccent = ElectricBlue,
        emeraldOnline = MeshEmerald,
        amberWarning = SignalAmber,
        bluetoothAccent = BluetoothIndigo,
        incomingBubble = CharcoalElevated,
        outgoingBubbleStart = DeepAzure,
        outgoingBubbleEnd = ElectricBlue,
        isDark = true
    )
}

private val CyberCharcoalColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = CharcoalVoid,
    primaryContainer = Color(0xFF003B4A),
    onPrimaryContainer = Color(0xFFB3F5FF),
    secondary = ElectricBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF102A52),
    onSecondaryContainer = Color(0xFFCCE0FF),
    tertiary = MeshEmerald,
    onTertiary = CharcoalVoid,
    background = CharcoalVoid,
    onBackground = TextPrimaryDark,
    surface = CharcoalSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = CharcoalElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = CharcoalVoid,
    surfaceContainerLow = CharcoalSurface,
    surfaceContainer = CharcoalElevated,
    surfaceContainerHigh = CharcoalCard,
    outline = SubtleBorderDark,
    outlineVariant = Color(0xFF1C293B),
    error = AlertCrimson,
    onError = Color.White
)

private val AbyssalOledColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = AbyssalVoid,
    primaryContainer = Color(0xFF00323F),
    onPrimaryContainer = Color(0xFFB3F5FF),
    secondary = ElectricBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF0D2142),
    onSecondaryContainer = Color(0xFFCCE0FF),
    tertiary = MeshEmerald,
    onTertiary = AbyssalVoid,
    background = AbyssalVoid,
    onBackground = TextPrimaryDark,
    surface = AbyssalSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = AbyssalElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = AbyssalVoid,
    surfaceContainerLow = AbyssalSurface,
    surfaceContainer = AbyssalElevated,
    surfaceContainerHigh = Color(0xFF172233),
    outline = Color(0xFF1F2D42),
    outlineVariant = Color(0xFF151E2E),
    error = AlertCrimson,
    onError = Color.White
)

private val ArcticLightColorScheme = lightColorScheme(
    primary = ArcticPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = ArcticSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    background = ArcticBackground,
    onBackground = TextPrimaryLight,
    surface = ArcticSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = ArcticElevated,
    onSurfaceVariant = TextSecondaryLight,
    outline = ArcticBorder,
    error = Color(0xFFDC2626),
    onError = Color.White
)

val NexusShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NexusTheme(
    themeMode: NexusThemeMode = NexusThemeMode.CYBER_CHARCOAL,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        NexusThemeMode.CYBER_CHARCOAL -> CyberCharcoalColorScheme
        NexusThemeMode.ABYSSAL_OLED -> AbyssalOledColorScheme
        NexusThemeMode.ARCTIC_LIGHT -> ArcticLightColorScheme
    }

    val extendedColors = when (themeMode) {
        NexusThemeMode.CYBER_CHARCOAL -> NexusExtendedColors(
            glassSurface = Color(0xD9131C2B),
            glassBorder = Color(0x4D00E5FF),
            subtleBorder = SubtleBorderDark,
            cyanGlow = NeonCyan,
            blueAccent = ElectricBlue,
            emeraldOnline = MeshEmerald,
            amberWarning = SignalAmber,
            bluetoothAccent = BluetoothIndigo,
            incomingBubble = CharcoalElevated,
            outgoingBubbleStart = Color(0xFF0D47A1),
            outgoingBubbleEnd = Color(0xFF0097A7),
            isDark = true
        )
        NexusThemeMode.ABYSSAL_OLED -> NexusExtendedColors(
            glassSurface = Color(0xE60B111A),
            glassBorder = Color(0x5500E5FF),
            subtleBorder = Color(0xFF1D2A3D),
            cyanGlow = NeonCyan,
            blueAccent = ElectricBlue,
            emeraldOnline = MeshEmerald,
            amberWarning = SignalAmber,
            bluetoothAccent = BluetoothIndigo,
            incomingBubble = AbyssalElevated,
            outgoingBubbleStart = Color(0xFF0A3982),
            outgoingBubbleEnd = Color(0xFF007B8A),
            isDark = true
        )
        NexusThemeMode.ARCTIC_LIGHT -> NexusExtendedColors(
            glassSurface = Color(0xE6FFFFFF),
            glassBorder = Color(0x660284C7),
            subtleBorder = ArcticBorder,
            cyanGlow = ArcticPrimary,
            blueAccent = ArcticSecondary,
            emeraldOnline = Color(0xFF059669),
            amberWarning = Color(0xFFD97706),
            bluetoothAccent = Color(0xFF5B21B6),
            incomingBubble = Color(0xFFE2E8F0),
            outgoingBubbleStart = Color(0xFF1D4ED8),
            outgoingBubbleEnd = Color(0xFF0284C7),
            isDark = false
        )
    }

    CompositionLocalProvider(LocalNexusColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexusTypography,
            shapes = NexusShapes,
            content = content
        )
    }
}

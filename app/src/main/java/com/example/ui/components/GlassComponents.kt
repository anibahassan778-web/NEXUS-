package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.model.TransportType
import com.example.ui.theme.AvatarColors
import com.example.ui.theme.LocalNexusColors

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glowBorder: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val nexus = LocalNexusColors.current
    val shape = RoundedCornerShape(20.dp)
    val borderBrush = if (glowBorder) {
        Brush.linearGradient(listOf(nexus.cyanGlow.copy(alpha = 0.65f), nexus.blueAccent.copy(alpha = 0.4f)))
    } else {
        Brush.linearGradient(listOf(nexus.subtleBorder, nexus.subtleBorder.copy(alpha = 0.6f)))
    }

    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = nexus.glassSurface,
        border = BorderStroke(1.dp, borderBrush),
        tonalElevation = 2.dp
    ) {
        Box(content = content)
    }
}

@Composable
fun NodeAvatar(
    label: String,
    avatarIndex: Int,
    isOnline: Boolean = false,
    isGroup: Boolean = false,
    size: Dp = 48.dp
) {
    val nexus = LocalNexusColors.current
    val accent = AvatarColors[kotlin.math.abs(avatarIndex) % AvatarColors.size]
    val initials = label.trim().split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "NX" }

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.28f), nexus.blueAccent.copy(alpha = 0.16f))
                    )
                )
                .border(
                    width = if (isOnline) 1.8.dp else 1.dp,
                    color = if (isOnline) nexus.cyanGlow else accent.copy(alpha = 0.45f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isGroup) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = label,
                    tint = accent,
                    modifier = Modifier.size(size * 0.52f)
                )
            } else {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = accent
                )
            }
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(nexus.emeraldOnline)
            )
        }
    }
}

@Composable
fun TransportBadge(
    transport: TransportType,
    isActive: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val nexus = LocalNexusColors.current
    val (color, icon) = transportVisuals(transport)
    val bgColor = if (isActive) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val borderColor = if (isActive) color.copy(alpha = 0.5f) else nexus.subtleBorder
    val fgColor = if (isActive) color else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 3.dp else 5.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = transport.displayName,
                tint = fgColor,
                modifier = Modifier.size(if (compact) 12.dp else 14.dp)
            )
            Text(
                text = if (compact) transport.shortLabel else transport.displayName,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = fgColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun transportVisuals(transport: TransportType): Pair<Color, ImageVector> {
    val nexus = LocalNexusColors.current
    return when (transport) {
        TransportType.WIFI_DIRECT -> nexus.cyanGlow to Icons.Rounded.Wifi
        TransportType.HOTSPOT -> nexus.blueAccent to Icons.Rounded.WifiTethering
        TransportType.BLUETOOTH -> nexus.bluetoothAccent to Icons.Rounded.Bluetooth
        TransportType.AUTO -> nexus.emeraldOnline to Icons.Rounded.AutoAwesome
    }
}

@Composable
fun SignalQualityBars(
    strengthPercent: Int,
    modifier: Modifier = Modifier
) {
    val nexus = LocalNexusColors.current
    val activeBars = when {
        strengthPercent >= 80 -> 4
        strengthPercent >= 55 -> 3
        strengthPercent >= 30 -> 2
        else -> 1
    }
    val activeColor = when {
        strengthPercent >= 70 -> nexus.emeraldOnline
        strengthPercent >= 40 -> nexus.amberWarning
        else -> MaterialTheme.colorScheme.error
    }

    Canvas(modifier = modifier.size(width = 22.dp, height = 14.dp)) {
        val barWidth = size.width / 5.5f
        val gap = barWidth * 0.5f
        for (i in 0 until 4) {
            val fraction = (i + 1) / 4f
            val barHeight = size.height * fraction
            val left = i * (barWidth + gap)
            val top = size.height - barHeight
            drawRoundRect(
                color = if (i < activeBars) activeColor else nexus.subtleBorder,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

@Composable
fun NexusEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val nexus = LocalNexusColors.current
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(nexus.cyanGlow.copy(alpha = 0.12f))
                    .border(1.dp, nexus.cyanGlow.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = nexus.cyanGlow,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (action != null) {
                Spacer(modifier = Modifier.height(16.dp))
                action()
            }
        }
    }
}

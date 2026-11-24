package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.PeerNode
import com.example.domain.model.RadioHardwareState
import com.example.domain.model.TransportType
import com.example.ui.components.GlassCard
import com.example.ui.components.NexusEmptyState
import com.example.ui.components.NodeAvatar
import com.example.ui.components.SignalQualityBars
import com.example.ui.components.TransportBadge
import com.example.ui.theme.LocalNexusColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NearbyScreen(
    radioState: RadioHardwareState,
    selectedFilter: TransportType,
    discoveredPeers: List<PeerNode>,
    onFilterSelected: (TransportType) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectPeer: (PeerNode) -> Unit,
    onOpenChat: (PeerNode) -> Unit,
    onConnectManualIp: (String, Int) -> Unit,
    onPermissionsUpdated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexus = LocalNexusColors.current
    var showManualIpDialog by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsUpdated()
        onStartScan()
    }

    val filteredPeers = remember(discoveredPeers, selectedFilter) {
        if (selectedFilter == TransportType.AUTO) discoveredPeers
        else discoveredPeers.filter { it.transport == selectedFilter }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header + Manual IP
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Nearby Spectrum",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (radioState.isAnyScanning) stringResource(R.string.nearby_scanning)
                        else stringResource(R.string.nearby_idle),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (radioState.isAnyScanning) nexus.cyanGlow else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = { showManualIpDialog = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("manual_ip_connect_button"),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, nexus.subtleBorder)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lan,
                        contentDescription = "Connect by IP",
                        tint = nexus.cyanGlow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("IP Link", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Animated Radar Discovery Canvas
        item {
            RadarScannerCard(
                isScanning = radioState.isAnyScanning,
                peers = filteredPeers,
                onScanToggle = {
                    if (radioState.isAnyScanning) onStopScan() else onStartScan()
                },
                onPeerBlipClick = { peer ->
                    if (peer.isConnected) onOpenChat(peer) else onConnectPeer(peer)
                }
            )
        }

        // Permission check card if radios lack runtime permissions
        if (!radioState.hasBluetoothPermissions || !radioState.hasWifiNearbyPermissions) {
            item {
                GlassCard(glowBorder = true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = "Radio permissions required",
                            tint = nexus.amberWarning,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Radio Discovery Permissions",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Enable Nearby Wi-Fi and Bluetooth discovery to detect offline NEXUS devices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { permissionLauncher.launch(requiredPermissions) },
                            modifier = Modifier.testTag("grant_radio_permissions_button"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Grant")
                        }
                    }
                }
            }
        }

        // Transport Filter Chips
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val filters = listOf(
                    TransportType.AUTO to "All Radios",
                    TransportType.WIFI_DIRECT to "Wi-Fi Direct",
                    TransportType.HOTSPOT to "Hotspot LAN",
                    TransportType.BLUETOOTH to "Bluetooth"
                )
                items(filters) { (type, label) ->
                    FilterChip(
                        selected = selectedFilter == type,
                        onClick = { onFilterSelected(type) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("filter_${type.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = nexus.cyanGlow.copy(alpha = 0.18f),
                            selectedLabelColor = nexus.cyanGlow
                        )
                    )
                }
            }
        }

        // Discovered Peers List
        if (filteredPeers.isEmpty()) {
            item {
                NexusEmptyState(
                    icon = Icons.Rounded.BluetoothSearching,
                    title = if (radioState.isAnyScanning) "Listening across local bands…" else "No peers discovered yet",
                    subtitle = "Ensure nearby phones have NEXUS open, or connect two phones on the same Local Hotspot and tap Scan Spectrum.",
                    action = {
                        Button(
                            onClick = {
                                if (!radioState.hasBluetoothPermissions || !radioState.hasWifiNearbyPermissions) {
                                    permissionLauncher.launch(requiredPermissions)
                                } else {
                                    onStartScan()
                                }
                            },
                            modifier = Modifier.testTag("empty_scan_button")
                        ) {
                            Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_scan_now))
                        }
                    }
                )
            }
        } else {
            items(filteredPeers, key = { it.peerId }) { peer ->
                DiscoveredPeerCard(
                    peer = peer,
                    onConnect = { onConnectPeer(peer) },
                    onChat = { onOpenChat(peer) }
                )
            }
        }
    }

    if (showManualIpDialog) {
        ManualIpConnectDialog(
            onDismiss = { showManualIpDialog = false },
            onConnect = { ip, port ->
                showManualIpDialog = false
                onConnectManualIp(ip, port)
            }
        )
    }
}

@Composable
private fun RadarScannerCard(
    isScanning: Boolean,
    peers: List<PeerNode>,
    onScanToggle: () -> Unit,
    onPeerBlipClick: (PeerNode) -> Unit
) {
    val nexus = LocalNexusColors.current
    val sweepAngle: Float
    val pulseRadius: Float
    if (isScanning) {
        val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
        sweepAngle = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sweep_angle"
        ).value
        pulseRadius = infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse_radius"
        ).value
    } else {
        sweepAngle = 0f
        pulseRadius = 0.25f
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        glowBorder = isScanning
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = minOf(size.width, size.height) * 0.45f

                    // Concentric range rings
                    listOf(0.33f, 0.66f, 1.0f).forEach { fraction ->
                        drawCircle(
                            color = nexus.cyanGlow.copy(alpha = 0.22f),
                            radius = maxRadius * fraction,
                            center = center,
                            style = Stroke(width = 1.5f)
                        )
                    }

                    // Crosshair lines
                    drawLine(
                        color = nexus.subtleBorder,
                        start = Offset(center.x - maxRadius, center.y),
                        end = Offset(center.x + maxRadius, center.y),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = nexus.subtleBorder,
                        start = Offset(center.x, center.y - maxRadius),
                        end = Offset(center.x, center.y + maxRadius),
                        strokeWidth = 1f
                    )

                    if (isScanning) {
                        // Expanding sonar wave
                        drawCircle(
                            color = nexus.cyanGlow.copy(alpha = (1f - pulseRadius) * 0.4f),
                            radius = maxRadius * pulseRadius,
                            center = center,
                            style = Stroke(width = 2.5f)
                        )
                        // Sweeping radar cone
                        drawArc(
                            brush = Brush.sweepGradient(
                                0.0f to Color.Transparent,
                                0.14f to nexus.cyanGlow.copy(alpha = 0.32f),
                                0.16f to Color.Transparent,
                                center = center
                            ),
                            startAngle = sweepAngle - 55f,
                            sweepAngle = 55f,
                            useCenter = true,
                            topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                            size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2)
                        )
                    }

                    // Center local node
                    drawCircle(
                        color = nexus.cyanGlow.copy(alpha = 0.25f),
                        radius = 16f,
                        center = center
                    )
                    drawCircle(
                        color = nexus.cyanGlow,
                        radius = 7f,
                        center = center
                    )

                    // Peer blips
                    peers.take(8).forEachIndexed { idx, peer ->
                        val angleDeg = (abs(peer.peerId.hashCode()) % 360).toDouble() + idx * 35.0
                        val rad = angleDeg * PI / 180.0
                        val distFactor = (1f - (peer.signalStrength / 115f)).coerceIn(0.28f, 0.92f)
                        val bx = center.x + (maxRadius * distFactor * cos(rad)).toFloat()
                        val by = center.y + (maxRadius * distFactor * sin(rad)).toFloat()
                        val blipColor = if (peer.isConnected) nexus.emeraldOnline else nexus.blueAccent
                        drawCircle(
                            color = blipColor.copy(alpha = 0.28f),
                            radius = 18f,
                            center = Offset(bx, by)
                        )
                        drawCircle(
                            color = blipColor,
                            radius = 8f,
                            center = Offset(bx, by)
                        )
                    }
                }

                // Quick tappable peer tag pills overlaid cleanly at top-right if peers exist
                if (peers.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clickable { onPeerBlipClick(peers.first()) },
                        shape = RoundedCornerShape(999.dp),
                        color = nexus.emeraldOnline.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, nexus.emeraldOnline.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "${peers.size} node${if (peers.size == 1) "" else "s"} in range",
                            style = MaterialTheme.typography.labelSmall,
                            color = nexus.emeraldOnline,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onScanToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("radar_scan_toggle_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) MaterialTheme.colorScheme.surfaceVariant else nexus.cyanGlow,
                    contentColor = if (isScanning) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Rounded.Stop else Icons.Rounded.Radar,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isScanning) stringResource(R.string.action_stop_scan)
                    else stringResource(R.string.action_scan_now)
                )
            }
        }
    }
}

@Composable
private fun DiscoveredPeerCard(
    peer: PeerNode,
    onConnect: () -> Unit,
    onChat: () -> Unit
) {
    val nexus = LocalNexusColors.current
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("discovered_peer_card_${peer.peerId}"),
        glowBorder = peer.isConnected
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NodeAvatar(
                label = peer.displayName,
                avatarIndex = peer.avatarIndex,
                isOnline = peer.isConnected,
                size = 48.dp
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = peer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    SignalQualityBars(strengthPercent = peer.signalStrength)
                }

                Text(
                    text = buildString {
                        append(peer.deviceName)
                        peer.estimatedDistanceMeters?.let {
                            append(" • ~")
                            append(String.format(Locale.US, "%.1f", it))
                            append("m")
                        }
                        append(" • ")
                        append(String.format(Locale.US, "%.0f", peer.bandwidthMbps))
                        append(" Mbps")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                TransportBadge(transport = peer.transport, isActive = peer.isConnected, compact = true)
            }

            if (peer.isConnected) {
                OutlinedButton(
                    onClick = onChat,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("message_peer_button_${peer.peerId}"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, nexus.emeraldOnline.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Connected",
                        tint = nexus.emeraldOnline,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Chat", color = nexus.emeraldOnline)
                }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !peer.isConnecting,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("connect_peer_button_${peer.peerId}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = nexus.blueAccent)
                ) {
                    if (peer.isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = "Connect",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualIpConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (String, Int) -> Unit
) {
    var ipAddress by remember { mutableStateOf("192.168.43.1") }
    var portText by remember { mutableStateOf("8989") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect via Local Hotspot IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Join another NEXUS phone on the same Wi-Fi hotspot or local network directly by IP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("Peer IPv4 Address") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_ip_input")
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Mesh Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: 8989
                    onConnect(ipAddress.trim(), port)
                },
                modifier = Modifier.testTag("confirm_manual_ip_button")
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

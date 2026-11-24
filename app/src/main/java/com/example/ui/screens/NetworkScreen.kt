package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ActiveMeshPath
import com.example.domain.model.MeshMode
import com.example.domain.model.MeshRoute
import com.example.domain.model.NetworkStats
import com.example.domain.model.PeerNode
import com.example.domain.model.TransferItemState
import com.example.domain.model.TransferState
import com.example.domain.model.TransportType
import com.example.domain.model.UserProfile
import com.example.ui.components.GlassCard
import com.example.ui.components.NexusEmptyState
import com.example.ui.components.NodeAvatar
import com.example.ui.components.SignalQualityBars
import com.example.ui.components.TransportBadge
import com.example.ui.theme.LocalNexusColors
import com.example.ui.util.FormatUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NetworkScreen(
    userProfile: UserProfile,
    networkStats: NetworkStats,
    connectedPeers: List<PeerNode>,
    multiHopPeers: List<PeerNode>,
    meshRoutes: Map<String, MeshRoute>,
    topologyEdges: List<Pair<String, String>>,
    activePaths: List<ActiveMeshPath>,
    transfers: List<TransferItemState>,
    onSelectMeshMode: (MeshMode) -> Unit,
    onToggleHotspot: () -> Unit,
    onToggleWifiDirectGroup: () -> Unit,
    onConnectByIp: (String, Int) -> Unit,
    onDisconnectAll: () -> Unit,
    onCancelTransfer: (String) -> Unit,
    onPeerClick: (PeerNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val nexus = LocalNexusColors.current
    var showJoinIpDialog by remember { mutableStateOf(false) }

    val allNodes = remember(connectedPeers, multiHopPeers) {
        (connectedPeers + multiHopPeers).distinctBy { it.peerId }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.network_topology_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = when {
                            networkStats.isHostingHotspot -> "Hosting Local Hotspot • ${networkStats.hotspotSsid ?: "Active"}"
                            networkStats.isHostingWifiDirectGroup -> "Hosting Wi-Fi Direct Group"
                            allNodes.isNotEmpty() -> "${connectedPeers.size} direct • ${multiHopPeers.size} multi-hop nodes"
                            else -> "Standalone node • Port ${networkStats.localPort} listening"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (networkStats.isHostingHotspot || allNodes.isNotEmpty()) nexus.emeraldOnline
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (connectedPeers.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onDisconnectAll,
                        modifier = Modifier.testTag("disconnect_all_button"),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Disconnect all", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Drop", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // Mesh Mode Selector Chips
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MeshMode.entries) { mode ->
                    val selected = userProfile.meshMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectMeshMode(mode) },
                        label = { Text(mode.label) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Route, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("mesh_mode_${mode.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = nexus.cyanGlow.copy(alpha = 0.18f),
                            selectedLabelColor = nexus.cyanGlow,
                            selectedLeadingIconColor = nexus.cyanGlow
                        )
                    )
                }
            }
        }

        // Animated Multi-Hop Topology Graph
        item {
            MeshTopologyDiagramCard(
                localNodeId = userProfile.nodeId,
                localName = userProfile.nickname,
                isHost = networkStats.isHostingHotspot || networkStats.isHostingWifiDirectGroup,
                connectedPeers = connectedPeers,
                multiHopPeers = multiHopPeers,
                topologyEdges = topologyEdges,
                activePaths = activePaths
            )
        }

        if (networkStats.isHostingHotspot) {
            item {
                GlassCard(glowBorder = true) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Hotspot Host Credentials", style = MaterialTheme.typography.titleMedium, color = nexus.cyanGlow)
                        Text(
                            text = "Nearby phones join this Wi-Fi network without internet, then open NEXUS:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SSID: ${networkStats.hotspotSsid ?: "NEXUS-AP"}", style = MaterialTheme.typography.labelMedium)
                            Text("Pass: ${networkStats.hotspotPassphrase ?: "—"}", style = MaterialTheme.typography.labelMedium, color = nexus.emeraldOnline)
                        }
                    }
                }
            }
        }

        // Network Hosting & Join Controls
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onToggleHotspot,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("host_hotspot_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (networkStats.isHostingHotspot) nexus.emeraldOnline else nexus.blueAccent
                        )
                    ) {
                        Icon(Icons.Rounded.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (networkStats.isHostingHotspot) "Stop Hotspot" else "Host Hotspot", maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onToggleWifiDirectGroup,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("host_wifi_direct_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (networkStats.isHostingWifiDirectGroup) nexus.cyanGlow else nexus.subtleBorder)
                    ) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = nexus.cyanGlow, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (networkStats.isHostingWifiDirectGroup) "Close P2P" else "P2P Group", maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = { showJoinIpDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("join_network_ip_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Lan, contentDescription = null, tint = nexus.cyanGlow, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.network_join_by_ip))
                }
            }
        }

        // Traffic & Routing Telemetry Grid
        item {
            GlassCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mesh Telemetry", style = MaterialTheme.typography.titleMedium)
                        TransportBadge(transport = networkStats.activeTransport, isActive = true)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetworkStatBox("SENT", FormatUtils.formatBytes(networkStats.bytesSent), Modifier.weight(1f))
                        NetworkStatBox("RECEIVED", FormatUtils.formatBytes(networkStats.bytesReceived), Modifier.weight(1f))
                        NetworkStatBox("RELAYED", "${networkStats.packetsRelayed} pkts", Modifier.weight(1f))
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetworkStatBox("ROUTES", "${meshRoutes.size} active", Modifier.weight(1f))
                        NetworkStatBox("STORE QUEUE", "${networkStats.queuedRelayCount} pending", Modifier.weight(1f))
                        NetworkStatBox(
                            "LATENCY",
                            if (networkStats.avgLatencyMs > 0) "${networkStats.avgLatencyMs} ms" else "—",
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Active / Recent File Transfers Queue
        if (transfers.isNotEmpty()) {
            item {
                Text("Transfer Queue (${transfers.size})", style = MaterialTheme.typography.titleLarge)
            }
            items(transfers.take(6), key = { it.transferId }) { tx ->
                GlassCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tx.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (tx.state == TransferState.IN_PROGRESS) {
                                IconButton(onClick = { onCancelTransfer(tx.transferId) }) {
                                    Icon(Icons.Rounded.Cancel, contentDescription = "Cancel transfer", tint = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                Text(tx.state.name, style = MaterialTheme.typography.labelSmall, color = nexus.emeraldOnline)
                            }
                        }
                        LinearProgressIndicator(
                            progress = { tx.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = nexus.cyanGlow
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${tx.progressPercent}% • ${FormatUtils.formatBytes(tx.totalBytes)}", style = MaterialTheme.typography.labelSmall)
                            if (tx.speedBytesPerSec > 0) {
                                Text("${FormatUtils.formatBytes(tx.speedBytesPerSec)}/s", style = MaterialTheme.typography.labelSmall, color = nexus.cyanGlow)
                            }
                        }
                    }
                }
            }
        }

        // Reachable Nodes (Direct & Multi-Hop)
        item {
            Text(
                text = "Mesh Nodes (${allNodes.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (allNodes.isEmpty()) {
            item {
                NexusEmptyState(
                    icon = Icons.Rounded.Devices,
                    title = "Local Mesh Standing By",
                    subtitle = "Host a Local Hotspot, create a Wi-Fi Direct group, or link with nearby devices to populate the decentralized mesh topology."
                )
            }
        } else {
            items(allNodes, key = { it.peerId }) { peer ->
                val route = meshRoutes[peer.peerId]
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowBorder = peer.isConnected,
                    onClick = { onPeerClick(peer) }
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
                            isOnline = true,
                            size = 44.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peer.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = if (route != null && route.hopCount > 1) {
                                    "Route (${route.hopCount} hops): ${route.path.joinToString(" → ")}"
                                } else {
                                    "${peer.ipAddress ?: peer.bluetoothAddress ?: peer.deviceName} • ${peer.bandwidthMbps.toInt()} Mbps"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SignalQualityBars(strengthPercent = peer.signalStrength)
                            TransportBadge(transport = peer.transport, compact = true)
                        }
                    }
                }
            }
        }
    }

    if (showJoinIpDialog) {
        var ip by remember { mutableStateOf("192.168.43.1") }
        var port by remember { mutableStateOf("8989") }
        AlertDialog(
            onDismissRequest = { showJoinIpDialog = false },
            title = { Text("Join Local Mesh Node") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Host IPv4 Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } }, label = { Text("TCP Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    showJoinIpDialog = false
                    onConnectByIp(ip, port.toIntOrNull() ?: 8989)
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showJoinIpDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MeshTopologyDiagramCard(
    localNodeId: String,
    localName: String,
    isHost: Boolean,
    connectedPeers: List<PeerNode>,
    multiHopPeers: List<PeerNode>,
    topologyEdges: List<Pair<String, String>>,
    activePaths: List<ActiveMeshPath>
) {
    val nexus = LocalNexusColors.current
    val packetProgress = if (activePaths.isNotEmpty()) {
        val infiniteTransition = rememberInfiniteTransition(label = "mesh_packet_flow")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart),
            label = "packet_progress"
        ).value
    } else {
        0f
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        glowBorder = connectedPeers.isNotEmpty() || isHost
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Mesh Graph", style = MaterialTheme.typography.titleMedium)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isHost) nexus.emeraldOnline.copy(alpha = 0.16f) else nexus.cyanGlow.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = if (isHost) "HOST ($localName)" else "NODE ($localName)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isHost) nexus.emeraldOnline else nexus.cyanGlow,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(235.dp)
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val innerOrbit = minOf(size.width, size.height) * 0.27f
                val outerOrbit = minOf(size.width, size.height) * 0.43f

                drawCircle(
                    color = nexus.subtleBorder.copy(alpha = 0.5f),
                    radius = innerOrbit,
                    center = center,
                    style = Stroke(width = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                )
                if (multiHopPeers.isNotEmpty()) {
                    drawCircle(
                        color = nexus.blueAccent.copy(alpha = 0.35f),
                        radius = outerOrbit,
                        center = center,
                        style = Stroke(width = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f)))
                    )
                }

                val nodePositions = mutableMapOf<String, Offset>()
                nodePositions[localNodeId] = center

                connectedPeers.forEachIndexed { idx, peer ->
                    val angle = (2 * PI * idx / connectedPeers.size.coerceAtLeast(1)) - PI / 2
                    val pos = Offset(
                        center.x + (innerOrbit * cos(angle)).toFloat(),
                        center.y + (innerOrbit * sin(angle)).toFloat()
                    )
                    nodePositions[peer.peerId] = pos
                }

                multiHopPeers.forEachIndexed { idx, peer ->
                    val angle = (2 * PI * idx / multiHopPeers.size.coerceAtLeast(1)) - PI / 4
                    val pos = Offset(
                        center.x + (outerOrbit * cos(angle)).toFloat(),
                        center.y + (outerOrbit * sin(angle)).toFloat()
                    )
                    nodePositions[peer.peerId] = pos
                }

                // Draw edges (direct + multi-hop relay links)
                topologyEdges.forEach { (a, b) ->
                    val posA = nodePositions[a] ?: return@forEach
                    val posB = nodePositions[b] ?: return@forEach
                    val isDirect = a == localNodeId || b == localNodeId
                    drawLine(
                        color = if (isDirect) nexus.cyanGlow.copy(alpha = 0.55f) else nexus.blueAccent.copy(alpha = 0.45f),
                        start = posA,
                        end = posB,
                        strokeWidth = if (isDirect) 2.8f else 2f,
                        pathEffect = if (isDirect) null else PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                    )
                }

                // Animate active packet flow along paths
                activePaths.forEach { path ->
                    val linkColor = when (path.transport) {
                        TransportType.WIFI_DIRECT -> nexus.cyanGlow
                        TransportType.HOTSPOT -> nexus.blueAccent
                        TransportType.BLUETOOTH -> nexus.bluetoothAccent
                        TransportType.AUTO -> nexus.emeraldOnline
                    }
                    for (i in 0 until path.hops.size - 1) {
                        val p1 = nodePositions[path.hops[i]] ?: continue
                        val p2 = nodePositions[path.hops[i + 1]] ?: continue
                        val px = p1.x + (p2.x - p1.x) * packetProgress
                        val py = p1.y + (p2.y - p1.y) * packetProgress
                        drawCircle(color = linkColor, radius = 5.5f, center = Offset(px, py))
                    }
                }

                // Draw direct peer nodes
                connectedPeers.forEach { peer ->
                    val pos = nodePositions[peer.peerId] ?: return@forEach
                    val color = when (peer.transport) {
                        TransportType.WIFI_DIRECT -> nexus.cyanGlow
                        TransportType.HOTSPOT -> nexus.blueAccent
                        TransportType.BLUETOOTH -> nexus.bluetoothAccent
                        TransportType.AUTO -> nexus.emeraldOnline
                    }
                    drawCircle(color = color.copy(alpha = 0.25f), radius = 22f, center = pos)
                    drawCircle(color = color, radius = 11f, center = pos)
                }

                // Draw multi-hop nodes
                multiHopPeers.forEach { peer ->
                    val pos = nodePositions[peer.peerId] ?: return@forEach
                    drawCircle(color = nexus.amberWarning.copy(alpha = 0.22f), radius = 19f, center = pos)
                    drawCircle(color = nexus.amberWarning, radius = 9f, center = pos)
                }

                // Draw local center node
                val centerColor = if (isHost) nexus.emeraldOnline else nexus.cyanGlow
                drawCircle(color = centerColor.copy(alpha = 0.25f), radius = 28f, center = center)
                drawCircle(color = centerColor, radius = 14f, center = center)
            }
        }
    }
}

@Composable
private fun NetworkStatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, LocalNexusColors.current.subtleBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

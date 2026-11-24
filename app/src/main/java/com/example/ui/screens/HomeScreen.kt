package com.example.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.CallRecordEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.ReceivedFileEntity
import com.example.domain.model.CallDirection
import com.example.domain.model.CallType
import com.example.domain.model.NetworkStats
import com.example.domain.model.PeerNode
import com.example.domain.model.RadioHardwareState
import com.example.domain.model.TransportType
import com.example.domain.model.UserProfile
import com.example.ui.components.GlassCard
import com.example.ui.components.NexusEmptyState
import com.example.ui.components.NodeAvatar
import com.example.ui.components.SignalQualityBars
import com.example.ui.components.TransportBadge
import com.example.ui.theme.LocalNexusColors
import com.example.ui.util.FormatUtils
import java.io.File

@Composable
fun HomeScreen(
    userProfile: UserProfile,
    networkStats: NetworkStats,
    radioState: RadioHardwareState,
    connectedPeers: List<PeerNode>,
    multiHopPeers: List<PeerNode>,
    discoveredPeers: List<PeerNode>,
    conversations: List<ConversationEntity>,
    allConversations: List<ConversationEntity>,
    callHistory: List<CallRecordEntity>,
    receivedFiles: List<ReceivedFileEntity>,
    onConnectNearbyClick: () -> Unit,
    onSelectTransport: (TransportType) -> Unit,
    onPeerClick: (PeerNode) -> Unit,
    onConversationClick: (String) -> Unit,
    onCreateGroup: (title: String, peerIds: List<String>) -> Unit,
    onArchiveConversation: (String, Boolean) -> Unit,
    onMuteConversation: (String, Boolean) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onStartCallFromHistory: (CallRecordEntity) -> Unit,
    onClearCallHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexus = LocalNexusColors.current
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showCallHistoryDialog by remember { mutableStateOf(false) }
    var showMediaGalleryDialog by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val missedCallsCount = remember(callHistory) {
        callHistory.count { it.direction == CallDirection.MISSED.name }
    }

    val visibleConversations = remember(conversations, allConversations, showArchived, searchQuery) {
        val base = if (showArchived) allConversations else conversations
        if (searchQuery.isBlank()) base
        else base.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.lastMessagePreview.contains(searchQuery, ignoreCase = true)
        }
    }

    val allMeshNodes = remember(connectedPeers, multiHopPeers, discoveredPeers) {
        (connectedPeers + multiHopPeers + discoveredPeers).distinctBy { it.peerId }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Brand Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = nexus.emeraldOnline.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, nexus.emeraldOnline.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = networkStats.meshMode.label.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = nexus.emeraldOnline,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, nexus.subtleBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = userProfile.nickname,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = userProfile.nodeId,
                                style = MaterialTheme.typography.labelSmall,
                                color = nexus.cyanGlow
                            )
                        }
                    }
                }
            }

            // Network Status Panel
            item {
                NetworkStatusPanel(
                    networkStats = networkStats,
                    radioState = radioState,
                    onSelectTransport = onSelectTransport
                )
            }

            // Large Central "Connect Nearby" Hero CTA
            item {
                ConnectNearbyHeroButton(
                    isScanning = radioState.isAnyScanning,
                    discoveredCount = discoveredPeers.size,
                    connectedCount = connectedPeers.size + multiHopPeers.size,
                    onClick = onConnectNearbyClick
                )
            }

            // Quick Utility Row: Call History & Media Gallery
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("open_call_history_button"),
                        onClick = { showCallHistoryDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = "Call history",
                                    tint = nexus.cyanGlow,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Call History", style = MaterialTheme.typography.labelLarge)
                            }
                            if (missedCallsCount > 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.error
                                ) {
                                    Text(
                                        text = missedCallsCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    GlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("open_media_gallery_button"),
                        onClick = { showMediaGalleryDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PhotoLibrary,
                                    contentDescription = "Media gallery",
                                    tint = nexus.blueAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Media (${receivedFiles.size})", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            // Connected & Multi-Hop Mesh Nodes Strip
            item {
                SectionHeader(
                    title = stringResource(R.string.section_connected_peers),
                    trailingLabel = "${connectedPeers.size} direct • ${multiHopPeers.size} multi-hop"
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (allMeshNodes.isEmpty()) {
                    NexusEmptyState(
                        icon = Icons.Rounded.Sensors,
                        title = stringResource(R.string.empty_peers_title),
                        subtitle = stringResource(R.string.empty_peers_subtitle)
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(allMeshNodes, key = { it.peerId }) { peer ->
                            PeerQuickCard(
                                peer = peer,
                                onClick = { onPeerClick(peer) }
                            )
                        }
                    }
                }
            }

            // Conversations Header + Search + New Group
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.section_recent_chats),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (allConversations.size > conversations.size) {
                                TextButton(onClick = { showArchived = !showArchived }) {
                                    Text(if (showArchived) "Active" else "Archived")
                                }
                            }
                            OutlinedButton(
                                onClick = { showCreateGroupDialog = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("new_group_button"),
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, nexus.cyanGlow.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GroupAdd,
                                    contentDescription = "New group channel",
                                    tint = nexus.cyanGlow,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "New Group",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = nexus.cyanGlow
                                )
                            }
                        }
                    }

                    if (allConversations.isNotEmpty()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search conversations…") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = "Search")
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("conversation_search_input")
                        )
                    }
                }
            }

            if (visibleConversations.isEmpty()) {
                item {
                    NexusEmptyState(
                        icon = Icons.AutoMirrored.Rounded.Chat,
                        title = if (searchQuery.isNotBlank()) "No matching threads"
                        else stringResource(R.string.empty_chats_title),
                        subtitle = if (searchQuery.isNotBlank()) "Try a different keyword."
                        else stringResource(R.string.empty_chats_subtitle),
                        action = {
                            if (searchQuery.isBlank()) {
                                OutlinedButton(
                                    onClick = { showCreateGroupDialog = true },
                                    modifier = Modifier.testTag("create_first_group_button"),
                                    border = BorderStroke(1.dp, nexus.cyanGlow.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, tint = nexus.cyanGlow, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Create Local Group Channel", color = nexus.cyanGlow)
                                }
                            }
                        }
                    )
                }
            } else {
                items(visibleConversations, key = { it.id }) { conv ->
                    ConversationRowCard(
                        conversation = conv,
                        onClick = { onConversationClick(conv.id) },
                        onArchive = { onArchiveConversation(conv.id, !conv.isArchived) },
                        onMute = { onMuteConversation(conv.id, !conv.isMuted) },
                        onDelete = { onDeleteConversation(conv.id) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onConnectNearbyClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp)
                .testTag("discover_devices_fab"),
            containerColor = nexus.cyanGlow,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Rounded.Radar,
                contentDescription = stringResource(R.string.action_discover_devices)
            )
        }
    }

    if (showCreateGroupDialog) {
        CreateGroupChannelDialog(
            availablePeers = allMeshNodes,
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { title, peerIds ->
                showCreateGroupDialog = false
                onCreateGroup(title, peerIds)
            }
        )
    }

    if (showCallHistoryDialog) {
        CallHistoryDialog(
            records = callHistory,
            onDismiss = { showCallHistoryDialog = false },
            onCallBack = { rec ->
                showCallHistoryDialog = false
                onStartCallFromHistory(rec)
            },
            onClear = onClearCallHistory
        )
    }

    if (showMediaGalleryDialog) {
        MediaGalleryDialog(
            files = receivedFiles,
            onDismiss = { showMediaGalleryDialog = false }
        )
    }
}

@Composable
private fun NetworkStatusPanel(
    networkStats: NetworkStats,
    radioState: RadioHardwareState,
    onSelectTransport: (TransportType) -> Unit
) {
    val nexus = LocalNexusColors.current

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        glowBorder = networkStats.connectedPeerCount > 0 || networkStats.isHostingHotspot
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_mesh_backdrop_1795474509921),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(if (nexus.isDark) 0.24f else 0.08f)
            )

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Hub,
                            contentDescription = "Local mesh status",
                            tint = nexus.cyanGlow,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Local Mesh Status",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TransportBadge(transport = networkStats.activeTransport, isActive = true)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniStat(
                        label = "LOCAL IP",
                        value = "${networkStats.localIpAddress}:${networkStats.localPort}",
                        modifier = Modifier.weight(1.3f)
                    )
                    TelemetryMiniStat(
                        label = "MESH NODES",
                        value = "${networkStats.connectedPeerCount + networkStats.reachableMultiHopCount} active",
                        modifier = Modifier.weight(0.85f)
                    )
                    TelemetryMiniStat(
                        label = "QUEUED",
                        value = "${networkStats.queuedRelayCount} relay",
                        modifier = Modifier.weight(0.75f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModeIndicatorChip(
                        label = "Wi-Fi P2P",
                        icon = Icons.Rounded.Wifi,
                        available = radioState.wifiDirectSupported && radioState.wifiEnabled,
                        selected = networkStats.activeTransport == TransportType.WIFI_DIRECT,
                        onClick = { onSelectTransport(TransportType.WIFI_DIRECT) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_wifi_direct")
                    )
                    ModeIndicatorChip(
                        label = "Hotspot",
                        icon = Icons.Rounded.WifiTethering,
                        available = radioState.wifiEnabled || networkStats.isHostingHotspot,
                        selected = networkStats.activeTransport == TransportType.HOTSPOT,
                        onClick = { onSelectTransport(TransportType.HOTSPOT) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_hotspot")
                    )
                    ModeIndicatorChip(
                        label = "Bluetooth",
                        icon = Icons.Rounded.Bluetooth,
                        available = radioState.bluetoothSupported && radioState.bluetoothEnabled,
                        selected = networkStats.activeTransport == TransportType.BLUETOOTH,
                        onClick = { onSelectTransport(TransportType.BLUETOOTH) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_bluetooth")
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryMiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val nexus = LocalNexusColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .border(1.dp, nexus.subtleBorder.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ModeIndicatorChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    available: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexus = LocalNexusColors.current
    val tint = when {
        selected -> nexus.cyanGlow
        available -> nexus.emeraldOnline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) nexus.cyanGlow.copy(alpha = 0.14f) else MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (selected) nexus.cyanGlow.copy(alpha = 0.65f) else nexus.subtleBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConnectNearbyHeroButton(
    isScanning: Boolean,
    discoveredCount: Int,
    connectedCount: Int,
    onClick: () -> Unit
) {
    val nexus = LocalNexusColors.current
    val pulseScale = if (isScanning) {
        val infiniteTransition = rememberInfiniteTransition(label = "connect_pulse")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.035f,
            animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
            label = "pulse_scale"
        ).value
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pulseScale)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(nexus.blueAccent, nexus.cyanGlow)))
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .testTag("connect_nearby_button")
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CellTower,
                        contentDescription = stringResource(R.string.action_connect_nearby),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.action_connect_nearby),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = when {
                            isScanning -> "Scanning local radio spectrum ($discoveredCount found)…"
                            connectedCount > 0 -> "$connectedCount mesh node(s) reachable • Tap to expand"
                            else -> "Bluetooth • Wi-Fi Direct • Local Hotspot"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                }
            }
            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.2f)) {
                Icon(
                    imageVector = Icons.Rounded.Radar,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PeerQuickCard(
    peer: PeerNode,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .width(168.dp)
            .testTag("peer_quick_card_${peer.peerId}"),
        glowBorder = peer.isConnected,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                NodeAvatar(
                    label = peer.displayName,
                    avatarIndex = peer.avatarIndex,
                    isOnline = peer.isConnected,
                    size = 42.dp
                )
                SignalQualityBars(strengthPercent = peer.signalStrength)
            }
            Column {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        peer.hopCount > 1 -> "${peer.hopCount}-hop relay"
                        peer.isConnected -> "Direct link"
                        else -> "Nearby"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TransportBadge(transport = peer.transport, isActive = peer.isConnected, compact = true)
        }
    }
}

@Composable
private fun ConversationRowCard(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onMute: () -> Unit,
    onDelete: () -> Unit
) {
    val nexus = LocalNexusColors.current
    val transport = runCatching { TransportType.valueOf(conversation.preferredTransport) }
        .getOrDefault(TransportType.WIFI_DIRECT)
    var menuExpanded by remember { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("conversation_card_${conversation.id}"),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NodeAvatar(
                label = conversation.title,
                avatarIndex = conversation.avatarIndex,
                isOnline = !conversation.isGroup,
                isGroup = conversation.isGroup,
                size = 50.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = conversation.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (conversation.isMuted) {
                            Icon(
                                imageVector = Icons.Rounded.NotificationsOff,
                                contentDescription = "Muted",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = FormatUtils.formatTimestamp(conversation.lastMessageTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessagePreview.ifBlank { "No messages yet" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (conversation.unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(nexus.cyanGlow)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        TransportBadge(transport = transport, compact = true)
                    }
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Conversation actions")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (conversation.isMuted) "Unmute" else "Mute") },
                        leadingIcon = { Icon(Icons.Rounded.NotificationsOff, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onMute()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (conversation.isArchived) "Unarchive" else "Archive") },
                        leadingIcon = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onArchive()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Thread", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailingLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(text = trailingLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreateGroupChannelDialog(
    availablePeers: List<PeerNode>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit
) {
    var groupTitle by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Offline Group Channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupTitle,
                    onValueChange = { groupTitle = it },
                    label = { Text("Channel Name") },
                    placeholder = { Text("e.g., Field Ops Alpha") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("group_title_input")
                )
                Text(
                    text = if (availablePeers.isEmpty()) {
                        "No linked peers yet—you can still create the local channel and peers will join once connected."
                    } else "Select peers to include:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                availablePeers.forEach { peer ->
                    val checked = peer.peerId in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (checked) selectedIds.remove(peer.peerId) else selectedIds.add(peer.peerId)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                if (it) selectedIds.add(peer.peerId) else selectedIds.remove(peer.peerId)
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(peer.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(groupTitle, selectedIds.toList()) },
                modifier = Modifier.testTag("confirm_create_group_button")
            ) { Text("Create Channel") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CallHistoryDialog(
    records: List<CallRecordEntity>,
    onDismiss: () -> Unit,
    onCallBack: (CallRecordEntity) -> Unit,
    onClear: () -> Unit
) {
    val nexus = LocalNexusColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Offline Call History")
                if (records.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
        },
        text = {
            if (records.isEmpty()) {
                Text(
                    text = "No offline voice or video calls logged yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(records, key = { it.id }) { rec ->
                        val dir = runCatching { CallDirection.valueOf(rec.direction) }.getOrDefault(CallDirection.OUTGOING)
                        val type = runCatching { CallType.valueOf(rec.callType) }.getOrDefault(CallType.VOICE)
                        GlassCard(onClick = { onCallBack(rec) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = when (dir) {
                                        CallDirection.MISSED -> Icons.AutoMirrored.Rounded.CallMissed
                                        CallDirection.INCOMING -> Icons.AutoMirrored.Rounded.CallReceived
                                        CallDirection.OUTGOING -> Icons.AutoMirrored.Rounded.CallMade
                                    },
                                    contentDescription = dir.name,
                                    tint = if (dir == CallDirection.MISSED) MaterialTheme.colorScheme.error else nexus.emeraldOnline
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rec.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = "${FormatUtils.formatDateTime(rec.timestamp)} • ${FormatUtils.formatDuration(rec.durationSeconds * 1000L)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = if (type == CallType.VIDEO) Icons.Rounded.Videocam else Icons.Rounded.Call,
                                    contentDescription = type.name,
                                    tint = nexus.cyanGlow
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun MediaGalleryDialog(
    files: List<ReceivedFileEntity>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline Media Gallery") },
        text = {
            if (files.isEmpty()) {
                Text(
                    text = "Received photos, videos, and files appear here automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.height(320.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(files, key = { it.id }) { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(86.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            if (item.isImage && File(item.filePath).exists()) {
                                AsyncImage(
                                    model = File(item.filePath),
                                    contentDescription = item.fileName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = item.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalNexusColors.current.cyanGlow
                                    )
                                    Text(
                                        text = item.fileName,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

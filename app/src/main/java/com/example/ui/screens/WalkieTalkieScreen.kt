package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.domain.model.PeerNode
import com.example.domain.model.WalkieTalkieState
import com.example.ui.components.GlassCard
import com.example.ui.components.NodeAvatar
import com.example.ui.components.TransportBadge
import com.example.ui.theme.LocalNexusColors

@Composable
fun WalkieTalkieScreen(
    pttState: WalkieTalkieState,
    connectedPeers: List<PeerNode>,
    multiHopPeers: List<PeerNode>,
    onSelectChannel: (String) -> Unit,
    onAddChannel: (String) -> Unit,
    onEnableListening: (Boolean) -> Unit,
    onSetTransmitting: (Boolean) -> Unit,
    onToggleLatch: () -> Unit,
    onToggleLowBandwidth: () -> Unit,
    onToggleMeshForwarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nexus = LocalNexusColors.current
    var showAddChannelDialog by remember { mutableStateOf(false) }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onEnableListening(true)
    }

    DisposableEffect(Unit) {
        onEnableListening(true)
        onDispose {
            onSetTransmitting(false)
            onEnableListening(false)
        }
    }

    val txPulse = if (pttState.isTransmitting) {
        val pulseTransition = rememberInfiniteTransition(label = "ptt_tx_pulse")
        pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.07f,
            animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
            label = "tx_pulse"
        ).value
    } else {
        1f
    }

    val allParticipants = remember(connectedPeers, multiHopPeers) {
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
                Column {
                    Text(
                        text = stringResource(R.string.ptt_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Instant half-duplex offline audio over Mesh & LAN",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { showAddChannelDialog = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("add_ptt_channel_button"),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add channel", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Channel")
                }
            }
        }

        // Channel Selector
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pttState.availableChannels) { channel ->
                    val selected = pttState.activeChannel == channel
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectChannel(channel) },
                        label = { Text(channel) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Radio,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = nexus.cyanGlow.copy(alpha = 0.2f),
                            selectedLabelColor = nexus.cyanGlow,
                            selectedLeadingIconColor = nexus.cyanGlow
                        )
                    )
                }
            }
        }

        // Live Audio Activity + Push-to-Talk Console
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowBorder = pttState.isTransmitting || pttState.activeSpeakerName != null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (pttState.isTransmitting) nexus.emeraldOnline.copy(alpha = 0.2f)
                            else nexus.cyanGlow.copy(alpha = 0.14f),
                            border = BorderStroke(
                                1.dp,
                                if (pttState.isTransmitting) nexus.emeraldOnline else nexus.cyanGlow.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = when {
                                    pttState.isTransmitting -> stringResource(R.string.ptt_transmitting)
                                    pttState.activeSpeakerName != null -> "RX: ${pttState.activeSpeakerName}"
                                    else -> "MONITORING ${pttState.activeChannel}"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (pttState.isTransmitting) nexus.emeraldOnline else nexus.cyanGlow,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }

                        Text(
                            text = if (pttState.lowBandwidthCodec) "8-bit Low-BW" else "16kHz PCM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Audio Activity Meter Bars
                    val activityLevel = if (pttState.isTransmitting) pttState.txLevel else pttState.rxLevel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (i in 0 until 20) {
                            val threshold = i / 20f
                            val active = activityLevel > threshold
                            val barColor = when {
                                !active -> nexus.subtleBorder.copy(alpha = 0.45f)
                                i > 15 -> nexus.amberWarning
                                pttState.isTransmitting -> nexus.emeraldOnline
                                else -> nexus.cyanGlow
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height((12 + (if (active) 24 else 4)).dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(barColor)
                            )
                        }
                    }

                    // Large Circular Hold-to-Talk Button
                    Box(
                        modifier = Modifier
                            .size(172.dp)
                            .scale(if (pttState.isTransmitting) txPulse else 1f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    if (pttState.isTransmitting) {
                                        listOf(nexus.emeraldOnline, Color(0xFF00796B))
                                    } else {
                                        listOf(nexus.blueAccent.copy(alpha = 0.85f), Color(0xFF102A52))
                                    }
                                )
                            )
                            .border(
                                width = 3.dp,
                                color = if (pttState.isTransmitting) nexus.emeraldOnline else nexus.cyanGlow,
                                shape = CircleShape
                            )
                            .pointerInput(pttState.isLatched) {
                                detectTapGestures(
                                    onPress = {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!granted) {
                                            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@detectTapGestures
                                        }
                                        if (!pttState.isLatched) {
                                            onSetTransmitting(true)
                                            tryAwaitRelease()
                                            onSetTransmitting(false)
                                        }
                                    }
                                )
                            }
                            .testTag("ptt_hold_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (pttState.isTransmitting) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                                contentDescription = stringResource(R.string.ptt_hold_hint),
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (pttState.isTransmitting) "ON AIR" else "HOLD TO TALK",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    onToggleLatch()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("ptt_latch_button"),
                            border = BorderStroke(
                                1.dp,
                                if (pttState.isLatched) nexus.emeraldOnline else nexus.subtleBorder
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = if (pttState.isLatched) nexus.emeraldOnline else nexus.cyanGlow,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (pttState.isLatched) "Release Lock" else "Latch Mic")
                        }
                    }
                }
            }
        }

        // Radio Codec & Mesh Forwarding Settings
        item {
            GlassCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Adaptive Compression (Low-BW)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Halves bitrate for Bluetooth & multi-hop links",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = pttState.lowBandwidthCodec,
                            onCheckedChange = { onToggleLowBandwidth() }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mesh Audio Forwarding", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Relay PTT voice frames to multi-hop neighbors",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = pttState.meshRelayEnabled,
                            onCheckedChange = { onToggleMeshForwarding() }
                        )
                    }
                }
            }
        }

        // Participants on Channel
        item {
            Text(
                text = "Channel Participants (${allParticipants.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (allParticipants.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        text = "No peers linked yet. Connect nearby devices via Nearby or Network to broadcast Walkie-Talkie audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(allParticipants, key = { it.peerId }) { peer ->
                GlassCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NodeAvatar(
                            label = peer.displayName,
                            avatarIndex = peer.avatarIndex,
                            isOnline = true,
                            size = 42.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peer.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (peer.hopCount > 1) "${peer.hopCount}-hop Mesh Relay" else "Direct Link",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TransportBadge(transport = peer.transport, compact = true)
                    }
                }
            }
        }
    }

    if (showAddChannelDialog) {
        var channelName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddChannelDialog = false },
            title = { Text("Create Radio Channel") },
            text = {
                OutlinedTextField(
                    value = channelName,
                    onValueChange = { channelName = it },
                    label = { Text("Channel Tag (e.g. CH-05 ALPHA)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    showAddChannelDialog = false
                    onAddChannel(channelName)
                }) {
                    Text("Tune")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddChannelDialog = false }) { Text("Cancel") }
            }
        )
    }
}

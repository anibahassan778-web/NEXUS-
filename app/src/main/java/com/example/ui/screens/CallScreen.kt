package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.domain.model.ActiveCallState
import com.example.domain.model.CallStatus
import com.example.domain.model.CallType
import com.example.ui.components.GlassCard
import com.example.ui.components.NodeAvatar
import com.example.ui.components.SignalQualityBars
import com.example.ui.components.TransportBadge
import com.example.ui.theme.LocalNexusColors
import com.example.ui.util.FormatUtils

@Composable
fun CallOverlayScreen(
    callState: ActiveCallState,
    remoteVideoFrames: Map<String, Bitmap>,
    onAcceptCall: (asVideo: Boolean?) -> Unit,
    onRejectCall: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleLowBandwidth: () -> Unit,
    onBindCamera: (androidx.lifecycle.LifecycleOwner, PreviewView) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val nexus = LocalNexusColors.current

    BackHandler(enabled = callState.status != CallStatus.IDLE) {
        if (callState.status == CallStatus.RINGING_INCOMING) onRejectCall() else onEndCall()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(callState.status, callState.callType) {
        if (callState.status != CallStatus.IDLE) {
            val needed = buildList {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.RECORD_AUDIO)
                if (callState.callType == CallType.VIDEO &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.CAMERA)
            }
            if (needed.isNotEmpty()) {
                permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "call_ring_pulse")
    val pulseScale = pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse_scale"
    ).value

    val previewView = remember { PreviewView(context) }

    LaunchedEffect(
        callState.status,
        callState.callType,
        callState.isCameraEnabled,
        callState.useFrontCamera,
        callState.videoQuality
    ) {
        if (callState.status == CallStatus.ACTIVE &&
            callState.callType == CallType.VIDEO &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onBindCamera(lifecycleOwner, previewView)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF070B12), Color(0xFF0E1726), Color(0xFF070B12))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Video Layer when Active Video Call
        if (callState.status == CallStatus.ACTIVE && callState.callType == CallType.VIDEO) {
            if (callState.isGroupCall && callState.participants.size > 1) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 88.dp, bottom = 140.dp, start = 12.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(callState.participants, key = { it.peerId }) { participant ->
                        val frame = remoteVideoFrames[participant.peerId]
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            glowBorder = !participant.isMuted
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (frame != null) {
                                    Image(
                                        bitmap = frame.asImageBitmap(),
                                        contentDescription = participant.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier.align(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        NodeAvatar(
                                            label = participant.displayName,
                                            avatarIndex = participant.avatarIndex,
                                            isOnline = true,
                                            size = 64.dp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            participant.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Color.White
                                        )
                                    }
                                }
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Black.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        text = if (participant.isMuted) "${participant.displayName} (Muted)" else participant.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val primaryPeer = callState.participants.firstOrNull()
                val remoteBmp = primaryPeer?.let { remoteVideoFrames[it.peerId] }
                if (remoteBmp != null) {
                    Image(
                        bitmap = remoteBmp.asImageBitmap(),
                        contentDescription = callState.callTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NodeAvatar(
                            label = callState.callTitle,
                            avatarIndex = primaryPeer?.avatarIndex ?: 0,
                            isOnline = true,
                            size = 110.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Awaiting remote video stream…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Local Picture-in-Picture Camera Preview
            if (callState.isCameraEnabled) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 86.dp, end = 16.dp)
                        .size(width = 112.dp, height = 156.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, nexus.cyanGlow),
                    shadowElevation = 8.dp
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Top Call Status Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransportBadge(transport = callState.transport, isActive = true)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (callState.callType == CallType.VIDEO && callState.status == CallStatus.ACTIVE) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.Black.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, nexus.cyanGlow.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "${callState.videoQuality.label} • ${callState.currentFps}fps",
                                style = MaterialTheme.typography.labelSmall,
                                color = nexus.cyanGlow,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    SignalQualityBars(strengthPercent = if (callState.latencyMs > 200) 55 else 92)
                }
            }

            if (callState.meshNotice != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = nexus.amberWarning.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, nexus.amberWarning.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = callState.meshNotice,
                        style = MaterialTheme.typography.labelSmall,
                        color = nexus.amberWarning,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Center Voice Call / Ringing Content
        if (callState.callType == CallType.VOICE || callState.status != CallStatus.ACTIVE) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.scale(if (callState.status != CallStatus.ACTIVE) pulseScale else 1f)
                ) {
                    NodeAvatar(
                        label = callState.callTitle,
                        avatarIndex = callState.participants.firstOrNull()?.avatarIndex ?: 0,
                        isOnline = true,
                        isGroup = callState.isGroupCall,
                        size = 116.dp
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = callState.callTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (callState.status) {
                        CallStatus.RINGING_INCOMING ->
                            if (callState.callType == CallType.VIDEO) "Incoming Offline Video Call…"
                            else "Incoming Offline Voice Call…"
                        CallStatus.RINGING_OUTGOING -> "Ringing over ${callState.transport.displayName}…"
                        CallStatus.ACTIVE -> FormatUtils.formatDuration(callState.durationSeconds * 1000L)
                        CallStatus.IDLE -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = nexus.cyanGlow
                )

                if (callState.participants.isNotEmpty() && callState.status == CallStatus.ACTIVE) {
                    Spacer(modifier = Modifier.height(22.dp))
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Participants (${callState.participants.size + 1})",
                                style = MaterialTheme.typography.labelLarge,
                                color = nexus.cyanGlow
                            )
                            callState.participants.forEach { p ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(p.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                    Text(
                                        text = if (p.isMuted) "Muted" else "Connected • ${p.transport.shortLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (p.isMuted) nexus.amberWarning else nexus.emeraldOnline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Controls
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xCC111926),
            border = BorderStroke(1.dp, nexus.glassBorder)
        ) {
            if (callState.status == CallStatus.RINGING_INCOMING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRejectCall,
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("decline_call_button"),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Rounded.CallEnd, contentDescription = "Decline call", tint = Color.White)
                    }

                    Button(
                        onClick = { onAcceptCall(false) },
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("accept_voice_call_button"),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = nexus.emeraldOnline)
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = "Accept voice call", tint = Color.Black)
                    }

                    if (callState.callType == CallType.VIDEO) {
                        Button(
                            onClick = { onAcceptCall(true) },
                            modifier = Modifier
                                .size(64.dp)
                                .testTag("accept_video_call_button"),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = nexus.cyanGlow)
                        ) {
                            Icon(Icons.Rounded.Videocam, contentDescription = "Accept video call", tint = Color.Black)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (callState.status == CallStatus.ACTIVE && callState.callType == CallType.VIDEO) {
                        Text(
                            text = "${callState.callTitle} • ${FormatUtils.formatDuration(callState.durationSeconds * 1000L)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallActionIcon(
                            icon = if (callState.isMicMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            label = if (callState.isMicMuted) "Unmute" else "Mute",
                            active = callState.isMicMuted,
                            onClick = onToggleMute,
                            testTag = "call_mute_button"
                        )
                        CallActionIcon(
                            icon = if (callState.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                            label = "Speaker",
                            active = callState.isSpeakerOn,
                            onClick = onToggleSpeaker,
                            testTag = "call_speaker_button"
                        )
                        if (callState.callType == CallType.VIDEO) {
                            CallActionIcon(
                                icon = if (callState.isCameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                label = "Camera",
                                active = !callState.isCameraEnabled,
                                onClick = onToggleCamera,
                                testTag = "call_camera_toggle_button"
                            )
                            CallActionIcon(
                                icon = Icons.Rounded.Cameraswitch,
                                label = "Flip",
                                active = false,
                                onClick = onFlipCamera,
                                testTag = "call_flip_camera_button"
                            )
                            CallActionIcon(
                                icon = Icons.Rounded.DataSaverOn,
                                label = "Low-BW",
                                active = callState.lowBandwidthMode,
                                onClick = onToggleLowBandwidth,
                                testTag = "call_low_bw_button"
                            )
                        }
                        IconButton(
                            onClick = onEndCall,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                                .testTag("end_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = "End call",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallActionIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val nexus = LocalNexusColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (active) nexus.cyanGlow.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f))
                .border(
                    width = if (active) 1.dp else 0.dp,
                    color = if (active) nexus.cyanGlow else Color.Transparent,
                    shape = CircleShape
                )
                .testTag(testTag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) nexus.cyanGlow else Color.White
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

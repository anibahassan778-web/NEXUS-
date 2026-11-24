package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ReceivedFileEntity
import com.example.domain.model.MeshMode
import com.example.domain.model.NexusThemeMode
import com.example.domain.model.RadioHardwareState
import com.example.domain.model.TransportType
import com.example.domain.model.UserProfile
import com.example.ui.components.GlassCard
import com.example.ui.components.NodeAvatar
import com.example.ui.theme.AvatarColors
import com.example.ui.theme.LocalNexusColors
import com.example.ui.util.FormatUtils

@Composable
fun SettingsScreen(
    userProfile: UserProfile,
    radioState: RadioHardwareState,
    storageBytes: Long,
    receivedFiles: List<ReceivedFileEntity>,
    onSaveProfile: (nickname: String, status: String, avatarIndex: Int) -> Unit,
    onUpdateTransportPref: (TransportType, Boolean) -> Unit,
    onUpdateMeshMode: (MeshMode) -> Unit,
    onUpdateLowBandwidth: (Boolean) -> Unit,
    onUpdateTheme: (NexusThemeMode) -> Unit,
    onUpdateVoiceCodec: (String) -> Unit,
    onDeleteReceivedFile: (ReceivedFileEntity) -> Unit,
    onClearStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nexus = LocalNexusColors.current

    var nickname by remember(userProfile.nickname) { mutableStateOf(userProfile.nickname) }
    var statusText by remember(userProfile.statusMessage) { mutableStateOf(userProfile.statusMessage) }
    var avatarIndex by remember(userProfile.avatarIndex) { mutableIntStateOf(userProfile.avatarIndex) }
    var showHotspotGuide by remember { mutableStateOf(false) }
    var selectedFileCategory by remember { mutableStateOf("ALL") }

    val filteredFiles = remember(receivedFiles, selectedFileCategory) {
        if (selectedFileCategory == "ALL") receivedFiles
        else receivedFiles.filter { it.category == selectedFileCategory }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = "NEXUS Settings", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Identity, decentralized mesh modes, radios, storage, and theme",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Profile Customization
        item {
            GlassCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = nexus.cyanGlow)
                        Text(stringResource(R.string.settings_profile), style = MaterialTheme.typography.titleMedium)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NodeAvatar(label = nickname, avatarIndex = avatarIndex, isOnline = true, size = 54.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AvatarColors.forEachIndexed { idx, color ->
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(if (avatarIndex == idx) 2.5.dp else 0.dp, Color.White, CircleShape)
                                        .clickable { avatarIndex = idx }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = { Text("Node Nickname") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_nickname_input")
                    )

                    OutlinedTextField(
                        value = statusText,
                        onValueChange = { statusText = it },
                        label = { Text("Broadcast Status") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_status_input")
                    )

                    Button(
                        onClick = { onSaveProfile(nickname, statusText, avatarIndex) },
                        modifier = Modifier.align(Alignment.End).minimumInteractiveComponentSize().testTag("save_profile_button")
                    ) {
                        Text("Save Identity")
                    }
                }
            }
        }

        // Mesh Mode & Low Bandwidth Controls
        item {
            GlassCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Route, contentDescription = null, tint = nexus.cyanGlow)
                        Text("Mesh Networking Mode", style = MaterialTheme.typography.titleMedium)
                    }

                    MeshMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onUpdateMeshMode(mode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = userProfile.meshMode == mode,
                                onClick = { onUpdateMeshMode(mode) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(mode.label, style = MaterialTheme.typography.titleSmall)
                                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Low-Bandwidth Call Mode", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Adapts video resolution & audio bitrate for Bluetooth/Mesh hops",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = userProfile.lowBandwidthMode,
                            onCheckedChange = onUpdateLowBandwidth,
                            modifier = Modifier.testTag("low_bandwidth_switch")
                        )
                    }
                }
            }
        }

        // Connection Preferences
        item {
            GlassCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.SettingsEthernet, contentDescription = null, tint = nexus.cyanGlow)
                        Text(stringResource(R.string.settings_transport), style = MaterialTheme.typography.titleMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Select Optimal Transport", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Prioritizes Wi-Fi Direct > Hotspot > Bluetooth",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = userProfile.autoSelectTransport,
                            onCheckedChange = { checked -> onUpdateTransportPref(userProfile.preferredTransport, checked) },
                            modifier = Modifier.testTag("auto_transport_switch")
                        )
                    }

                    listOf(
                        TransportType.AUTO,
                        TransportType.WIFI_DIRECT,
                        TransportType.HOTSPOT,
                        TransportType.BLUETOOTH
                    ).forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onUpdateTransportPref(mode, mode == TransportType.AUTO) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = userProfile.preferredTransport == mode,
                                onClick = { onUpdateTransportPref(mode, mode == TransportType.AUTO) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(mode.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // Radio Settings & Hotspot Instructions
        item {
            GlassCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.settings_radios), style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("open_bt_settings_button")
                        ) {
                            Icon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (radioState.bluetoothEnabled) "BT: On" else "BT: Off")
                        }
                        OutlinedButton(
                            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("open_wifi_settings_button")
                        ) {
                            Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (radioState.wifiEnabled) "Wi-Fi: On" else "Wi-Fi: Off")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showHotspotGuide = !showHotspotGuide }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = nexus.blueAccent)
                            Text("Hotspot & Multi-Hop Guide", style = MaterialTheme.typography.titleSmall)
                        }
                        Icon(if (showHotspotGuide) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = "Toggle guide")
                    }

                    AnimatedVisibility(visible = showHotspotGuide) {
                        Text(
                            text = "1. Host phone starts a Local Hotspot or Wi-Fi Direct group from the Network tab.\n" +
                                "2. Nearby devices join the Hotspot or link via Bluetooth/P2P.\n" +
                                "3. In Mesh Mode, linked phones automatically advertise neighbors and relay packets across multi-hop paths (A → B → C → D).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Offline Voice Codec Preset
        item {
            GlassCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = nexus.cyanGlow)
                        Column {
                            Text("Offline Voice & PTT Audio Pipeline", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Real-time AudioRecord / AudioTrack codec profile",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    listOf(
                        "PCM 16kHz Adaptive (Offline)",
                        "8-bit Compressed 16kHz (Bluetooth/Mesh)",
                        "AAC-LD 32kbps Voice Notes"
                    ).forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onUpdateVoiceCodec(preset) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = userProfile.voiceCodecPreset == preset, onClick = { onUpdateVoiceCodec(preset) })
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(preset, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // Theme Selection
        item {
            GlassCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Palette, contentDescription = null, tint = nexus.cyanGlow)
                        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NexusThemeMode.entries.forEach { mode ->
                            val selected = userProfile.themeMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onUpdateTheme(mode) }
                                    .testTag("theme_${mode.name.lowercase()}"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) nexus.cyanGlow.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, if (selected) nexus.cyanGlow else nexus.subtleBorder)
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) nexus.cyanGlow else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Storage & Categorized Received Files
        item {
            GlassCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = nexus.cyanGlow)
                            Column {
                                Text(stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Offline storage: ${FormatUtils.formatBytes(storageBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (storageBytes > 0L || receivedFiles.isNotEmpty()) {
                            OutlinedButton(onClick = onClearStorage, modifier = Modifier.testTag("clear_storage_button")) {
                                Text("Clear")
                            }
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("ALL", "IMAGE", "VIDEO", "AUDIO", "DOC", "APK", "ARCHIVE")) { cat ->
                            FilterChip(
                                selected = selectedFileCategory == cat,
                                onClick = { selectedFileCategory = cat },
                                label = { Text(cat) }
                            )
                        }
                    }

                    if (filteredFiles.isEmpty()) {
                        Text(
                            text = "No received files in this category.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(filteredFiles, key = { it.id }) { file ->
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${file.category} • ${file.senderName} • ${FormatUtils.formatBytes(file.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onDeleteReceivedFile(file) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete received file", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

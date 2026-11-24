package com.example.ui.screens

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.domain.model.PeerNode
import com.example.ui.components.GlassCard
import com.example.ui.components.NodeAvatar
import com.example.ui.components.TransportBadge
import com.example.ui.theme.LocalNexusColors
import com.example.ui.util.FormatUtils
import java.io.File

@Composable
fun ImagePreSendDialog(
    previewModels: List<Any>,
    onDismiss: () -> Unit,
    onSend: (caption: String) -> Unit
) {
    var caption by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send ${previewModels.size} Image${if (previewModels.size > 1) "s" else ""} Offline") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = previewModels.firstOrNull(),
                    contentDescription = "Image preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                if (previewModels.size > 1) {
                    Text(
                        text = "+${previewModels.size - 1} more selected",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalNexusColors.current.cyanGlow
                    )
                }
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("Optional Caption") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSend(caption) }) { Text("Send Now") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MediaViewerDialog(
    message: MessageEntity,
    isVideo: Boolean,
    onDismiss: () -> Unit,
    onSaveCopy: (path: String, fileName: String) -> Unit
) {
    val path = message.mediaPath ?: return
    val file = remember(path) { File(path) }
    val fileExists = remember(path) { file.exists() }
    var activeVideoView by remember(path) { mutableStateOf<VideoView?>(null) }

    DisposableEffect(path) {
        onDispose {
            runCatching { activeVideoView?.stopPlayback() }
            activeVideoView = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.fileName ?: "Media",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    val meta = buildString {
                        append(FormatUtils.formatBytes(message.fileSizeBytes))
                        if (message.mediaWidth > 0 && message.mediaHeight > 0) {
                            append(" • ${message.mediaWidth}x${message.mediaHeight}")
                        }
                        append(" • ${message.senderName}")
                    }
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Row {
                    if (fileExists) {
                        IconButton(onClick = { onSaveCopy(path, message.fileName ?: "nexus_media") }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Save copy", tint = Color.White)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close viewer", tint = Color.White)
                    }
                }
            }

            if (!fileExists) {
                Text(
                    text = "Media file is no longer cached on disk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (isVideo) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.fromFile(file))
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setOnErrorListener { _, _, _ -> true }
                            start()
                            activeVideoView = this
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .align(Alignment.Center)
                )
            } else {
                AsyncImage(
                    model = file,
                    contentDescription = message.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 64.dp)
                        .align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
fun ForwardMessageDialog(
    conversations: List<ConversationEntity>,
    onDismiss: () -> Unit,
    onSelectTarget: (ConversationEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward Offline Message") },
        text = {
            if (conversations.isEmpty()) {
                Text("No other conversations available.")
            } else {
                LazyColumn(
                    modifier = Modifier.height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        GlassCard(onClick = { onSelectTarget(conv) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                NodeAvatar(
                                    label = conv.title,
                                    avatarIndex = conv.avatarIndex,
                                    isGroup = conv.isGroup,
                                    size = 38.dp
                                )
                                Text(conv.title, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun GroupMembersDialog(
    groupTitle: String,
    memberIdsCsv: String,
    allMeshPeers: List<PeerNode>,
    onDismiss: () -> Unit
) {
    val ids = memberIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$groupTitle Members") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ids.isEmpty()) {
                    Text(
                        "Broadcast channel: all connected local mesh peers receive messages.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    ids.forEach { pid ->
                        val peer = allMeshPeers.firstOrNull { it.peerId == pid }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, LocalNexusColors.current.subtleBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(peer?.displayName ?: pid, style = MaterialTheme.typography.bodyMedium)
                                if (peer != null) {
                                    TransportBadge(transport = peer.transport, isActive = peer.isConnected, compact = true)
                                } else {
                                    Text("Offline", style = MaterialTheme.typography.labelSmall)
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

package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.MessageType
import com.example.domain.model.PeerNode
import com.example.domain.model.TransferState
import com.example.domain.model.TransportType
import com.example.media.VoicePlaybackState
import com.example.media.VoiceRecordingState
import com.example.ui.components.GlassCard
import com.example.ui.components.NodeAvatar
import com.example.ui.components.TransportBadge
import com.example.ui.theme.LocalNexusColors
import com.example.ui.util.FormatUtils
import java.io.File

private val QuickReactionEmojis = listOf("👍", "❤️", "🔥", "⚡", "😂", "👀")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversation: ConversationEntity?,
    messages: List<MessageEntity>,
    allConversations: List<ConversationEntity>,
    allMeshPeers: List<PeerNode>,
    typingSenderName: String?,
    activeTransport: TransportType,
    voiceRecordingState: VoiceRecordingState,
    voicePlaybackState: VoicePlaybackState,
    onBack: () -> Unit,
    onSendText: (String, replyTo: MessageEntity?) -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onStartVoiceRecording: () -> Boolean,
    onStopAndSendVoice: (Boolean) -> Unit,
    onToggleVoicePlay: (messageId: String, path: String) -> Unit,
    onCycleVoiceSpeed: () -> Unit,
    onCreateCameraUri: () -> Pair<Uri, File>,
    onSendImages: (List<Uri>, caption: String) -> Unit,
    onSendCameraPhoto: (File, caption: String) -> Unit,
    onSendFiles: (List<Uri>) -> Unit,
    onRetryTransfer: (messageId: String) -> Unit,
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
    onForwardMessage: (MessageEntity, targetConvId: String) -> Unit,
    onEditMessage: (messageId: String, newText: String) -> Unit,
    onDeleteMessage: (messageId: String) -> Unit,
    onSwitchTransport: (TransportType) -> Unit,
    onStartVoiceCall: () -> Unit,
    onStartVideoCall: () -> Unit
) {
    val context = LocalContext.current
    val nexus = LocalNexusColors.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var draftText by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var showAttachmentBar by remember { mutableStateOf(false) }
    var showTransportMenu by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var actionTargetMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardSourceMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editTargetMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var viewerTargetMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var showGroupMembersDialog by remember { mutableStateOf(false) }

    var pendingGalleryUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var showCameraPreview by remember { mutableStateOf(false) }
    var pendingExportPath by remember { mutableStateOf<String?>(null) }

    val multiImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6)
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingGalleryUris = uris
            showAttachmentBar = false
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingCameraFile?.exists() == true) {
            showCameraPreview = true
            showAttachmentBar = false
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val (uri, file) = onCreateCameraUri()
            pendingCameraFile = file
            takePictureLauncher.launch(uri)
        }
    }

    val multiFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onSendFiles(uris)
            showAttachmentBar = false
        }
    }

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        val srcPath = pendingExportPath
        pendingExportPath = null
        if (destUri != null && srcPath != null) {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    File(srcPath).inputStream().use { input ->
                        context.contentResolver.openOutputStream(destUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartVoiceRecording()
    }

    val displayedMessages = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) messages
        else messages.filter {
            it.textContent.contains(searchQuery, ignoreCase = true) ||
                (it.fileName?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    LaunchedEffect(displayedMessages.size) {
        if (displayedMessages.isNotEmpty() && searchQuery.isBlank()) {
            listState.animateScrollToItem(displayedMessages.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.clickable {
                                if (conversation?.isGroup == true) showGroupMembersDialog = true
                            }
                        ) {
                            NodeAvatar(
                                label = conversation?.title ?: "Peer",
                                avatarIndex = conversation?.avatarIndex ?: 0,
                                isOnline = true,
                                isGroup = conversation?.isGroup == true,
                                size = 36.dp
                            )
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = conversation?.title ?: "Offline Chat",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when {
                                        typingSenderName != null -> "$typingSenderName is typing…"
                                        conversation?.isGroup == true -> "Group Mesh • ${activeTransport.shortLabel}"
                                        else -> "${activeTransport.displayName} • ${activeTransport.typicalSpeedMbps.toInt()} Mbps"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (typingSenderName != null) nexus.cyanGlow else nexus.emeraldOnline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onStartVoiceCall,
                            modifier = Modifier.testTag("chat_voice_call_button")
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = "Start voice call", tint = nexus.emeraldOnline)
                        }
                        IconButton(
                            onClick = onStartVideoCall,
                            modifier = Modifier.testTag("chat_video_call_button")
                        ) {
                            Icon(Icons.Rounded.Videocam, contentDescription = "Start video call", tint = nexus.cyanGlow)
                        }
                        IconButton(
                            onClick = { showSearchBar = !showSearchBar; if (!showSearchBar) searchQuery = "" },
                            modifier = Modifier.testTag("chat_search_toggle_button")
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search messages")
                        }
                        if (conversation?.isGroup == true) {
                            IconButton(onClick = { showGroupMembersDialog = true }) {
                                Icon(Icons.Rounded.Groups, contentDescription = "Group members")
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { showTransportMenu = true },
                                modifier = Modifier.testTag("switch_transport_button")
                            ) {
                                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Switch transport", tint = nexus.cyanGlow)
                            }
                            DropdownMenu(expanded = showTransportMenu, onDismissRequest = { showTransportMenu = false }) {
                                listOf(
                                    TransportType.AUTO,
                                    TransportType.WIFI_DIRECT,
                                    TransportType.HOTSPOT,
                                    TransportType.BLUETOOTH
                                ).forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.displayName) },
                                        onClick = {
                                            showTransportMenu = false
                                            onSwitchTransport(mode)
                                        }
                                    )
                                }
                            }
                        }
                    }
                )

                AnimatedVisibility(visible = showSearchBar) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search messages in thread…") },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Reply Banner
                AnimatedVisibility(visible = replyTarget != null) {
                    replyTarget?.let { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(nexus.cyanGlow.copy(alpha = 0.1f))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${target.senderName}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = nexus.cyanGlow
                                )
                                Text(
                                    text = target.textContent.ifBlank { target.fileName ?: "Media" },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { replyTarget = null }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cancel reply")
                            }
                        }
                    }
                }

                // Attachment Drawer (Gallery, Camera, Files/Video/Audio/APK)
                AnimatedVisibility(visible = showAttachmentBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AttachmentOptionButton(
                            icon = Icons.Rounded.Image,
                            label = stringResource(R.string.chat_attach_image),
                            onClick = {
                                multiImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.weight(1f).testTag("attach_image_button")
                        )
                        AttachmentOptionButton(
                            icon = Icons.Rounded.CameraAlt,
                            label = stringResource(R.string.chat_take_photo),
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    val (uri, file) = onCreateCameraUri()
                                    pendingCameraFile = file
                                    takePictureLauncher.launch(uri)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("attach_camera_button")
                        )
                        AttachmentOptionButton(
                            icon = Icons.Rounded.AttachFile,
                            label = stringResource(R.string.chat_attach_file),
                            onClick = { multiFilePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f).testTag("attach_file_button")
                        )
                    }
                }

                if (voiceRecordingState.isRecording) {
                    VoiceRecordingBar(
                        recordingState = voiceRecordingState,
                        onCancel = { onStopAndSendVoice(false) },
                        onSend = { onStopAndSendVoice(true) }
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = { showAttachmentBar = !showAttachmentBar },
                            modifier = Modifier.testTag("toggle_attachments_button")
                        ) {
                            Icon(
                                imageVector = if (showAttachmentBar) Icons.Rounded.Close else Icons.Rounded.Add,
                                contentDescription = "Attachments",
                                tint = nexus.cyanGlow
                            )
                        }

                        OutlinedTextField(
                            value = draftText,
                            onValueChange = { next ->
                                draftText = next
                                onTypingChanged(next.isNotBlank())
                            },
                            placeholder = {
                                Text(stringResource(R.string.chat_input_hint), style = MaterialTheme.typography.bodyMedium)
                            },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = nexus.cyanGlow.copy(alpha = 0.7f),
                                unfocusedBorderColor = nexus.subtleBorder,
                                focusedContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.weight(1f).testTag("chat_message_input")
                        )

                        if (draftText.isBlank()) {
                            IconButton(
                                onClick = {
                                    val hasAudio = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasAudio) onStartVoiceRecording()
                                    else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(nexus.blueAccent.copy(alpha = 0.18f))
                                    .testTag("record_voice_button")
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.chat_record_voice), tint = nexus.cyanGlow)
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val msg = draftText
                                    val reply = replyTarget
                                    draftText = ""
                                    replyTarget = null
                                    onTypingChanged(false)
                                    onSendText(msg, reply)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(nexus.blueAccent, nexus.cyanGlow)))
                                    .testTag("send_message_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.chat_send), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (displayedMessages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TransportBadge(transport = activeTransport, isActive = true)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Direct & Mesh P2P Channel", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Send text, voice notes, photos, videos, or files directly—or initiate an offline Voice/Video call above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayedMessages, key = { it.id }) { msg ->
                        MessageBubbleItem(
                            message = msg,
                            isGroup = conversation?.isGroup == true,
                            playbackState = voicePlaybackState,
                            showActionOverlay = actionTargetMessage?.id == msg.id,
                            onLongPress = {
                                actionTargetMessage = if (actionTargetMessage?.id == msg.id) null else msg
                            },
                            onSelectReaction = { emoji ->
                                actionTargetMessage = null
                                onToggleReaction(msg.id, emoji)
                            },
                            onReply = {
                                actionTargetMessage = null
                                replyTarget = msg
                            },
                            onForward = {
                                actionTargetMessage = null
                                forwardSourceMessage = msg
                            },
                            onEdit = {
                                actionTargetMessage = null
                                editTargetMessage = msg
                            },
                            onDelete = {
                                actionTargetMessage = null
                                onDeleteMessage(msg.id)
                            },
                            onToggleVoicePlay = {
                                msg.mediaPath?.let { path -> onToggleVoicePlay(msg.id, path) }
                            },
                            onCycleVoiceSpeed = onCycleVoiceSpeed,
                            onOpenViewer = { viewerTargetMessage = msg },
                            onRetryTransfer = { onRetryTransfer(msg.id) },
                            onExportFile = { path, name ->
                                pendingExportPath = path
                                exportFileLauncher.launch(name)
                            }
                        )
                    }
                }
            }
        }
    }

    if (pendingGalleryUris.isNotEmpty()) {
        ImagePreSendDialog(
            previewModels = pendingGalleryUris,
            onDismiss = { pendingGalleryUris = emptyList() },
            onSend = { caption ->
                val uris = pendingGalleryUris
                pendingGalleryUris = emptyList()
                onSendImages(uris, caption)
            }
        )
    }

    if (showCameraPreview && pendingCameraFile != null) {
        val file = pendingCameraFile!!
        ImagePreSendDialog(
            previewModels = listOf(file),
            onDismiss = {
                showCameraPreview = false
                runCatching { file.delete() }
                pendingCameraFile = null
            },
            onSend = { caption ->
                showCameraPreview = false
                pendingCameraFile = null
                onSendCameraPhoto(file, caption)
            }
        )
    }

    viewerTargetMessage?.let { msg ->
        MediaViewerDialog(
            message = msg,
            isVideo = msg.type == MessageType.VIDEO.name,
            onDismiss = { viewerTargetMessage = null },
            onSaveCopy = { path, name ->
                pendingExportPath = path
                exportFileLauncher.launch(name)
            }
        )
    }

    forwardSourceMessage?.let { src ->
        ForwardMessageDialog(
            conversations = allConversations.filter { it.id != conversation?.id },
            onDismiss = { forwardSourceMessage = null },
            onSelectTarget = { target ->
                forwardSourceMessage = null
                onForwardMessage(src, target.id)
            }
        )
    }

    editTargetMessage?.let { target ->
        var editedText by remember(target.id) { mutableStateOf(target.textContent) }
        AlertDialog(
            onDismissRequest = { editTargetMessage = null },
            title = { Text("Edit Message") },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val text = editedText
                    editTargetMessage = null
                    onEditMessage(target.id, text)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editTargetMessage = null }) { Text("Cancel") }
            }
        )
    }

    if (showGroupMembersDialog && conversation != null) {
        GroupMembersDialog(
            groupTitle = conversation.title,
            memberIdsCsv = conversation.peerIdsCsv,
            allMeshPeers = allMeshPeers,
            onDismiss = { showGroupMembersDialog = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubbleItem(
    message: MessageEntity,
    isGroup: Boolean,
    playbackState: VoicePlaybackState,
    showActionOverlay: Boolean,
    onLongPress: () -> Unit,
    onSelectReaction: (String) -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleVoicePlay: () -> Unit,
    onCycleVoiceSpeed: () -> Unit,
    onOpenViewer: () -> Unit,
    onRetryTransfer: () -> Unit,
    onExportFile: (path: String, fileName: String) -> Unit
) {
    val nexus = LocalNexusColors.current
    val msgType = runCatching { MessageType.valueOf(message.type) }.getOrDefault(MessageType.TEXT)

    if (msgType == MessageType.SYSTEM) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, nexus.subtleBorder)
            ) {
                Text(
                    text = message.textContent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        return
    }

    val bubbleAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (message.isOutgoing) 18.dp else 4.dp,
        bottomEnd = if (message.isOutgoing) 4.dp else 18.dp
    )

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = bubbleAlignment) {
        AnimatedVisibility(visible = showActionOverlay && !message.isDeleted) {
            Surface(
                modifier = Modifier.padding(bottom = 6.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, nexus.glassBorder)
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickReactionEmojis.forEach { emoji ->
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.clip(CircleShape).clickable { onSelectReaction(emoji) }.padding(5.dp)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onReply, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = "Reply", tint = nexus.cyanGlow, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onForward, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.Forward, contentDescription = "Forward", tint = nexus.cyanGlow, modifier = Modifier.size(18.dp))
                        }
                        if (message.isOutgoing && msgType == MessageType.TEXT) {
                            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = nexus.cyanGlow, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (message.isOutgoing) {
                            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .widthIn(max = 305.dp)
                .clip(bubbleShape)
                .then(
                    if (message.isOutgoing) {
                        Modifier.background(Brush.linearGradient(listOf(nexus.outgoingBubbleStart, nexus.outgoingBubbleEnd)))
                    } else {
                        Modifier.background(nexus.incomingBubble).border(1.dp, nexus.subtleBorder, bubbleShape)
                    }
                )
                .combinedClickable(
                    onClick = {
                        if (!message.isDeleted && (msgType == MessageType.IMAGE || msgType == MessageType.VIDEO)) {
                            onOpenViewer()
                        }
                    },
                    onLongClick = onLongPress
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isGroup && !message.isOutgoing) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = nexus.cyanGlow
                    )
                }

                if (message.isForwarded) {
                    Text(
                        text = "Forwarded",
                        style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                        color = if (message.isOutgoing) Color.White.copy(alpha = 0.75f) else nexus.cyanGlow
                    )
                }

                if (!message.replyToPreview.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.22f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            Text(
                                text = message.replyToSender ?: "Reply",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = nexus.cyanGlow
                            )
                            Text(
                                text = message.replyToPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (message.isDeleted) {
                    Text(
                        text = "Message deleted",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                } else {
                    when (msgType) {
                        MessageType.IMAGE -> {
                            val path = message.mediaPath
                            val mediaExists = remember(path) { path != null && File(path).exists() }
                            if (mediaExists && path != null) {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = message.fileName ?: "Shared photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(185.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                            if (message.textContent.isNotBlank() && message.textContent != message.fileName) {
                                Text(
                                    text = message.textContent,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (message.isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            val meta = buildString {
                                append(FormatUtils.formatBytes(message.fileSizeBytes))
                                if (message.mediaWidth > 0 && message.mediaHeight > 0) {
                                    append(" • ${message.mediaWidth}×${message.mediaHeight}")
                                }
                            }
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.isOutgoing) Color.White.copy(alpha = 0.78f)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MessageType.VOICE -> {
                            val isPlaying = playbackState.playingMessageId == message.id && playbackState.isPlaying
                            val waveform = remember(message.waveformCsv) {
                                message.waveformCsv.split(",").mapNotNull { it.toFloatOrNull() }
                                    .ifEmpty { listOf(0.3f, 0.6f, 0.4f, 0.8f, 0.5f, 0.7f, 0.35f, 0.65f, 0.45f, 0.55f) }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                IconButton(
                                    onClick = onToggleVoicePlay,
                                    modifier = Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(22.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        waveform.take(18).forEachIndexed { idx, amp ->
                                            val frac = idx.toFloat() / waveform.take(18).size.coerceAtLeast(1)
                                            val active = isPlaying && frac <= playbackState.progress
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height((5 + amp * 16).dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(
                                                        if (active) Color.White
                                                        else Color.White.copy(alpha = 0.38f)
                                                    )
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Voice • ${FormatUtils.formatDuration(message.voiceDurationMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (message.isOutgoing) Color.White.copy(alpha = 0.88f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    modifier = Modifier.clickable(onClick = onCycleVoiceSpeed),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = "${playbackState.playbackSpeed}x",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (msgType == MessageType.VIDEO) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                    contentDescription = msgType.name,
                                    tint = if (message.isOutgoing) Color.White else nexus.cyanGlow,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.fileName ?: "Media",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (message.isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${msgType.name} • ${FormatUtils.formatBytes(message.fileSizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (message.isOutgoing) Color.White.copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!message.mediaPath.isNullOrBlank()) {
                                    IconButton(onClick = { onExportFile(message.mediaPath, message.fileName ?: "nexus_file") }) {
                                        Icon(Icons.Rounded.Download, contentDescription = "Save copy", tint = if (message.isOutgoing) Color.White else nexus.cyanGlow)
                                    }
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = message.textContent,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (message.isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Transfer Progress / Retry Bar when active or failed
                    val txState = runCatching { TransferState.valueOf(message.transferState) }.getOrDefault(TransferState.COMPLETED)
                    if (txState == TransferState.IN_PROGRESS) {
                        LinearProgressIndicator(
                            progress = { message.transferProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f)
                        )
                        Text(
                            text = "Transferring ${message.transferProgress}% • ${FormatUtils.formatBytes(message.transferSpeedBps)}/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    } else if (txState == TransferState.FAILED) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable(onClick = onRetryTransfer)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Retry transfer", tint = nexus.amberWarning, modifier = Modifier.size(16.dp))
                            Text("Transfer paused • Tap to Retry", style = MaterialTheme.typography.labelSmall, color = nexus.amberWarning)
                        }
                    }
                }

                // Hop trace & Timestamp + Delivery Status
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (message.hopTrace.isNotBlank()) {
                        Text(
                            text = message.hopTrace,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    }
                    if (message.isEdited) {
                        Text(
                            text = "edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        text = FormatUtils.formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isOutgoing) Color.White.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (message.isOutgoing) {
                        val status = runCatching { DeliveryStatus.valueOf(message.deliveryStatus) }
                            .getOrDefault(DeliveryStatus.SENT)
                        Icon(
                            imageVector = when (status) {
                                DeliveryStatus.DELIVERED -> Icons.Rounded.DoneAll
                                DeliveryStatus.QUEUED -> Icons.Rounded.Schedule
                                else -> Icons.Rounded.Done
                            },
                            contentDescription = status.name,
                            tint = Color.White.copy(alpha = if (status == DeliveryStatus.DELIVERED) 1f else 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        val reactions = message.reactionsCsv.split(",").filter { it.isNotBlank() }
        if (reactions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                reactions.forEach { emoji ->
                    Surface(
                        modifier = Modifier.clickable { onSelectReaction(emoji) },
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, nexus.subtleBorder)
                    ) {
                        Text(text = emoji, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentOptionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexus = LocalNexusColors.current
    GlassCard(modifier = modifier.minimumInteractiveComponentSize(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = nexus.cyanGlow, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun VoiceRecordingBar(
    recordingState: VoiceRecordingState,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val nexus = LocalNexusColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp).testTag("cancel_voice_recording_button")) {
            Icon(Icons.Rounded.Close, contentDescription = "Discard voice recording", tint = MaterialTheme.colorScheme.error)
        }
        Row(
            modifier = Modifier.weight(1f).height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val bars = recordingState.amplitudes.ifEmpty { listOf(0.2f, 0.3f, 0.2f) }
            bars.forEach { amp ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((6 + amp * 26).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(nexus.cyanGlow)
                )
            }
        }
        Text(text = FormatUtils.formatDuration(recordingState.durationMs), style = MaterialTheme.typography.labelMedium, color = nexus.cyanGlow)
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(nexus.cyanGlow).testTag("send_voice_recording_button")
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send voice message", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

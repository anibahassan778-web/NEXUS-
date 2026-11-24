package com.example.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.R
import com.example.domain.model.CallStatus
import com.example.domain.model.CallType
import com.example.ui.screens.CallOverlayScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NearbyScreen
import com.example.ui.screens.NetworkScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WalkieTalkieScreen
import com.example.ui.theme.LocalNexusColors
import com.example.ui.theme.NexusTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object NexusRoutes {
    const val HOME = "home"
    const val NEARBY = "nearby"
    const val PTT = "ptt"
    const val NETWORK = "network"
    const val SETTINGS = "settings"
    const val CHAT_PATTERN = "chat/{conversationId}"

    fun chat(conversationId: String): String = "chat/${Uri.encode(conversationId)}"
}

private data class TopDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TopDestinations = listOf(
    TopDestination(
        route = NexusRoutes.HOME,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Rounded.CellTower,
        unselectedIcon = Icons.Rounded.CellTower,
        testTag = "nav_home"
    ),
    TopDestination(
        route = NexusRoutes.NEARBY,
        labelRes = R.string.nav_nearby,
        selectedIcon = Icons.Rounded.Radar,
        unselectedIcon = Icons.Outlined.Radar,
        testTag = "nav_nearby"
    ),
    TopDestination(
        route = NexusRoutes.PTT,
        labelRes = R.string.nav_ptt,
        selectedIcon = Icons.Rounded.Radio,
        unselectedIcon = Icons.Outlined.Radio,
        testTag = "nav_ptt"
    ),
    TopDestination(
        route = NexusRoutes.NETWORK,
        labelRes = R.string.nav_network,
        selectedIcon = Icons.Rounded.Hub,
        unselectedIcon = Icons.Outlined.Hub,
        testTag = "nav_network"
    ),
    TopDestination(
        route = NexusRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings"
    )
)

@Composable
fun NexusApp(viewModel: NexusViewModel) {
    val themeModeFlow = remember(viewModel) {
        viewModel.userProfile.map { it.themeMode }.distinctUntilChanged()
    }
    val isCallActiveFlow = remember(viewModel) {
        viewModel.callState.map { it.status != CallStatus.IDLE }.distinctUntilChanged()
    }

    val themeMode by themeModeFlow.collectAsStateWithLifecycle(
        initialValue = viewModel.userProfile.value.themeMode
    )
    val isCallActive by isCallActiveFlow.collectAsStateWithLifecycle(
        initialValue = viewModel.callState.value.status != CallStatus.IDLE
    )

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    NexusTheme(themeMode = themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val isTopLevelRoute = TopDestinations.any { it.route == currentRoute }
        val nexus = LocalNexusColors.current

        Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavigationRail = maxWidth >= 600.dp

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        if (isTopLevelRoute && !useNavigationRail) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                windowInsets = WindowInsets.navigationBars
                            ) {
                                TopDestinations.forEach { dest ->
                                    val selected = currentRoute == dest.route
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(dest.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = stringResource(dest.labelRes)
                                            )
                                        },
                                        label = { Text(stringResource(dest.labelRes)) },
                                        modifier = Modifier.testTag(dest.testTag),
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = nexus.cyanGlow,
                                            selectedTextColor = nexus.cyanGlow,
                                            indicatorColor = nexus.cyanGlow.copy(alpha = 0.16f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (isTopLevelRoute && useNavigationRail) {
                            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                                TopDestinations.forEach { dest ->
                                    val selected = currentRoute == dest.route
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(dest.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = stringResource(dest.labelRes)
                                            )
                                        },
                                        label = { Text(stringResource(dest.labelRes)) },
                                        modifier = Modifier.testTag(dest.testTag),
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = nexus.cyanGlow,
                                            selectedTextColor = nexus.cyanGlow,
                                            indicatorColor = nexus.cyanGlow.copy(alpha = 0.16f)
                                        )
                                    )
                                }
                            }
                        }

                        NavHost(
                            navController = navController,
                            startDestination = NexusRoutes.HOME,
                            modifier = Modifier.weight(1f)
                        ) {
                            composable(NexusRoutes.HOME) {
                                HomeRoute(viewModel = viewModel, navController = navController)
                            }
                            composable(NexusRoutes.NEARBY) {
                                NearbyRoute(viewModel = viewModel, navController = navController)
                            }
                            composable(NexusRoutes.PTT) {
                                PttRoute(viewModel = viewModel)
                            }
                            composable(NexusRoutes.NETWORK) {
                                NetworkRoute(viewModel = viewModel, navController = navController)
                            }
                            composable(NexusRoutes.SETTINGS) {
                                SettingsRoute(viewModel = viewModel)
                            }
                            composable(
                                route = NexusRoutes.CHAT_PATTERN,
                                arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                            ) { entry ->
                                val conversationId = entry.arguments?.getString("conversationId") ?: return@composable
                                ChatRoute(
                                    conversationId = conversationId,
                                    viewModel = viewModel,
                                    navController = navController
                                )
                            }
                        }
                    }
                }
            }

            if (isCallActive) {
                CallOverlayHost(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun HomeRoute(viewModel: NexusViewModel, navController: NavHostController) {
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val networkStats by viewModel.networkStats.collectAsStateWithLifecycle()
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val multiHopPeers by viewModel.multiHopPeers.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val allConversations by viewModel.allConversations.collectAsStateWithLifecycle()
    val callHistory by viewModel.callHistory.collectAsStateWithLifecycle()
    val receivedFiles by viewModel.receivedFiles.collectAsStateWithLifecycle()

    HomeScreen(
        userProfile = userProfile,
        networkStats = networkStats,
        radioState = radioState,
        connectedPeers = connectedPeers,
        multiHopPeers = multiHopPeers,
        discoveredPeers = discoveredPeers,
        conversations = conversations,
        allConversations = allConversations,
        callHistory = callHistory,
        receivedFiles = receivedFiles,
        onConnectNearbyClick = {
            navController.navigate(NexusRoutes.NEARBY) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
        onSelectTransport = { viewModel.selectActiveTransport(it) },
        onPeerClick = { peer ->
            if (peer.isConnected) {
                viewModel.openPeerChat(peer) { convId ->
                    navController.navigate(NexusRoutes.chat(convId))
                }
            } else {
                viewModel.connectToPeer(peer)
            }
        },
        onConversationClick = { convId ->
            navController.navigate(NexusRoutes.chat(convId))
        },
        onCreateGroup = { title, peerIds ->
            viewModel.createGroupChannel(title, peerIds) { convId ->
                navController.navigate(NexusRoutes.chat(convId))
            }
        },
        onArchiveConversation = { id, archived -> viewModel.archiveConversation(id, archived) },
        onMuteConversation = { id, muted -> viewModel.toggleMuteConversation(id, muted) },
        onDeleteConversation = { id -> viewModel.deleteConversation(id) },
        onStartCallFromHistory = { rec ->
            val conv = allConversations.firstOrNull { it.id == rec.conversationId }
            val type = runCatching { CallType.valueOf(rec.callType) }.getOrDefault(CallType.VOICE)
            viewModel.startCall(
                conversationId = rec.conversationId,
                title = rec.title,
                callType = type,
                isGroup = rec.isGroup,
                peerIdsCsv = conv?.peerIdsCsv ?: ""
            )
        },
        onClearCallHistory = { viewModel.clearCallHistory() }
    )
}

@Composable
private fun NearbyRoute(viewModel: NexusViewModel, navController: NavHostController) {
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val selectedNearbyFilter by viewModel.selectedNearbyFilter.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val multiHopPeers by viewModel.multiHopPeers.collectAsStateWithLifecycle()
    val combinedPeers = remember(discoveredPeers, multiHopPeers) {
        (discoveredPeers + multiHopPeers).distinctBy { it.peerId }
    }

    NearbyScreen(
        radioState = radioState,
        selectedFilter = selectedNearbyFilter,
        discoveredPeers = combinedPeers,
        onFilterSelected = { viewModel.setNearbyFilter(it) },
        onStartScan = { viewModel.startDiscoveryScan() },
        onStopScan = { viewModel.stopDiscoveryScan() },
        onConnectPeer = { viewModel.connectToPeer(it) },
        onOpenChat = { peer ->
            viewModel.openPeerChat(peer) { convId ->
                navController.navigate(NexusRoutes.chat(convId))
            }
        },
        onConnectManualIp = { ip, port -> viewModel.connectByManualIp(ip, port) },
        onPermissionsUpdated = { viewModel.refreshRadios() }
    )
}

@Composable
private fun PttRoute(viewModel: NexusViewModel) {
    val pttState by viewModel.pttState.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val multiHopPeers by viewModel.multiHopPeers.collectAsStateWithLifecycle()

    WalkieTalkieScreen(
        pttState = pttState,
        connectedPeers = connectedPeers,
        multiHopPeers = multiHopPeers,
        onSelectChannel = { viewModel.selectPttChannel(it) },
        onAddChannel = { viewModel.addCustomPttChannel(it) },
        onEnableListening = { viewModel.enablePttListening(it) },
        onSetTransmitting = { viewModel.setPttTransmitting(it) },
        onToggleLatch = { viewModel.togglePttLatch() },
        onToggleLowBandwidth = { viewModel.togglePttLowBandwidth() },
        onToggleMeshForwarding = { viewModel.togglePttMeshForwarding() }
    )
}

@Composable
private fun NetworkRoute(viewModel: NexusViewModel, navController: NavHostController) {
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val networkStats by viewModel.networkStats.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val multiHopPeers by viewModel.multiHopPeers.collectAsStateWithLifecycle()
    val meshRoutes by viewModel.meshRoutes.collectAsStateWithLifecycle()
    val topologyEdges by viewModel.topologyEdges.collectAsStateWithLifecycle()
    val activePaths by viewModel.activePaths.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()

    NetworkScreen(
        userProfile = userProfile,
        networkStats = networkStats,
        connectedPeers = connectedPeers,
        multiHopPeers = multiHopPeers,
        meshRoutes = meshRoutes,
        topologyEdges = topologyEdges,
        activePaths = activePaths,
        transfers = transfers,
        onSelectMeshMode = { viewModel.updateMeshMode(it) },
        onToggleHotspot = { viewModel.toggleLocalHotspot() },
        onToggleWifiDirectGroup = { viewModel.toggleWifiDirectGroup() },
        onConnectByIp = { ip, port -> viewModel.connectByManualIp(ip, port) },
        onDisconnectAll = { viewModel.disconnectAllPeers() },
        onCancelTransfer = { viewModel.cancelTransfer(it) },
        onPeerClick = { peer ->
            viewModel.openPeerChat(peer) { convId ->
                navController.navigate(NexusRoutes.chat(convId))
            }
        }
    )
}

@Composable
private fun SettingsRoute(viewModel: NexusViewModel) {
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val receivedFiles by viewModel.receivedFiles.collectAsStateWithLifecycle()

    SettingsScreen(
        userProfile = userProfile,
        radioState = radioState,
        storageBytes = storageBytes,
        receivedFiles = receivedFiles,
        onSaveProfile = { nick, status, idx -> viewModel.updateProfile(nick, status, idx) },
        onUpdateTransportPref = { pref, auto -> viewModel.updateTransportPreference(pref, auto) },
        onUpdateMeshMode = { viewModel.updateMeshMode(it) },
        onUpdateLowBandwidth = { viewModel.updateLowBandwidthMode(it) },
        onUpdateTheme = { viewModel.updateThemeMode(it) },
        onUpdateVoiceCodec = { viewModel.updateVoiceCodecPreset(it) },
        onDeleteReceivedFile = { viewModel.deleteReceivedFile(it) },
        onClearStorage = { viewModel.clearAllReceivedStorage() }
    )
}

@Composable
private fun ChatRoute(
    conversationId: String,
    viewModel: NexusViewModel,
    navController: NavHostController
) {
    val conversationFlow = remember(viewModel, conversationId) {
        viewModel.observeConversation(conversationId)
    }
    val messagesFlow = remember(viewModel, conversationId) {
        viewModel.observeMessages(conversationId)
    }

    val conversation by conversationFlow.collectAsStateWithLifecycle(initialValue = null)
    val messages by messagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allConversations by viewModel.allConversations.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val multiHopPeers by viewModel.multiHopPeers.collectAsStateWithLifecycle()
    val typingPeers by viewModel.typingPeers.collectAsStateWithLifecycle()
    val networkStats by viewModel.networkStats.collectAsStateWithLifecycle()
    val voiceRecordingState by viewModel.voiceRecordingState.collectAsStateWithLifecycle()
    val voicePlaybackState by viewModel.voicePlaybackState.collectAsStateWithLifecycle()

    LaunchedEffect(conversationId) {
        viewModel.markConversationRead(conversationId)
    }

    val allMeshPeers = remember(connectedPeers, multiHopPeers) {
        (connectedPeers + multiHopPeers).distinctBy { it.peerId }
    }

    ChatScreen(
        conversation = conversation,
        messages = messages,
        allConversations = allConversations,
        allMeshPeers = allMeshPeers,
        typingSenderName = typingPeers[conversationId],
        activeTransport = networkStats.activeTransport,
        voiceRecordingState = voiceRecordingState,
        voicePlaybackState = voicePlaybackState,
        onBack = { navController.popBackStack() },
        onSendText = { text, reply -> viewModel.sendTextMessage(conversationId, text, reply) },
        onTypingChanged = { viewModel.sendTypingIndicator(conversationId, it) },
        onStartVoiceRecording = { viewModel.startVoiceRecording() },
        onStopAndSendVoice = { save -> viewModel.stopAndSendVoiceRecording(conversationId, save) },
        onToggleVoicePlay = { msgId, path -> viewModel.toggleVoicePlayback(msgId, path) },
        onCycleVoiceSpeed = { viewModel.cycleVoicePlaybackSpeed() },
        onCreateCameraUri = { viewModel.createCameraCaptureUri() },
        onSendImages = { uris, caption -> viewModel.sendImages(conversationId, uris, caption) },
        onSendCameraPhoto = { file, caption -> viewModel.sendCameraPhoto(conversationId, file, caption) },
        onSendFiles = { uris -> viewModel.sendFiles(conversationId, uris) },
        onRetryTransfer = { msgId -> viewModel.retryTransfer(msgId) },
        onToggleReaction = { msgId, emoji -> viewModel.toggleReaction(conversationId, msgId, emoji) },
        onForwardMessage = { src, targetConvId -> viewModel.forwardMessage(src, targetConvId) },
        onEditMessage = { msgId, newText -> viewModel.editMessage(conversationId, msgId, newText) },
        onDeleteMessage = { msgId -> viewModel.deleteMessage(conversationId, msgId) },
        onSwitchTransport = { mode -> viewModel.switchConversationTransport(conversationId, mode) },
        onStartVoiceCall = {
            val conv = conversation ?: return@ChatScreen
            val peersCsv = conv.peerIdsCsv.ifBlank {
                allMeshPeers.joinToString(",") { it.peerId }
            }
            viewModel.startCall(conv.id, conv.title, CallType.VOICE, conv.isGroup, peersCsv)
        },
        onStartVideoCall = {
            val conv = conversation ?: return@ChatScreen
            val peersCsv = conv.peerIdsCsv.ifBlank {
                allMeshPeers.joinToString(",") { it.peerId }
            }
            viewModel.startCall(conv.id, conv.title, CallType.VIDEO, conv.isGroup, peersCsv)
        }
    )
}

@Composable
private fun CallOverlayHost(viewModel: NexusViewModel) {
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val remoteVideoFrames by viewModel.remoteVideoFrames.collectAsStateWithLifecycle()

    if (callState.status != CallStatus.IDLE) {
        CallOverlayScreen(
            callState = callState,
            remoteVideoFrames = remoteVideoFrames,
            onAcceptCall = { asVideo -> viewModel.acceptIncomingCall(asVideo) },
            onRejectCall = { viewModel.rejectIncomingCall() },
            onEndCall = { viewModel.endCall() },
            onToggleMute = { viewModel.toggleCallMute() },
            onToggleSpeaker = { viewModel.toggleCallSpeaker() },
            onToggleCamera = { viewModel.toggleCallCamera() },
            onFlipCamera = { viewModel.flipCallCamera() },
            onToggleLowBandwidth = { viewModel.toggleCallLowBandwidth() },
            onBindCamera = { owner, previewView -> viewModel.bindCallCamera(owner, previewView) }
        )
    }
}


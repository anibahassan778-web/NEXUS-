package com.example.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CallRecordEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.local.PeerContactEntity
import com.example.data.local.PendingRelayEntity
import com.example.data.local.ReceivedFileEntity
import com.example.data.repository.NexusRepository
import com.example.domain.model.ActiveCallState
import com.example.domain.model.ActiveMeshPath
import com.example.domain.model.CallType
import com.example.domain.model.MeshMode
import com.example.domain.model.MeshRoute
import com.example.domain.model.NetworkStats
import com.example.domain.model.NexusThemeMode
import com.example.domain.model.PeerNode
import com.example.domain.model.RadioHardwareState
import com.example.domain.model.TransferItemState
import com.example.domain.model.TransportType
import com.example.domain.model.UserProfile
import com.example.domain.model.WalkieTalkieState
import com.example.media.CallManager
import com.example.media.RealtimeAudioVideoEngine
import com.example.media.VoicePlaybackState
import com.example.media.VoiceRecorderPlayer
import com.example.media.VoiceRecordingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class NexusViewModel(
    private val repository: NexusRepository,
    private val voiceEngine: VoiceRecorderPlayer,
    private val avEngine: RealtimeAudioVideoEngine,
    private val callManager: CallManager
) : ViewModel() {

    val userProfile: StateFlow<UserProfile> = repository.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserProfile())

    val conversations: StateFlow<List<ConversationEntity>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allConversations: StateFlow<List<ConversationEntity>> = repository.allConversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val peerContacts: StateFlow<List<PeerContactEntity>> = repository.peerContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val receivedFiles: StateFlow<List<ReceivedFileEntity>> = repository.receivedFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val callHistory: StateFlow<List<CallRecordEntity>> = repository.callHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingRelays: StateFlow<List<PendingRelayEntity>> = repository.pendingRelays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transfers: StateFlow<List<TransferItemState>> = repository.transfers

    val discoveredPeers: StateFlow<List<PeerNode>> = repository.meshEngine.discoveredPeers
    val connectedPeers: StateFlow<List<PeerNode>> = repository.meshEngine.connectedPeers
    val multiHopPeers: StateFlow<List<PeerNode>> = repository.meshEngine.multiHopPeers
    val meshRoutes: StateFlow<Map<String, MeshRoute>> = repository.meshEngine.routes
    val topologyEdges: StateFlow<List<Pair<String, String>>> = repository.meshEngine.topologyEdges
    val activePaths: StateFlow<List<ActiveMeshPath>> = repository.meshEngine.activePaths

    val radioState: StateFlow<RadioHardwareState> = repository.meshEngine.radioState
    val networkStats: StateFlow<NetworkStats> = repository.meshEngine.networkStats
    val typingPeers: StateFlow<Map<String, String>> = repository.meshEngine.typingPeers

    val voiceRecordingState: StateFlow<VoiceRecordingState> = voiceEngine.recordingState
    val voicePlaybackState: StateFlow<VoicePlaybackState> = voiceEngine.playbackState

    val callState: StateFlow<ActiveCallState> = callManager.callState
    val remoteVideoFrames: StateFlow<Map<String, Bitmap>> = avEngine.remoteVideoFrames

    private val _pttState = MutableStateFlow(WalkieTalkieState())
    val pttState: StateFlow<WalkieTalkieState> = _pttState.asStateFlow()

    private val _selectedNearbyFilter = MutableStateFlow(TransportType.AUTO)
    val selectedNearbyFilter: StateFlow<TransportType> = _selectedNearbyFilter.asStateFlow()

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    init {
        refreshStorageMetrics()
        viewModelScope.launch {
            repository.userProfile.collect { profile ->
                callManager.updateProfile(profile)
                _pttState.update {
                    it.copy(
                        lowBandwidthCodec = profile.lowBandwidthMode,
                        meshRelayEnabled = profile.pttMeshForwarding
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.meshEngine.statusMessages.collect { msg ->
                _uiEvents.emit(msg)
            }
        }
        viewModelScope.launch {
            combine(avEngine.micLevel, avEngine.rxLevel, avEngine.activePttSpeaker) { mic, rx, speaker ->
                Triple(mic, rx, speaker)
            }.collect { (mic, rx, speaker) ->
                _pttState.update {
                    it.copy(
                        txLevel = if (it.isTransmitting) mic else 0f,
                        rxLevel = rx,
                        activeSpeakerName = speaker
                    )
                }
            }
        }
    }

    fun refreshRadios() = repository.meshEngine.refreshHardwareStatus()

    fun setNearbyFilter(transportType: TransportType) {
        _selectedNearbyFilter.value = transportType
    }

    fun startDiscoveryScan() = repository.meshEngine.startUnifiedScan(_selectedNearbyFilter.value)

    fun stopDiscoveryScan() = repository.meshEngine.stopAllScans()

    fun selectActiveTransport(mode: TransportType) = repository.meshEngine.selectTransportMode(mode)

    fun connectToPeer(peer: PeerNode) = repository.meshEngine.connectToPeer(peer)

    fun connectByManualIp(ip: String, port: Int = 8989) {
        if (ip.isBlank()) return
        repository.meshEngine.connectByManualIp(ip, port)
    }

    fun toggleLocalHotspot() = repository.meshEngine.toggleLocalHotspot()

    fun toggleWifiDirectGroup() = repository.meshEngine.toggleWifiDirectGroup()

    fun disconnectAllPeers() = repository.meshEngine.disconnectAllPeers()

    fun openPeerChat(peer: PeerNode, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val convId = repository.openOrCreatePeerChat(peer)
            onReady(convId)
        }
    }

    fun createGroupChannel(title: String, peerIds: List<String>, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = repository.createGroupChannel(title, peerIds)
            onCreated(id)
        }
    }

    fun observeConversation(conversationId: String): Flow<ConversationEntity?> =
        repository.observeConversation(conversationId)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        repository.observeMessages(conversationId)

    fun markConversationRead(conversationId: String) {
        viewModelScope.launch { repository.clearUnread(conversationId) }
    }

    fun archiveConversation(conversationId: String, archived: Boolean) {
        viewModelScope.launch { repository.setConversationArchived(conversationId, archived) }
    }

    fun toggleMuteConversation(conversationId: String, muted: Boolean) {
        viewModelScope.launch { repository.setConversationMuted(conversationId, muted) }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch { repository.deleteConversation(conversationId) }
    }

    fun switchConversationTransport(conversationId: String, transport: TransportType) {
        viewModelScope.launch { repository.switchConversationTransport(conversationId, transport) }
    }

    fun sendTypingIndicator(conversationId: String, isTyping: Boolean) {
        viewModelScope.launch { repository.sendTypingIndicator(conversationId, isTyping) }
    }

    fun sendTextMessage(conversationId: String, text: String, replyTo: MessageEntity? = null) {
        viewModelScope.launch { repository.sendTextMessage(conversationId, text, replyTo) }
    }

    fun forwardMessage(source: MessageEntity, targetConversationId: String) {
        viewModelScope.launch {
            repository.forwardMessage(source, targetConversationId)
            _uiEvents.emit("Message forwarded")
        }
    }

    fun editMessage(conversationId: String, messageId: String, newText: String) {
        viewModelScope.launch { repository.editMessage(conversationId, messageId, newText) }
    }

    fun deleteMessage(conversationId: String, messageId: String) {
        viewModelScope.launch { repository.deleteMessage(conversationId, messageId) }
    }

    fun startVoiceRecording(): Boolean = voiceEngine.startRecording()

    fun stopAndSendVoiceRecording(conversationId: String, save: Boolean) {
        val result = voiceEngine.stopRecording(save) ?: return
        viewModelScope.launch {
            repository.sendVoiceMessage(conversationId, result)
            refreshStorageMetrics()
        }
    }

    fun toggleVoicePlayback(messageId: String, path: String) = voiceEngine.togglePlayback(messageId, path)

    fun cycleVoicePlaybackSpeed() = voiceEngine.cyclePlaybackSpeed()

    fun createCameraCaptureUri(): Pair<Uri, File> = repository.createCameraCaptureUri()

    fun sendImages(conversationId: String, uris: List<Uri>, caption: String = "") {
        viewModelScope.launch {
            uris.forEach { uri -> repository.sendImageFromUri(conversationId, uri, caption) }
            refreshStorageMetrics()
        }
    }

    fun sendCameraPhoto(conversationId: String, file: File, caption: String = "") {
        viewModelScope.launch {
            repository.sendCameraFile(conversationId, file, caption)
            refreshStorageMetrics()
        }
    }

    fun sendFiles(conversationId: String, uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri -> repository.sendFileFromUri(conversationId, uri) }
            refreshStorageMetrics()
        }
    }

    fun retryTransfer(messageId: String) {
        viewModelScope.launch { repository.retryTransfer(messageId) }
    }

    fun cancelTransfer(transferId: String) = repository.cancelTransfer(transferId)

    fun toggleReaction(conversationId: String, messageId: String, emoji: String) {
        viewModelScope.launch { repository.toggleReaction(conversationId, messageId, emoji) }
    }

    // Calls (Voice & Video)
    fun startCall(
        conversationId: String,
        title: String,
        callType: CallType,
        isGroup: Boolean,
        peerIdsCsv: String
    ) {
        val targets = peerIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (targets.isEmpty()) {
            viewModelScope.launch { _uiEvents.emit("Link at least one peer before starting a call") }
            return
        }
        callManager.startOutgoingCall(conversationId, title, callType, isGroup, targets)
    }

    fun acceptIncomingCall(asVideo: Boolean? = null) = callManager.acceptIncomingCall(asVideo)
    fun rejectIncomingCall() = callManager.rejectIncomingCall()
    fun endCall() = callManager.endCall()
    fun toggleCallMute() = callManager.toggleMute()
    fun toggleCallSpeaker() = callManager.toggleSpeaker()
    fun toggleCallCamera() = callManager.toggleCameraEnabled()
    fun flipCallCamera() = callManager.flipCamera()
    fun toggleCallLowBandwidth() = callManager.toggleLowBandwidthMode()

    fun bindCallCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val state = callState.value
        avEngine.bindCameraPreviewAndStream(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            useFrontCamera = state.useFrontCamera,
            cameraEnabled = state.isCameraEnabled,
            callId = state.callId,
            targetPeerIds = callManager.getTargetPeerIds()
        )
    }

    // Walkie-Talkie (PTT)
    fun selectPttChannel(channel: String) {
        _pttState.update { it.copy(activeChannel = channel, isTransmitting = false, isLatched = false) }
        avEngine.stopAudioTransmit()
        avEngine.setPttChannelListener(channel, _pttState.value.lowBandwidthCodec)
    }

    fun addCustomPttChannel(channelName: String) {
        val clean = channelName.trim().uppercase()
        if (clean.isBlank()) return
        _pttState.update {
            val updated = (it.availableChannels + clean).distinct()
            it.copy(availableChannels = updated, activeChannel = clean)
        }
        avEngine.setPttChannelListener(clean, _pttState.value.lowBandwidthCodec)
    }

    fun enablePttListening(enabled: Boolean) {
        val state = _pttState.value
        avEngine.setPttChannelListener(if (enabled) state.activeChannel else null, state.lowBandwidthCodec)
    }

    fun setPttTransmitting(transmitting: Boolean) {
        val state = _pttState.value
        if (transmitting == state.isTransmitting) return
        _pttState.update { it.copy(isTransmitting = transmitting) }
        if (transmitting) {
            avEngine.setSpeakerphone(true)
            avEngine.startAudioTransmit(isPtt = true, pttChannel = state.activeChannel, targetPeers = emptyList())
        } else {
            avEngine.stopAudioTransmit()
        }
    }

    fun togglePttLatch() {
        val nextLatch = !_pttState.value.isLatched
        _pttState.update { it.copy(isLatched = nextLatch) }
        setPttTransmitting(nextLatch)
    }

    fun togglePttLowBandwidth() {
        val next = !_pttState.value.lowBandwidthCodec
        _pttState.update { it.copy(lowBandwidthCodec = next) }
        avEngine.setPttChannelListener(_pttState.value.activeChannel, next)
    }

    fun togglePttMeshForwarding() {
        val next = !_pttState.value.meshRelayEnabled
        _pttState.update { it.copy(meshRelayEnabled = next) }
        viewModelScope.launch { repository.updatePttMeshForwarding(next) }
    }

    // Settings
    fun updateProfile(nickname: String, status: String, avatarIndex: Int) {
        viewModelScope.launch {
            repository.updateProfile(nickname, status, avatarIndex)
            _uiEvents.emit("Node identity updated")
        }
    }

    fun updateTransportPreference(preferred: TransportType, autoSelect: Boolean) {
        viewModelScope.launch { repository.updateTransportPreference(preferred, autoSelect) }
    }

    fun updateMeshMode(meshMode: MeshMode) {
        viewModelScope.launch {
            repository.updateMeshMode(meshMode)
            _uiEvents.emit("Mesh mode: ${meshMode.label}")
        }
    }

    fun updateLowBandwidthMode(enabled: Boolean) {
        viewModelScope.launch { repository.updateLowBandwidthMode(enabled) }
    }

    fun updateThemeMode(themeMode: NexusThemeMode) {
        viewModelScope.launch { repository.updateThemeMode(themeMode) }
    }

    fun updateVoiceCodecPreset(preset: String) {
        viewModelScope.launch { repository.updateVoiceCodecPreset(preset) }
    }

    fun clearCallHistory() {
        viewModelScope.launch { repository.clearCallHistory() }
    }

    fun deleteReceivedFile(file: ReceivedFileEntity) {
        viewModelScope.launch {
            repository.deleteReceivedFile(file)
            refreshStorageMetrics()
        }
    }

    fun clearAllReceivedStorage() {
        viewModelScope.launch {
            repository.clearAllReceivedStorage()
            refreshStorageMetrics()
            _uiEvents.emit("Offline media cache cleared")
        }
    }

    fun refreshStorageMetrics() {
        viewModelScope.launch {
            _storageBytes.value = repository.calculateLocalStorageBytes()
        }
    }

    override fun onCleared() {
        voiceEngine.stopPlayback()
        if (voiceEngine.recordingState.value.isRecording) {
            voiceEngine.stopRecording(save = false)
        }
        if (_pttState.value.isTransmitting) {
            avEngine.stopAudioTransmit()
        }
        avEngine.setPttChannelListener(null, _pttState.value.lowBandwidthCodec)
        super.onCleared()
    }

    class Factory(
        private val repository: NexusRepository,
        private val voiceEngine: VoiceRecorderPlayer,
        private val avEngine: RealtimeAudioVideoEngine,
        private val callManager: CallManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NexusViewModel(repository, voiceEngine, avEngine, callManager) as T
        }
    }
}


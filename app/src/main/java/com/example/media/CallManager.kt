package com.example.media

import android.os.Build
import com.example.data.local.CallRecordEntity
import com.example.data.local.NexusDao
import com.example.domain.model.ActiveCallState
import com.example.domain.model.CallDirection
import com.example.domain.model.CallParticipant
import com.example.domain.model.CallStatus
import com.example.domain.model.CallType
import com.example.domain.model.TransportType
import com.example.domain.model.UserProfile
import com.example.domain.model.VideoQualityProfile
import com.example.network.NexusMeshEngine
import com.example.network.PacketType
import com.example.network.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class CallManager(
    private val dao: NexusDao,
    private val meshEngine: NexusMeshEngine,
    private val avEngine: RealtimeAudioVideoEngine
) {
    companion object {
        private const val RING_TIMEOUT_MS = 45_000L
        private const val PEER_UNREACHABLE_GRACE_SECONDS = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _callState = MutableStateFlow(ActiveCallState())
    val callState: StateFlow<ActiveCallState> = _callState.asStateFlow()

    private var timerJob: Job? = null
    private var ringTimeoutJob: Job? = null
    @Volatile private var localProfile = UserProfile()
    @Volatile private var currentTargetPeerIds: List<String> = emptyList()
    @Volatile private var incomingDirection = false

    init {
        scope.launch {
            meshEngine.incomingPackets.collect { (envelope, transport) ->
                when (envelope.type) {
                    PacketType.CALL_INVITE.name -> handleIncomingInvite(envelope, transport)
                    PacketType.CALL_ACCEPT.name -> handleRemoteAccept(envelope, transport)
                    PacketType.CALL_REJECT.name -> handleRemoteReject(envelope)
                    PacketType.CALL_END.name -> handleRemoteEnd(envelope)
                    PacketType.CALL_MUTE_STATE.name -> handleRemoteMuteState(envelope)
                }
            }
        }
    }

    fun updateProfile(profile: UserProfile) {
        localProfile = profile
        avEngine.updateIdentity(profile.nodeId, profile.nickname)
    }

    fun startOutgoingCall(
        conversationId: String,
        title: String,
        callType: CallType,
        isGroup: Boolean,
        targetPeerIds: List<String>
    ) {
        if (_callState.value.status != CallStatus.IDLE) return

        val callId = "call_" + UUID.randomUUID().toString().take(8)
        currentTargetPeerIds = targetPeerIds
        incomingDirection = false

        val stats = meshEngine.networkStats.value
        val qualityProfile = computeAdaptiveQuality(
            lowBandwidth = localProfile.lowBandwidthMode,
            latencyMs = stats.avgLatencyMs,
            transport = stats.activeTransport
        )
        avEngine.updateVideoQuality(qualityProfile)

        val relayNotice = if (callType == CallType.VIDEO && stats.activeTransport == TransportType.BLUETOOTH) {
            "Bluetooth RFCOMM active: Video frames throttled to Low-Bandwidth mode"
        } else null

        val participants = targetPeerIds.map { pid ->
            val peer = meshEngine.connectedPeers.value.firstOrNull { it.peerId == pid }
                ?: meshEngine.multiHopPeers.value.firstOrNull { it.peerId == pid }
            CallParticipant(
                peerId = pid,
                displayName = peer?.displayName ?: pid,
                avatarIndex = peer?.avatarIndex ?: 0,
                isCameraOn = callType == CallType.VIDEO,
                transport = peer?.transport ?: stats.activeTransport
            )
        }

        _callState.value = ActiveCallState(
            callId = callId,
            conversationId = conversationId,
            callTitle = title,
            callType = callType,
            status = CallStatus.RINGING_OUTGOING,
            isGroupCall = isGroup,
            isSpeakerOn = callType == CallType.VIDEO,
            isCameraEnabled = callType == CallType.VIDEO,
            lowBandwidthMode = localProfile.lowBandwidthMode,
            videoQuality = qualityProfile,
            currentFps = if (callType == CallType.VIDEO) qualityProfile.maxFps else 0,
            transport = stats.activeTransport,
            latencyMs = stats.avgLatencyMs,
            meshNotice = relayNotice,
            participants = participants
        )

        val invite = WireEnvelope(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.CALL_INVITE.name,
            senderId = localProfile.nodeId,
            senderName = localProfile.nickname,
            senderDevice = Build.MODEL ?: "Android",
            senderAvatarIndex = localProfile.avatarIndex,
            conversationId = conversationId,
            conversationTitle = title,
            isGroup = isGroup,
            groupPeerIdsCsv = targetPeerIds.joinToString(","),
            callId = callId,
            callType = callType.name
        )
        val sent = meshEngine.sendEnvelope(invite, targetPeerIds)
        if (!sent) {
            persistCallRecord(CallDirection.OUTGOING, 0L)
            resetCallState()
            return
        }
        startRingTimeout(callId, isIncoming = false)
    }

    fun acceptIncomingCall(asVideo: Boolean? = null) {
        val current = _callState.value
        if (current.status != CallStatus.RINGING_INCOMING) return
        ringTimeoutJob?.cancel()
        val resolvedType = when (asVideo) {
            true -> CallType.VIDEO
            false -> CallType.VOICE
            null -> current.callType
        }

        val accept = WireEnvelope(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.CALL_ACCEPT.name,
            senderId = localProfile.nodeId,
            senderName = localProfile.nickname,
            senderDevice = Build.MODEL ?: "Android",
            senderAvatarIndex = localProfile.avatarIndex,
            conversationId = current.conversationId,
            callId = current.callId,
            callType = resolvedType.name
        )
        meshEngine.sendEnvelope(accept, currentTargetPeerIds)

        _callState.update {
            it.copy(
                callType = resolvedType,
                status = CallStatus.ACTIVE,
                isCameraEnabled = resolvedType == CallType.VIDEO,
                isSpeakerOn = resolvedType == CallType.VIDEO,
                startedAtMs = System.currentTimeMillis()
            )
        }
        startActiveMediaAndTimer()
    }

    fun rejectIncomingCall() {
        val current = _callState.value
        if (current.status != CallStatus.RINGING_INCOMING) return
        val reject = WireEnvelope(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.CALL_REJECT.name,
            senderId = localProfile.nodeId,
            senderName = localProfile.nickname,
            senderDevice = Build.MODEL ?: "Android",
            callId = current.callId
        )
        meshEngine.sendEnvelope(reject, currentTargetPeerIds)
        persistCallRecord(CallDirection.MISSED, durationSec = 0)
        resetCallState()
    }

    fun endCall() {
        val current = _callState.value
        if (current.status == CallStatus.IDLE) return
        val endEnv = WireEnvelope(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.CALL_END.name,
            senderId = localProfile.nodeId,
            senderName = localProfile.nickname,
            senderDevice = Build.MODEL ?: "Android",
            callId = current.callId
        )
        meshEngine.sendEnvelope(endEnv, currentTargetPeerIds)
        val direction = if (incomingDirection) CallDirection.INCOMING else CallDirection.OUTGOING
        persistCallRecord(direction, current.durationSeconds)
        resetCallState()
    }

    fun toggleMute() {
        val next = !_callState.value.isMicMuted
        _callState.update { it.copy(isMicMuted = next) }
        avEngine.setMicMuted(next)
        val stateEnv = WireEnvelope(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.CALL_MUTE_STATE.name,
            senderId = localProfile.nodeId,
            senderName = localProfile.nickname,
            senderDevice = Build.MODEL ?: "Android",
            callId = _callState.value.callId,
            isTyping = next
        )
        meshEngine.sendEnvelope(stateEnv, currentTargetPeerIds)
    }

    fun toggleSpeaker() {
        val next = !_callState.value.isSpeakerOn
        _callState.update { it.copy(isSpeakerOn = next) }
        avEngine.setSpeakerphone(next)
    }

    fun toggleCameraEnabled() {
        val next = !_callState.value.isCameraEnabled
        _callState.update { it.copy(isCameraEnabled = next) }
    }

    fun flipCamera() {
        _callState.update { it.copy(useFrontCamera = !it.useFrontCamera) }
    }

    fun toggleLowBandwidthMode() {
        val next = !_callState.value.lowBandwidthMode
        val stats = meshEngine.networkStats.value
        val quality = computeAdaptiveQuality(next, stats.avgLatencyMs, stats.activeTransport)
        avEngine.updateVideoQuality(quality)
        avEngine.configureAudioSession(
            callId = _callState.value.callId,
            targetPeerIds = currentTargetPeerIds,
            lowBandwidth = next
        )
        _callState.update {
            it.copy(
                lowBandwidthMode = next,
                videoQuality = quality,
                currentFps = if (it.callType == CallType.VIDEO) quality.maxFps else 0
            )
        }
    }

    fun getTargetPeerIds(): List<String> = currentTargetPeerIds

    private fun startRingTimeout(callId: String, isIncoming: Boolean) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            val current = _callState.value
            if (current.callId == callId &&
                (current.status == CallStatus.RINGING_OUTGOING || current.status == CallStatus.RINGING_INCOMING)
            ) {
                val dir = if (isIncoming) CallDirection.MISSED else CallDirection.OUTGOING
                persistCallRecord(dir, 0L)
                resetCallState()
            }
        }
    }

    private fun handleIncomingInvite(envelope: WireEnvelope, transport: TransportType) {
        val incomingCallId = envelope.callId ?: UUID.randomUUID().toString()
        if (_callState.value.status != CallStatus.IDLE) {
            meshEngine.sendUnicastToPeer(
                envelope.senderId,
                WireEnvelope(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.CALL_REJECT.name,
                    senderId = localProfile.nodeId,
                    senderName = localProfile.nickname,
                    senderDevice = Build.MODEL ?: "Android",
                    callId = incomingCallId
                )
            )
            return
        }
        val type = runCatching { CallType.valueOf(envelope.callType ?: CallType.VOICE.name) }
            .getOrDefault(CallType.VOICE)
        incomingDirection = true

        val extraGroupPeers = if (envelope.isGroup) {
            envelope.groupPeerIdsCsv.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != localProfile.nodeId && it != envelope.senderId }
        } else emptyList()
        currentTargetPeerIds = (listOf(envelope.senderId) + extraGroupPeers).distinct()

        val quality = computeAdaptiveQuality(
            lowBandwidth = localProfile.lowBandwidthMode,
            latencyMs = meshEngine.networkStats.value.avgLatencyMs,
            transport = transport
        )
        avEngine.updateVideoQuality(quality)

        val participantList = buildList {
            add(
                CallParticipant(
                    peerId = envelope.senderId,
                    displayName = envelope.senderName,
                    avatarIndex = envelope.senderAvatarIndex,
                    isCameraOn = type == CallType.VIDEO,
                    transport = transport
                )
            )
            extraGroupPeers.forEach { pid ->
                val known = meshEngine.connectedPeers.value.firstOrNull { it.peerId == pid }
                    ?: meshEngine.multiHopPeers.value.firstOrNull { it.peerId == pid }
                add(
                    CallParticipant(
                        peerId = pid,
                        displayName = known?.displayName ?: pid,
                        avatarIndex = known?.avatarIndex ?: 0,
                        isCameraOn = type == CallType.VIDEO,
                        transport = known?.transport ?: transport
                    )
                )
            }
        }

        _callState.value = ActiveCallState(
            callId = incomingCallId,
            conversationId = envelope.conversationId,
            callTitle = if (envelope.isGroup) envelope.conversationTitle else envelope.senderName,
            callType = type,
            status = CallStatus.RINGING_INCOMING,
            isGroupCall = envelope.isGroup,
            isCameraEnabled = type == CallType.VIDEO,
            isSpeakerOn = type == CallType.VIDEO,
            lowBandwidthMode = localProfile.lowBandwidthMode,
            videoQuality = quality,
            transport = transport,
            participants = participantList
        )
        startRingTimeout(incomingCallId, isIncoming = true)
    }

    private fun handleRemoteAccept(envelope: WireEnvelope, transport: TransportType) {
        val current = _callState.value
        if (current.callId != envelope.callId) return
        ringTimeoutJob?.cancel()

        // Ensure participant exists & update transport/status
        val updatedParticipants = if (current.participants.any { it.peerId == envelope.senderId }) {
            current.participants.map {
                if (it.peerId == envelope.senderId) it.copy(transport = transport) else it
            }
        } else {
            current.participants + CallParticipant(
                peerId = envelope.senderId,
                displayName = envelope.senderName,
                avatarIndex = envelope.senderAvatarIndex,
                isCameraOn = current.callType == CallType.VIDEO,
                transport = transport
            )
        }

        if (current.status == CallStatus.RINGING_OUTGOING) {
            _callState.update {
                it.copy(
                    status = CallStatus.ACTIVE,
                    startedAtMs = System.currentTimeMillis(),
                    participants = updatedParticipants
                )
            }
            startActiveMediaAndTimer()
        } else if (current.status == CallStatus.ACTIVE) {
            _callState.update { it.copy(participants = updatedParticipants) }
        }
    }

    private fun handleRemoteReject(envelope: WireEnvelope) {
        val current = _callState.value
        if (current.callId != envelope.callId) return
        if (current.isGroupCall && current.participants.size > 1) {
            _callState.update { state ->
                state.copy(participants = state.participants.filterNot { it.peerId == envelope.senderId })
            }
            return
        }
        persistCallRecord(CallDirection.OUTGOING, 0)
        resetCallState()
    }

    private fun handleRemoteEnd(envelope: WireEnvelope) {
        val current = _callState.value
        if (current.callId != envelope.callId) return
        if (current.isGroupCall && current.participants.size > 1) {
            _callState.update { state ->
                state.copy(participants = state.participants.filterNot { it.peerId == envelope.senderId })
            }
            return
        }
        val dir = if (current.status == CallStatus.RINGING_INCOMING) {
            CallDirection.MISSED
        } else if (incomingDirection) {
            CallDirection.INCOMING
        } else {
            CallDirection.OUTGOING
        }
        persistCallRecord(dir, current.durationSeconds)
        resetCallState()
    }

    private fun handleRemoteMuteState(envelope: WireEnvelope) {
        if (_callState.value.callId != envelope.callId) return
        _callState.update { state ->
            state.copy(
                participants = state.participants.map { p ->
                    if (p.peerId == envelope.senderId) p.copy(isMuted = envelope.isTyping) else p
                }
            )
        }
    }

    private fun startActiveMediaAndTimer() {
        val current = _callState.value
        avEngine.configureAudioSession(
            callId = current.callId,
            targetPeerIds = currentTargetPeerIds,
            lowBandwidth = current.lowBandwidthMode
        )
        avEngine.setSpeakerphone(current.isSpeakerOn)
        avEngine.startAudioTransmit(isPtt = false, targetPeers = currentTargetPeerIds)

        timerJob?.cancel()
        timerJob = scope.launch {
            var unreachableTicks = 0
            while (isActive) {
                delay(1000L)
                val callSnapshot = _callState.value
                if (callSnapshot.status != CallStatus.ACTIVE) break

                val anyReachable = currentTargetPeerIds.any { meshEngine.isPeerReachable(it) }
                if (!anyReachable && currentTargetPeerIds.isNotEmpty()) {
                    unreachableTicks++
                    if (unreachableTicks >= PEER_UNREACHABLE_GRACE_SECONDS) {
                        val dir = if (incomingDirection) CallDirection.INCOMING else CallDirection.OUTGOING
                        persistCallRecord(dir, callSnapshot.durationSeconds)
                        resetCallState()
                        break
                    }
                } else {
                    unreachableTicks = 0
                }

                val started = callSnapshot.startedAtMs
                val stats = meshEngine.networkStats.value
                val adaptive = computeAdaptiveQuality(
                    lowBandwidth = callSnapshot.lowBandwidthMode,
                    latencyMs = stats.avgLatencyMs,
                    transport = stats.activeTransport
                )
                avEngine.updateVideoQuality(adaptive)
                _callState.update {
                    it.copy(
                        durationSeconds = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0L),
                        latencyMs = stats.avgLatencyMs,
                        transport = stats.activeTransport,
                        videoQuality = adaptive,
                        currentFps = if (it.callType == CallType.VIDEO && it.isCameraEnabled) adaptive.maxFps else 0,
                        bitrateKbps = if (it.callType == CallType.VIDEO) adaptive.maxFps * adaptive.jpegQuality else 32,
                        meshNotice = if (!anyReachable && currentTargetPeerIds.isNotEmpty()) {
                            "Peer link interrupted — attempting mesh recovery…"
                        } else if (it.callType == CallType.VIDEO && stats.activeTransport == TransportType.BLUETOOTH) {
                            "Bluetooth RFCOMM active: Video frames throttled to Low-Bandwidth mode"
                        } else null
                    )
                }
            }
        }
    }

    private fun computeAdaptiveQuality(
        lowBandwidth: Boolean,
        latencyMs: Int,
        transport: TransportType
    ): VideoQualityProfile {
        if (lowBandwidth || transport == TransportType.BLUETOOTH || latencyMs > 240) {
            return VideoQualityProfile.LOW_BANDWIDTH
        }
        if (latencyMs in 90..240 || transport == TransportType.HOTSPOT) {
            return VideoQualityProfile.BALANCED
        }
        return VideoQualityProfile.HD_LOCAL
    }

    private fun persistCallRecord(direction: CallDirection, durationSec: Long) {
        val state = _callState.value
        if (state.callId.isBlank()) return
        scope.launch {
            dao.insertCallRecord(
                CallRecordEntity(
                    id = state.callId,
                    conversationId = state.conversationId,
                    title = state.callTitle,
                    callType = state.callType.name,
                    direction = direction.name,
                    isGroup = state.isGroupCall,
                    participantsCsv = state.participants.joinToString(",") { it.displayName },
                    durationSeconds = durationSec,
                    transportUsed = state.transport.name
                )
            )
        }
    }

    private fun resetCallState() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        timerJob?.cancel()
        timerJob = null
        avEngine.stopAllAudio()
        avEngine.stopCameraStream()
        currentTargetPeerIds = emptyList()
        _callState.value = ActiveCallState()
    }
}

package com.example.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.domain.model.VideoQualityProfile
import com.example.network.NexusMeshEngine
import com.example.network.PacketType
import com.example.network.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

class RealtimeAudioVideoEngine(
    private val context: Context,
    private val meshEngine: NexusMeshEngine
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val AUDIO_FRAME_BYTES = 1280 // 40ms @ 16kHz 16-bit mono
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val audioLock = Any()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioCaptureJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var isCapturingAudio = false

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _rxLevel = MutableStateFlow(0f)
    val rxLevel: StateFlow<Float> = _rxLevel.asStateFlow()

    private val _remoteVideoFrames = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val remoteVideoFrames: StateFlow<Map<String, Bitmap>> = _remoteVideoFrames.asStateFlow()

    private val _activePttSpeaker = MutableStateFlow<String?>(null)
    val activePttSpeaker: StateFlow<String?> = _activePttSpeaker.asStateFlow()

    @Volatile private var activeCallTargets: List<String> = emptyList()
    @Volatile private var activeCallId: String = ""
    @Volatile private var activePttChannel: String? = null
    @Volatile private var isPttMode = false
    @Volatile private var isMuted = false
    @Volatile private var lowBandwidthAudio = false
    @Volatile private var localNodeId: String = "NX-0000"
    @Volatile private var localNickname: String = "Node"

    // Video capture state
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var isVideoStreaming = false
    @Volatile private var lastFrameSentMs = 0L
    @Volatile private var currentVideoQuality = VideoQualityProfile.BALANCED
    @Volatile private var currentUsingFrontCamera = true
    private val cameraBindGeneration = AtomicLong(0L)
    private val frameBufferOut = ByteArrayOutputStream(32 * 1024)
    private val rotatedBufferOut = ByteArrayOutputStream(32 * 1024)

    init {
        scope.launch {
            meshEngine.realtimeMediaPackets.collect { (envelope, _) ->
                when (envelope.type) {
                    PacketType.AUDIO_FRAME.name -> {
                        if (envelope.callId == activeCallId && activeCallId.isNotEmpty()) {
                            playIncomingAudioFrame(envelope.payloadBase64, isCompressed8Bit = envelope.isForwarded)
                        }
                    }
                    PacketType.PTT_AUDIO_FRAME.name -> {
                        if (activePttChannel != null && envelope.pttChannel == activePttChannel) {
                            _activePttSpeaker.value = envelope.senderName
                            playIncomingAudioFrame(envelope.payloadBase64, isCompressed8Bit = envelope.isForwarded)
                        }
                    }
                    PacketType.VIDEO_FRAME.name -> {
                        if (envelope.callId == activeCallId && activeCallId.isNotEmpty() && envelope.payloadBase64 != null) {
                            val bytes = runCatching {
                                Base64.decode(envelope.payloadBase64, Base64.DEFAULT)
                            }.getOrNull() ?: return@collect
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@collect
                            _remoteVideoFrames.update { map ->
                                map + (envelope.senderId to bmp)
                            }
                        }
                    }
                }
            }
        }
    }

    fun updateIdentity(nodeId: String, nickname: String) {
        localNodeId = nodeId
        localNickname = nickname
    }

    fun configureAudioSession(
        callId: String,
        targetPeerIds: List<String>,
        pttChannel: String? = null,
        lowBandwidth: Boolean = false
    ) {
        activeCallId = callId
        activeCallTargets = targetPeerIds
        activePttChannel = pttChannel
        isPttMode = pttChannel != null
        lowBandwidthAudio = lowBandwidth
        meshEngine.setActiveRealtimeSession(true)
        requestCommunicationAudioFocus()
        ensureAudioTrackStarted()
    }

    fun setPttChannelListener(channel: String?, lowBandwidth: Boolean) {
        activePttChannel = channel
        lowBandwidthAudio = lowBandwidth
        if (channel != null) {
            meshEngine.setActiveRealtimeSession(true)
            requestCommunicationAudioFocus()
            ensureAudioTrackStarted()
        } else if (activeCallId.isEmpty()) {
            stopAllAudio()
        }
    }

    fun setMicMuted(muted: Boolean) {
        isMuted = muted
    }

    fun setSpeakerphone(enabled: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) audioManager.setCommunicationDevice(speaker)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAudioTransmit(isPtt: Boolean = false, pttChannel: String? = null, targetPeers: List<String> = activeCallTargets) {
        synchronized(audioLock) {
            if (isCapturingAudio) return
            isPttMode = isPtt
            if (pttChannel != null) activePttChannel = pttChannel
            activeCallTargets = targetPeers
            meshEngine.setActiveRealtimeSession(true)
            requestCommunicationAudioFocus()
            ensureAudioTrackStarted()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(AUDIO_FRAME_BYTES * 2)

            val rec = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            }.getOrNull() ?: return

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }

            audioRecord = rec
            isCapturingAudio = true
            rec.startRecording()

            audioCaptureJob?.cancel()
            audioCaptureJob = scope.launch {
                val buffer = ByteArray(AUDIO_FRAME_BYTES)
                while (isActive && isCapturingAudio) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val rms = computePcm16Rms(buffer, read)
                        _micLevel.value = rms
                        if (!isMuted) {
                            val payloadBytes = if (lowBandwidthAudio) {
                                compressPcm16To8Bit(buffer, read)
                            } else {
                                buffer.copyOf(read)
                            }
                            val b64 = Base64.encodeToString(payloadBytes, Base64.NO_WRAP)
                            val envelope = WireEnvelope(
                                packetId = UUID.randomUUID().toString(),
                                type = if (isPttMode) PacketType.PTT_AUDIO_FRAME.name else PacketType.AUDIO_FRAME.name,
                                senderId = localNodeId,
                                senderName = localNickname,
                                senderDevice = Build.MODEL ?: "Android",
                                callId = activeCallId,
                                pttChannel = activePttChannel,
                                isForwarded = lowBandwidthAudio,
                                payloadBase64 = b64
                            )
                            meshEngine.sendEnvelope(envelope, activeCallTargets)
                        }
                    }
                }
            }
        }
    }

    fun stopAudioTransmit() {
        synchronized(audioLock) {
            isCapturingAudio = false
            audioCaptureJob?.cancel()
            audioCaptureJob = null
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            _micLevel.value = 0f
        }
    }

    fun stopAllAudio() {
        stopAudioTransmit()
        synchronized(audioLock) {
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            audioTrack = null
            activeCallId = ""
            activePttChannel = null
            _rxLevel.value = 0f
            _activePttSpeaker.value = null
            restoreNormalAudioMode()
        }
        meshEngine.setActiveRealtimeSession(false)
    }

    fun updateVideoQuality(profile: VideoQualityProfile) {
        currentVideoQuality = profile
    }

    fun bindCameraPreviewAndStream(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        useFrontCamera: Boolean,
        cameraEnabled: Boolean,
        callId: String,
        targetPeerIds: List<String>
    ) {
        val gen = cameraBindGeneration.incrementAndGet()
        activeCallId = callId
        activeCallTargets = targetPeerIds
        currentUsingFrontCamera = useFrontCamera
        isVideoStreaming = cameraEnabled

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()
                if (!cameraEnabled || !isVideoStreaming || cameraBindGeneration.get() != gen) {
                    return@runCatching
                }

                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(currentVideoQuality.width, currentVideoQuality.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processAndSendVideoFrame(imageProxy)
                }

                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCameraStream() {
        cameraBindGeneration.incrementAndGet()
        isVideoStreaming = false
        ContextCompat.getMainExecutor(context).execute {
            runCatching { cameraProvider?.unbindAll() }
        }
        _remoteVideoFrames.value = emptyMap()
    }

    fun release() {
        stopAllAudio()
        stopCameraStream()
        cameraExecutor.shutdownNow()
        scope.cancel()
    }

    private fun processAndSendVideoFrame(imageProxy: ImageProxy) {
        try {
            if (!isVideoStreaming || activeCallTargets.isEmpty()) return
            val now = System.currentTimeMillis()
            val minIntervalMs = (1000L / currentVideoQuality.maxFps.coerceAtLeast(1))
            if (now - lastFrameSentMs < minIntervalMs) return
            lastFrameSentMs = now

            val jpegBytes = imageProxyToJpeg(imageProxy, currentVideoQuality.jpegQuality) ?: return
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val envelope = WireEnvelope(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.VIDEO_FRAME.name,
                senderId = localNodeId,
                senderName = localNickname,
                senderDevice = Build.MODEL ?: "Android",
                callId = activeCallId,
                payloadBase64 = b64
            )
            meshEngine.sendEnvelope(envelope, activeCallTargets)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToJpeg(image: ImageProxy, quality: Int): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        frameBufferOut.reset()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, frameBufferOut)
        val rawJpeg = frameBufferOut.toByteArray()

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0 && !currentUsingFrontCamera) return rawJpeg

        val bmp = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return rawJpeg
        var rotated: Bitmap? = null
        return try {
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (currentUsingFrontCamera) postScale(-1f, 1f)
            }
            rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            rotatedBufferOut.reset()
            rotated.compress(Bitmap.CompressFormat.JPEG, quality, rotatedBufferOut)
            rotatedBufferOut.toByteArray()
        } finally {
            bmp.recycle()
            if (rotated != null && rotated !== bmp) rotated.recycle()
        }
    }

    private fun ensureAudioTrackStarted() {
        synchronized(audioLock) {
            if (audioTrack != null) return
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(AUDIO_FRAME_BYTES * 2)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return

            audioTrack = track
            runCatching { track.play() }
        }
    }

    private fun playIncomingAudioFrame(base64: String?, isCompressed8Bit: Boolean) {
        if (base64.isNullOrBlank()) return
        val raw = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        val pcm16 = if (isCompressed8Bit) decompress8BitToPcm16(raw) else raw
        _rxLevel.value = computePcm16Rms(pcm16, pcm16.size)
        val track = synchronized(audioLock) { audioTrack }
        runCatching { track?.write(pcm16, 0, pcm16.size) }
    }

    private fun requestCommunicationAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setOnAudioFocusChangeListener { change ->
                            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                                stopAudioTransmit()
                            }
                        }
                        .build()
                }
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun restoreNormalAudioMode() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }

    private fun compressPcm16To8Bit(pcm16: ByteArray, length: Int): ByteArray {
        val sampleCount = length / 2
        val out = ByteArray(sampleCount)
        val bb = ByteBuffer.wrap(pcm16, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val s = bb.short.toInt()
            out[i] = (s shr 8).toByte()
        }
        return out
    }

    private fun decompress8BitToPcm16(bytes8: ByteArray): ByteArray {
        val out = ByteArray(bytes8.size * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (b in bytes8) {
            bb.putShort((b.toInt() shl 8).toShort())
        }
        return out
    }

    private fun computePcm16Rms(pcm: ByteArray, len: Int): Float {
        if (len < 2) return 0f
        var sum = 0.0
        val count = len / 2
        val bb = ByteBuffer.wrap(pcm, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val s = bb.short / 32768.0
            sum += s * s
        }
        return (sqrt(sum / count) * 2.6).toFloat().coerceIn(0f, 1f)
    }
}

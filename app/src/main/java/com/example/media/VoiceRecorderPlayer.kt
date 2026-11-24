package com.example.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class VoiceRecordingState(
    val isRecording: Boolean = false,
    val durationMs: Long = 0L,
    val amplitudes: List<Float> = emptyList()
)

data class VoicePlaybackState(
    val playingMessageId: String? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val playbackSpeed: Float = 1.0f
)

data class RecordedVoiceResult(
    val file: File,
    val durationMs: Long,
    val waveformCsv: String
)

class VoiceRecorderPlayer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()

    private var recorder: MediaRecorder? = null
    private var activeRecordingFile: File? = null
    private var recordingStartMs: Long = 0L
    private var recordingJob: Job? = null

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val _recordingState = MutableStateFlow(VoiceRecordingState())
    val recordingState: StateFlow<VoiceRecordingState> = _recordingState.asStateFlow()

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    private val voiceDir: File by lazy {
        File(context.filesDir, "nexus_voice").apply { mkdirs() }
    }

    fun startRecording(): Boolean {
        stopPlayback()
        synchronized(lock) {
            if (recorder != null) return false
            val outputFile = File(voiceDir, "voice_${UUID.randomUUID()}.m4a")
            return try {
                requestFocus()
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                rec.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(32000)
                    setAudioSamplingRate(22050)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                    start()
                }
                recorder = rec
                activeRecordingFile = outputFile
                recordingStartMs = System.currentTimeMillis()
                _recordingState.value = VoiceRecordingState(isRecording = true)

                recordingJob?.cancel()
                recordingJob = scope.launch {
                    while (isActive) {
                        val amp = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }
                            .getOrDefault(0.15f)
                            .coerceIn(0.1f, 1f)
                        val elapsed = System.currentTimeMillis() - recordingStartMs
                        val updatedBars = (_recordingState.value.amplitudes + amp).takeLast(32)
                        _recordingState.value = VoiceRecordingState(
                            isRecording = true,
                            durationMs = elapsed,
                            amplitudes = updatedBars
                        )
                        delay(90L)
                    }
                }
                true
            } catch (_: Exception) {
                runCatching { outputFile.delete() }
                recorder = null
                activeRecordingFile = null
                abandonFocus()
                _recordingState.value = VoiceRecordingState()
                false
            }
        }
    }

    fun stopRecording(save: Boolean): RecordedVoiceResult? {
        synchronized(lock) {
            recordingJob?.cancel()
            recordingJob = null
            val duration = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(500L)
            val capturedAmps = _recordingState.value.amplitudes.ifEmpty { listOf(0.25f, 0.5f, 0.7f, 0.4f, 0.3f) }
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            abandonFocus()
            val file = activeRecordingFile
            activeRecordingFile = null
            _recordingState.value = VoiceRecordingState()

            if (!save || file == null || !file.exists() || file.length() == 0L) {
                runCatching { file?.delete() }
                return null
            }
            val waveformCsv = capturedAmps.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }
            return RecordedVoiceResult(file = file, durationMs = duration, waveformCsv = waveformCsv)
        }
    }

    fun cyclePlaybackSpeed() {
        val current = _playbackState.value.playbackSpeed
        val next = when (current) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        _playbackState.value = _playbackState.value.copy(playbackSpeed = next)
        synchronized(lock) {
            runCatching {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.playbackParams = PlaybackParams().setSpeed(next)
                    }
                }
            }
        }
    }

    fun togglePlayback(messageId: String, filePath: String) {
        val current = _playbackState.value
        if (current.playingMessageId == messageId && current.isPlaying) {
            stopPlayback()
            return
        }
        stopPlayback()
        val speed = _playbackState.value.playbackSpeed

        playbackJob = scope.launch {
            val file = File(filePath)
            if (!file.exists()) return@launch
            try {
                requestFocus()
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    prepare()
                    if (speed != 1.0f) {
                        playbackParams = PlaybackParams().setSpeed(speed)
                    }
                    setOnCompletionListener { stopPlayback() }
                    setOnErrorListener { _, _, _ ->
                        stopPlayback()
                        true
                    }
                    start()
                }
                synchronized(lock) {
                    mediaPlayer = player
                }
                _playbackState.value = VoicePlaybackState(
                    playingMessageId = messageId,
                    isPlaying = true,
                    progress = 0f,
                    playbackSpeed = speed
                )
                while (isActive) {
                    val active = synchronized(lock) { mediaPlayer } ?: break
                    val playing = runCatching { active.isPlaying }.getOrDefault(false)
                    if (!playing) break
                    val total = runCatching { active.duration }.getOrDefault(1).coerceAtLeast(1)
                    val pos = runCatching { active.currentPosition }.getOrDefault(0)
                    _playbackState.value = _playbackState.value.copy(
                        playingMessageId = messageId,
                        isPlaying = true,
                        progress = (pos.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    )
                    delay(80L)
                }
            } catch (_: Exception) {
                stopPlayback()
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        synchronized(lock) {
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
        abandonFocus()
        _playbackState.value = VoicePlaybackState(playbackSpeed = _playbackState.value.playbackSpeed)
    }

    fun release() {
        stopPlayback()
        stopRecording(save = false)
        scope.cancel()
    }

    private fun requestFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    private fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }
}

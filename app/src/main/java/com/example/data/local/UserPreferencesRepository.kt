package com.example.data.local

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.MeshMode
import com.example.domain.model.NexusThemeMode
import com.example.domain.model.TransportType
import com.example.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.nexusDataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_prefs")

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val NODE_ID = stringPreferencesKey("node_id")
        val NICKNAME = stringPreferencesKey("nickname")
        val STATUS_MESSAGE = stringPreferencesKey("status_message")
        val AVATAR_INDEX = intPreferencesKey("avatar_index")
        val PREFERRED_TRANSPORT = stringPreferencesKey("preferred_transport")
        val AUTO_SELECT_TRANSPORT = booleanPreferencesKey("auto_select_transport")
        val MESH_MODE = stringPreferencesKey("mesh_mode")
        val LOW_BANDWIDTH_MODE = booleanPreferencesKey("low_bandwidth_mode")
        val PTT_MESH_FORWARDING = booleanPreferencesKey("ptt_mesh_forwarding")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val VOICE_CODEC_PRESET = stringPreferencesKey("voice_codec_preset")
    }

    private val defaultNodeId: String by lazy {
        "NX-" + UUID.randomUUID().toString().take(6).uppercase()
    }

    private val defaultNickname: String by lazy {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android Node"
        model.take(22)
    }

    val userProfile: Flow<UserProfile> = context.nexusDataStore.data.map { prefs ->
        val transportName = prefs[Keys.PREFERRED_TRANSPORT] ?: TransportType.AUTO.name
        val themeName = prefs[Keys.THEME_MODE] ?: NexusThemeMode.CYBER_CHARCOAL.name
        val meshModeName = prefs[Keys.MESH_MODE] ?: MeshMode.MESH_MODE.name
        UserProfile(
            nodeId = prefs[Keys.NODE_ID] ?: defaultNodeId,
            nickname = prefs[Keys.NICKNAME] ?: defaultNickname,
            statusMessage = prefs[Keys.STATUS_MESSAGE] ?: "Offline & Ready",
            avatarIndex = prefs[Keys.AVATAR_INDEX] ?: 0,
            preferredTransport = runCatching { TransportType.valueOf(transportName) }
                .getOrDefault(TransportType.AUTO),
            autoSelectTransport = prefs[Keys.AUTO_SELECT_TRANSPORT] ?: true,
            meshMode = runCatching { MeshMode.valueOf(meshModeName) }
                .getOrDefault(MeshMode.MESH_MODE),
            lowBandwidthMode = prefs[Keys.LOW_BANDWIDTH_MODE] ?: false,
            pttMeshForwarding = prefs[Keys.PTT_MESH_FORWARDING] ?: true,
            themeMode = runCatching { NexusThemeMode.valueOf(themeName) }
                .getOrDefault(NexusThemeMode.CYBER_CHARCOAL),
            voiceCodecPreset = prefs[Keys.VOICE_CODEC_PRESET] ?: "PCM 16kHz Adaptive (Offline)"
        )
    }

    suspend fun ensureNodeIdInitialized() {
        context.nexusDataStore.edit { prefs ->
            if (!prefs.contains(Keys.NODE_ID)) {
                prefs[Keys.NODE_ID] = defaultNodeId
            }
            if (!prefs.contains(Keys.NICKNAME)) {
                prefs[Keys.NICKNAME] = defaultNickname
            }
        }
    }

    suspend fun updateProfile(nickname: String, statusMessage: String, avatarIndex: Int) {
        context.nexusDataStore.edit { prefs ->
            prefs[Keys.NICKNAME] = nickname.trim().ifEmpty { defaultNickname }
            prefs[Keys.STATUS_MESSAGE] = statusMessage.trim().ifEmpty { "Offline & Ready" }
            prefs[Keys.AVATAR_INDEX] = avatarIndex
        }
    }

    suspend fun updateTransportPreference(preferred: TransportType, autoSelect: Boolean) {
        context.nexusDataStore.edit { prefs ->
            prefs[Keys.PREFERRED_TRANSPORT] = preferred.name
            prefs[Keys.AUTO_SELECT_TRANSPORT] = autoSelect
        }
    }

    suspend fun updateMeshMode(meshMode: MeshMode) {
        context.nexusDataStore.edit { prefs ->
            prefs[Keys.MESH_MODE] = meshMode.name
        }
    }

    suspend fun updateLowBandwidthMode(enabled: Boolean) {
        context.nexusDataStore.edit { prefs ->
            prefs[Keys.LOW_BANDWIDTH_MODE] = enabled
        }
    }

    suspend fun updatePttMeshForwarding(enabled: Boolean) {
        context.nexusDataStore.edit { prefs ->
            prefs[Keys.PTT_MESH_FORWARDING] = enabled
        }
    }

    suspend fun updateThemeMode(themeMode: NexusThemeMode) {
        context.nexusDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = themeMode.name
        }
    }

    suspend fun updateVoiceCodecPreset(preset: String) {
        context.nexusDataStore.edit { prefs ->
            prefs[Keys.VOICE_CODEC_PRESET] = preset
        }
    }
}

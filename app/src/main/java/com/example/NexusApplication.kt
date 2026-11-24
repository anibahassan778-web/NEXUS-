package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.data.local.NexusDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.repository.NexusRepository
import com.example.media.CallManager
import com.example.media.RealtimeAudioVideoEngine
import com.example.media.VoiceRecorderPlayer
import com.example.network.NexusMeshEngine

class NexusApplication : Application() {
    lateinit var database: NexusDatabase
        private set
    lateinit var preferencesRepository: UserPreferencesRepository
        private set
    lateinit var meshEngine: NexusMeshEngine
        private set
    lateinit var repository: NexusRepository
        private set
    lateinit var voiceRecorderPlayer: VoiceRecorderPlayer
        private set
    lateinit var avEngine: RealtimeAudioVideoEngine
        private set
    lateinit var callManager: CallManager
        private set

    private val startedActivities = HashSet<Int>()
    private var appInForeground = false

    private val lifecycleCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit

        override fun onActivityStarted(activity: Activity) {
            startedActivities.add(System.identityHashCode(activity))
            if (!appInForeground) {
                appInForeground = true
                meshEngine.setAppInForeground(true)
                meshEngine.refreshHardwareStatus()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities.remove(System.identityHashCode(activity))
            if (startedActivities.isEmpty() && !activity.isChangingConfigurations && appInForeground) {
                appInForeground = false
                meshEngine.setAppInForeground(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = NexusDatabase.getInstance(this)
        preferencesRepository = UserPreferencesRepository(this)
        meshEngine = NexusMeshEngine(this)
        repository = NexusRepository(
            context = this,
            dao = database.nexusDao(),
            prefs = preferencesRepository,
            meshEngine = meshEngine
        )
        voiceRecorderPlayer = VoiceRecorderPlayer(this)
        avEngine = RealtimeAudioVideoEngine(this, meshEngine)
        callManager = CallManager(
            dao = database.nexusDao(),
            meshEngine = meshEngine,
            avEngine = avEngine
        )
        registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    override fun onTerminate() {
        unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        voiceRecorderPlayer.release()
        avEngine.release()
        meshEngine.release()
        super.onTerminate()
    }
}


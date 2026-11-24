package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.NexusApp
import com.example.ui.NexusViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val app = application as NexusApplication
        setContent {
            val viewModel: NexusViewModel = viewModel(
                factory = NexusViewModel.Factory(
                    repository = app.repository,
                    voiceEngine = app.voiceRecorderPlayer,
                    avEngine = app.avEngine,
                    callManager = app.callManager
                )
            )
            NexusApp(viewModel = viewModel)
        }
    }
}

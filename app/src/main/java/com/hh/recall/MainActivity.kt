package com.hh.recall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hh.recall.ui.RecallApp

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) model.background() })
        setContent { RecallApp(model) }
    }
}

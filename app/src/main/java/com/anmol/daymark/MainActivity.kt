package com.anmol.daymark

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    // Bumped on every resume so the UI re-reads anything saved through the share sheet meanwhile.
    private var resumeTick by mutableIntStateOf(0)
    private var widgetRequest by mutableStateOf<WidgetRequest?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WidgetActions.requestFrom(intent)?.let { widgetRequest = it }
    }

    override fun onResume() {
        super.onResume()
        // A widget's settings page (same process) may have changed the shared look meanwhile.
        Look.load(this)
        resumeTick++
    }

    override fun onStart() {
        super.onStart()
        DeepFocus.appVisible = true
        DeepFocus.poke()
    }

    override fun onStop() {
        super.onStop()
        DeepFocus.appVisible = false
        DeepFocus.poke()
        PrivateVault.onAppHidden()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.load(this)
        Layouts.load(this)
        if (savedInstanceState == null) widgetRequest = WidgetActions.requestFrom(intent)
        setContent {
            // While Private is open, keep it out of screenshots and the recent-apps preview.
            val privateOpen by PrivateVault.unlocked.collectAsState()
            LaunchedEffect(privateOpen) {
                if (privateOpen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            DaymarkTheme {
                ZenTouch { GlassBackdrop { DaymarkApp(BoardStore(this@MainActivity), resumeTick, widgetRequest = widgetRequest, onWidgetRequestHandled = { widgetRequest = null }) } }
            }
        }
        kotlin.concurrent.thread { WidgetActions.refreshAll(applicationContext) }
        // Alarms are wiped by a force-stop with no broadcast; re-set them whenever the app starts.
        BoardStore.enqueue { runCatching { Reminders.ensureChannels(applicationContext); Reminders.sync(applicationContext, BoardStore(applicationContext).load(), force = true) } }
    }
}

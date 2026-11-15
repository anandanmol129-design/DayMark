package com.anmol.daymark

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Rings one alarm-type reminder: the default alarm sound looping on the alarm stream plus vibration,
 * from a foreground service so it outlives the alarm screen. Stops on Dismiss/Done/Snooze, or by
 * itself after [TIMEOUT_MILLIS], leaving a missed-reminder notification.
 */
class AlarmService : Service() {
    data class Ringing(val cardId: String, val title: String, val text: String, val kind: String)

    companion object {
        private const val TAG = "DaymarkAlarm"
        private const val NOTIFICATION_ID = 4100
        private const val TIMEOUT_MILLIS = 5 * 60_000L
        /** What is ringing now, or null. Only one alarm rings at a time. */
        val ringing = MutableStateFlow<Ringing?>(null)

        fun start(context: Context, ringing: Ringing) {
            context.startForegroundService(Intent(context, AlarmService::class.java)
                .putExtra("card", ringing.cardId).putExtra("title", ringing.title).putExtra("text", ringing.text).putExtra("kind", ringing.kind))
        }

        /** Stops the ringing if it is for [cardId] (or anything, when null). */
        fun stop(context: Context, cardId: String? = null) {
            val now = ringing.value ?: return
            if (cardId == null || now.cardId == cardId) context.stopService(Intent(context, AlarmService::class.java))
        }

        fun screenPending(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var timedOut = false
    private val timeout = Runnable {
        timedOut = true
        ringing.value?.let { Reminders.postReminder(this, it.cardId, "Missed: ${it.title}", it.text, CardKind.fromId(it.kind)) }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incoming = intent?.getStringExtra("card")?.let {
            Ringing(it, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("text").orEmpty(), intent.getStringExtra("kind").orEmpty())
        }
        val current = ringing.value
        // Publish before the notification goes up, so the alarm screen it opens finds something ringing.
        if (incoming != null && current == null) ringing.value = incoming
        val showing = ringing.value
        // Every startForegroundService needs a startForeground, even when we're already ringing.
        startForeground(NOTIFICATION_ID, notification(showing ?: Ringing(Reminders.TEST_ID, "Alarm", "", CardKind.EVENT.id)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when {
            incoming == null -> if (showing == null) stopSelf()
            current != null && current.cardId != incoming.cardId ->
                // One ring at a time: a second alarm becomes a notification.
                Reminders.postReminder(this, incoming.cardId, incoming.title, incoming.text, CardKind.fromId(incoming.kind))
            current == null -> {
                startSound()
                startVibration()
                handler.postDelayed(timeout, TIMEOUT_MILLIS)
                // Without notifications there's no full-screen intent; try the screen directly (may be blocked in background).
                if (!Reminders.notificationsAllowed(this)) runCatching { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(r: Ringing): Notification {
        Reminders.ensureChannels(this)
        val screen = screenPending(this)
        return Notification.Builder(this, Reminders.CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(r.title)
            .setContentText(r.text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(screen)
            .apply {
                // Android 14+ may withhold full-screen intents; then this stays a heads-up, ongoing notification with the same actions.
                if (Reminders.fullScreenAllowed(this@AlarmService)) setFullScreenIntent(screen, true)
            }
            .addAction(Notification.Action.Builder(null, "Dismiss", Reminders.actionPending(this, Reminders.ACTION_DISMISS, r.cardId)).build())
            .addAction(Notification.Action.Builder(null, "Snooze ${Reminders.snoozeMinutes(this)} min", Reminders.actionPending(this, Reminders.ACTION_SNOOZE, r.cardId)).build())
            .addAction(Notification.Action.Builder(null, Reminders.doneLabel(r.kind), Reminders.actionPending(this, Reminders.ACTION_DONE, r.cardId)).build())
            .build()
    }

    private fun startSound() {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build().also { audio.requestAudioFocus(it) }
        val candidates = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (uri in candidates) {
            val started = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    setWakeMode(this@AlarmService, PowerManager.PARTIAL_WAKE_LOCK)
                    prepare()
                    start()
                }
            }.onFailure { Log.w(TAG, "Couldn't play $uri", it) }.getOrNull()
            if (started != null) { player = started; return }
        }
    }

    private fun startVibration() {
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else @Suppress("DEPRECATION") vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        runCatching { player?.stop() }
        player?.release()
        player = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        runCatching { getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
        if (!timedOut) getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        ringing.value = null
        super.onDestroy()
    }
}

/** The full-screen alarm: shown over the lock screen while [AlarmService] rings; closes when it stops. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val backdrop = Backdrop.load(this)
        setContent {
            val ring by AlarmService.ringing.collectAsState()
            LaunchedEffect(ring) { if (ring == null) finish() }
            val shown = ring ?: return@setContent
            GlassBackdrop(backdrop) {
                AlarmScreen(shown,
                    onDismiss = { act(shown.cardId, Reminders::dismiss) },
                    onSnooze = { act(shown.cardId, Reminders::snooze) },
                    onDone = { act(shown.cardId, Reminders::done) })
            }
        }
    }

    private fun act(cardId: String, action: (Context, String) -> Unit) {
        val app = applicationContext
        // The action itself stops the ringing (snooze first checks what was ringing).
        BoardStore.enqueue { runCatching { action(app, cardId) } }
        finish()
    }
}

@Composable
private fun AlarmScreen(ring: AlarmService.Ringing, onDismiss: () -> Unit, onSnooze: () -> Unit, onDone: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1_000) } }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(ZenIcon.Alarm, TodoBlue, 34.dp)
            Text(now.format(DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())), color = TodoText, fontSize = 96.sp, fontWeight = FontWeight.Light)
            Text(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())), color = TodoMuted, fontSize = 16.sp)
            Spacer(Modifier.height(40.dp))
            val shape = RoundedCornerShape(24.dp)
            Surface(color = TodoCard, shape = shape, border = GlassStroke, modifier = Modifier.fillMaxWidth().glassBlur(shape)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ring.title, color = TodoText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (ring.text.isNotBlank()) Text(ring.text, color = TodoMuted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AlarmButton("Snooze", TodoCard, TodoText, onSnooze)
            AlarmButton(Reminders.doneLabel(ring.kind), TodoBlue, OnAccent, onDone)
            AlarmButton("Dismiss", TodoCard, TodoText, onDismiss)
        }
    }
}

@Composable
private fun AlarmButton(label: String, color: Color, textColor: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Surface(color = color, shape = shape, border = GlassStroke, modifier = Modifier.fillMaxWidth().height(68.dp).glassBlur(shape).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = textColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

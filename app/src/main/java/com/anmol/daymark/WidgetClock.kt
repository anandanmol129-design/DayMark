package com.anmol.daymark

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps date-based widgets right: an inexact alarm just after each midnight redraws every widget, and the
 * same refresh runs after a reboot, an app update or a clock/time-zone change. Separate from [Reminders].
 */
object WidgetClock {
    const val ACTION_MIDNIGHT = "com.anmol.daymark.widget.MIDNIGHT"
    private const val REQUEST = 0x57D0

    /** Sets (or moves) the single pending alarm for the next midnight. Cheap enough to call on every refresh. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        runCatching {
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000L
            val pending = PendingIntent.getBroadcast(
                app, REQUEST,
                Intent(app, WidgetClockReceiver::class.java).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Inexact on purpose: a few minutes' slack is fine for a date change and costs no exact-alarm privilege.
            alarms.set(AlarmManager.RTC, next, pending)
        }
    }
}

class WidgetClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        BoardStore.enqueue {
            try {
                WidgetActions.refreshAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}

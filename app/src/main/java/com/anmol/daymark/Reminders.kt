package com.anmol.daymark

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal const val REMIND_NOTIFY = "notify"
internal const val REMIND_ALARM = "alarm"
/** Reminder time for an item that has a date but no time. */
internal const val DEFAULT_REMINDER_MINUTE = 9 * 60
/** Reminder time a habit gets when its reminder is turned on without a daily time. */
internal const val DEFAULT_HABIT_REMINDER_MINUTE = 20 * 60
internal val REMINDER_LEADS = listOf(0, 5, 10, 30, 60, 24 * 60)
internal val SNOOZE_CHOICES = listOf(5, 10, 15, 30)

internal fun leadLabel(minutes: Int) = when (minutes) {
    0 -> "At time"
    60 -> "1 hour before"
    24 * 60 -> "1 day before"
    else -> if (minutes % 60 == 0) "${minutes / 60} hours before" else "$minutes min before"
}

internal fun remindTypeLabel(type: String) = when (type) { REMIND_NOTIFY -> "Notification"; REMIND_ALARM -> "Alarm"; else -> "Off" }
internal fun reminderIcon(type: String) = when (type) { REMIND_NOTIFY -> ZenIcon.Bell; REMIND_ALARM -> ZenIcon.Alarm; else -> null }

/** The reminder icon shown after due text, for open items that have a reminder. */
internal fun reminderBadge(card: BoardCard) = if (card.completed) null else reminderIcon(card.remind)

/** One pending alarm: [key] is the card id, or "<id>#snooze" for a snoozed reminder. [day]/[minute] are the occurrence it is for. */
internal data class ReminderPlan(val key: String, val cardId: String, val at: Long, val type: String, val day: Long, val minute: Int)

/**
 * The next time [this] card should remind, or null. [accept] filters candidate trigger times (future
 * enough and not already fired); repeating in-app events and habits move on to their next occurrence
 * when a candidate is rejected, so the next one is computed from now rather than from [BoardCard.dueDay].
 */
internal fun BoardCard.nextReminder(zone: ZoneId, today: Long, accept: (Long) -> Boolean): ReminderPlan? {
    if (remind != REMIND_NOTIFY && remind != REMIND_ALARM) return null
    fun at(day: Long, minute: Int, lead: Int) =
        LocalDateTime.of(LocalDate.ofEpochDay(day), LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli() - lead * 60_000L
    fun plan(day: Long, minute: Int, lead: Int) = at(day, minute, lead).takeIf(accept)?.let { ReminderPlan(id, id, it, remind, day, minute) }
    return when (kind) {
        CardKind.HABIT -> {
            val minute = (dueMinute ?: return null).coerceIn(0, 24 * 60 - 1)
            (today..today + 2).firstNotNullOfOrNull { day -> if (day in checkIns) null else plan(day, minute, 0) }
        }
        CardKind.TASK, CardKind.EVENT -> {
            if (completed) return null
            val due = dueDay ?: return null
            val minute = (dueMinute ?: DEFAULT_REMINDER_MINUTE).coerceIn(0, 24 * 60 - 1)
            val lead = remindLeadMinutes.coerceAtLeast(0)
            if (!repeatsAsEvent()) plan(due, minute, lead)
            else {
                // Leads reach back at most a day or so, so start a little before today.
                val start = maxOf(due, today - 1 - lead / (24 * 60))
                (start..start + 800).firstNotNullOfOrNull { day -> if (occursOn(day)) plan(day, minute, lead) else null }
            }
        }
        else -> null
    }
}

/**
 * Keeps the pending AlarmManager alarms equal to what the saved data asks for. [sync] is called from
 * [BoardStore.save] (every write path) and is idempotent: it diffs against the set recorded in its
 * own preferences, cancels what is no longer wanted and (re)sets only what changed.
 */
object Reminders {
    private const val TAG = "DaymarkReminders"
    private const val PREFS = "daymark_reminders"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_ALARMS = "alarms"
    const val TEST_ID = "daymark-test"
    internal const val ACTION_FIRE = "com.anmol.daymark.reminder.FIRE"
    internal const val ACTION_DONE = "com.anmol.daymark.reminder.DONE"
    internal const val ACTION_SNOOZE = "com.anmol.daymark.reminder.SNOOZE"
    internal const val ACTION_DISMISS = "com.anmol.daymark.reminder.DISMISS"
    internal const val EXTRA_KEY = "key"
    internal const val EXTRA_AT = "at"
    internal const val EXTRA_CARD = "card"
    internal const val EXTRA_TYPE = "type"
    /** Alarms already in the past by up to this much are still kept, so a save racing the alarm can't cancel it. */
    private const val GRACE_MILLIS = 2 * 60_000L
    /** Reminders missed while the phone was off are delivered on boot if no later than this. */
    private const val MISSED_WINDOW_MILLIS = 12 * 60 * 60_000L
    /** An alarm delayed by the system still rings; only one later than this becomes a notification. */
    private const val LATE_ALARM_MILLIS = 2 * 60 * 60_000L
    /** After a reboot, a missed alarm within the limit is re-armed this far ahead so it is delivered (and rings) as a normal exact alarm. */
    private const val REARM_DELAY_MILLIS = 5_000L
    private const val MAX_ALARMS = 300

    /** Bumped when a reminder action changes the store outside the app, so an open app reloads. */
    val externalEdits = MutableStateFlow(0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Settings ----------------------------------------------------------------------------------
    fun defaultType(context: Context): String = prefs(context).getString("default_type", "").orEmpty()
    fun setDefaultType(context: Context, type: String) = prefs(context).edit().putString("default_type", type).apply()
    fun defaultLead(context: Context): Int = prefs(context).getInt("default_lead", 0)
    fun setDefaultLead(context: Context, minutes: Int) = prefs(context).edit().putInt("default_lead", minutes).apply()
    fun snoozeMinutes(context: Context): Int = prefs(context).getInt("snooze_minutes", 10)
    fun setSnoozeMinutes(context: Context, minutes: Int) = prefs(context).edit().putInt("snooze_minutes", minutes).apply()

    // Permissions ---------------------------------------------------------------------------------
    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun needsNotificationPermission(context: Context) =
        Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    fun exactAllowed(context: Context): Boolean = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun fullScreenAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** True when the app is exempt from battery optimisation (phones may delay alarms of restricted apps otherwise). */
    fun batteryUnrestricted(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun batterySettingsIntent(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    fun notificationSettingsIntent(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    fun exactAlarmSettingsIntent(context: Context) = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    fun fullScreenSettingsIntent(context: Context) =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")) else notificationSettingsIntent(context)

    // Sync ----------------------------------------------------------------------------------------
    private fun JSONObject.toPlan(key: String) = ReminderPlan(key, optString("c"), optLong("at"), optString("t"), optLong("d"), optInt("m"))
    private fun ReminderPlan.toJson() = JSONObject().put("c", cardId).put("at", at).put("t", type).put("d", day).put("m", minute)

    private fun readMap(context: Context, name: String): Map<String, ReminderPlan> = runCatching {
        val json = JSONObject(prefs(context).getString(name, "{}") ?: "{}")
        json.keys().asSequence().associateWith { json.getJSONObject(it).toPlan(it) }
    }.getOrDefault(emptyMap())

    private fun writeMap(context: Context, name: String, map: Map<String, ReminderPlan>) =
        prefs(context).edit().putString(name, JSONObject().apply { map.forEach { (k, v) -> put(k, v.toJson()) } }.toString()).apply()

    private fun idSet(context: Context, name: String): List<String> = runCatching {
        val a = JSONArray(prefs(context).getString(name, "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun firedSet(context: Context): List<String> = idSet(context, "fired")

    /** Records "<key>@<time>" in the named id set (kept 3 days); false if it was already there. */
    private fun markOnce(context: Context, name: String, id: String): Boolean {
        val ids = idSet(context, name)
        if (id in ids) return false
        val cutoff = System.currentTimeMillis() - 3 * 24 * 60 * 60_000L
        val kept = (ids + id).filter { (it.substringAfterLast('@').toLongOrNull() ?: 0) > cutoff }.takeLast(200)
        prefs(context).edit().putString(name, JSONArray(kept).toString()).apply()
        return true
    }

    /** Records a delivery; false if this exact reminder was already delivered. */
    private fun markFired(context: Context, key: String, at: Long): Boolean = markOnce(context, "fired", "$key@$at")

    /** Makes the pending alarms match [snapshot]. [force] re-sets every alarm (after boot or a clock change). */
    @Synchronized
    fun sync(context: Context, snapshot: BoardSnapshot, force: Boolean = false) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).toEpochDay()
        val fired = firedSet(ctx).toHashSet()
        val cards = snapshot.cards.associateBy { it.id }
        val desired = mutableListOf<ReminderPlan>()
        snapshot.cards.forEach { card ->
            card.nextReminder(zone, today) { at -> at > now - GRACE_MILLIS && "${card.id}@$at" !in fired }?.let(desired::add)
        }
        // Snoozes live until they fire, as long as their item still wants reminding.
        val snoozes = readMap(ctx, "snoozes").filter { (key, plan) ->
            val card = cards[plan.cardId]
            (plan.cardId == TEST_ID || (card != null && card.remind.isNotBlank() && !card.completed)) && plan.at > now - GRACE_MILLIS && "$key@${plan.at}" !in fired
        }
        desired += snoozes.values.filter { it.cardId != TEST_ID }
        val chosen = desired.sortedBy { it.at }.take(MAX_ALARMS).associateBy { it.key }
        val stored = readMap(ctx, "scheduled")
        val am = ctx.getSystemService(AlarmManager::class.java)
        (stored.keys - chosen.keys).forEach { am.cancel(firePending(ctx, it)) }
        chosen.values.forEach { if (force || stored[it.key] != it) setAlarm(ctx, am, it) }
        if (stored != chosen) writeMap(ctx, "scheduled", chosen)
        if (snoozes.size != readMap(ctx, "snoozes").size) writeMap(ctx, "snoozes", snoozes)
    }

    private fun firePending(context: Context, key: String, at: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("daymark://reminder/${Uri.encode(key)}"))
            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun setAlarm(context: Context, am: AlarmManager, plan: ReminderPlan) {
        val pending = firePending(context, plan.key, plan.at)
        val exact = am.canScheduleExactAlarms()
        try {
            when {
                // Alarm clocks are exempt from Doze and show the status-bar alarm icon.
                exact && plan.type == REMIND_ALARM -> am.setAlarmClock(AlarmManager.AlarmClockInfo(plan.at, openAppPending(context, plan.cardId)), pending)
                exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.at, pending)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.at, pending)
            }
        } catch (e: SecurityException) {
            // Exact alarms were revoked between the check and the call: fall back to inexact.
            Log.w(TAG, "Exact alarm refused, using inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.at, pending)
        }
    }

    /**
     * Whether a reminder found missed on boot should be re-armed as a real alarm instead of posted as "Missed": alarm-type,
     * no later than [LATE_ALARM_MILLIS], exact alarms allowed, and not already re-armed once (that is what stops a loop).
     * Starting the ringing service straight from BOOT_COMPLETED isn't allowed on Android 15, but an exact alarm's delivery may.
     */
    internal fun shouldRearm(plan: ReminderPlan, now: Long, exactAllowed: Boolean, alreadyRearmed: Boolean) =
        plan.type == REMIND_ALARM && exactAllowed && !alreadyRearmed && plan.at <= now && now - plan.at <= LATE_ALARM_MILLIS

    /** Re-arms [plan] a few seconds ahead as a snooze-keyed alarm-clock alarm (survives [sync]); false if it couldn't be. */
    private fun rearm(ctx: Context, am: AlarmManager, plan: ReminderPlan, now: Long): Boolean {
        val rearmed = plan.copy(key = "${plan.cardId}#snooze", at = now + REARM_DELAY_MILLIS)
        synchronized(this) {
            val origin = "${plan.key}@${plan.at}"
            if (!shouldRearm(plan, now, am.canScheduleExactAlarms(), alreadyRearmed = origin in idSet(ctx, "rearmed"))) return false
            markOnce(ctx, "rearmed", origin)
            // The re-armed plan is marked too, so a second reboot within its few seconds posts "Missed" rather than re-arming again.
            markOnce(ctx, "rearmed", "${rearmed.key}@${rearmed.at}")
            markFired(ctx, plan.key, plan.at)
            writeMap(ctx, "scheduled", readMap(ctx, "scheduled") - plan.key)
            writeMap(ctx, "snoozes", readMap(ctx, "snoozes") + (rearmed.key to rearmed))
        }
        return runCatching { setAlarm(ctx, am, rearmed) }.isSuccess
    }

    /** Re-sets everything (boot, app update, clock or zone change). On boot, missed alarms within the limit ring again; older ones become "Missed" notifications. */
    fun reschedule(context: Context, deliverMissed: Boolean) {
        val ctx = context.applicationContext
        if (deliverMissed) {
            val now = System.currentTimeMillis()
            val am = ctx.getSystemService(AlarmManager::class.java)
            (readMap(ctx, "scheduled") + readMap(ctx, "snoozes")).values
                .filter { it.at <= now && it.at > now - MISSED_WINDOW_MILLIS }
                .forEach { if (!rearm(ctx, am, it, now)) fire(ctx, it.key, it.at, missed = true) }
        }
        sync(ctx, BoardStore(ctx).load(), force = true)
    }

    // Firing --------------------------------------------------------------------------------------
    /** Handles an alarm going off. Runs on the store's writer thread. */
    internal fun fire(context: Context, key: String, at: Long, missed: Boolean = false) {
        val ctx = context.applicationContext
        val isSnooze = key.endsWith("#snooze")
        val plan = synchronized(this) {
            val scheduled = readMap(ctx, "scheduled")
            val snoozes = readMap(ctx, "snoozes")
            val plan = scheduled[key] ?: snoozes[key]
            // A stale alarm (the item changed after it was set) or a repeat delivery is ignored.
            if (plan == null || plan.at != at || !markFired(ctx, key, at)) { Log.i(TAG, "Ignoring stale reminder $key"); return }
            writeMap(ctx, "scheduled", scheduled - key)
            if (isSnooze) writeMap(ctx, "snoozes", snoozes - key)
            plan
        }
        if (plan.cardId == TEST_ID) { deliver(ctx, testCard(plan.type), plan, missed); return }
        val snapshot = BoardStore(ctx).load()
        val card = snapshot.cards.firstOrNull { it.id == plan.cardId }
        val today = LocalDate.now().toEpochDay()
        val wanted = card != null && card.remind.isNotBlank() && !card.completed && !(card.kind == CardKind.HABIT && card.checkedOn(today))
        if (wanted) deliver(ctx, card!!, plan, missed || System.currentTimeMillis() - at > LATE_ALARM_MILLIS)
        // Schedules the next occurrence of repeating events and habits.
        sync(ctx, snapshot)
    }

    private fun testCard(type: String) = BoardCard(id = TEST_ID, workspace = Workspace.TODOS, columnId = "", kind = CardKind.EVENT, title = if (type == REMIND_ALARM) "Test alarm" else "Test notification", remind = type)

    private fun deliver(context: Context, card: BoardCard, plan: ReminderPlan, late: Boolean) {
        ensureChannels(context)
        val text = describe(card, plan)
        if (plan.type == REMIND_ALARM && !late) {
            if (AlarmService.ringing.value == null) {
                val started = runCatching { AlarmService.start(context, AlarmService.Ringing(card.id, card.title, text, card.kind.id)) }
                    .onFailure { Log.w(TAG, "Couldn't start alarm service", it) }.isSuccess
                if (started) return
            }
            // Another alarm is ringing (or the service can't start): don't stack sound, post it instead.
            postReminder(context, card.id, card.title, text, card.kind)
            return
        }
        postReminder(context, card.id, if (late) "Missed: ${card.title}" else card.title, text, card.kind)
    }

    /** "Today · 03:00 PM · in 10 min" style line for a reminder. */
    internal fun describe(card: BoardCard, plan: ReminderPlan): String = when {
        card.id == TEST_ID -> "Reminders are working"
        card.kind == CardKind.HABIT -> "Daily at ${formatClock(plan.minute)} · not checked in yet"
        else -> listOfNotNull(formatDue(plan.day), formatClock(plan.minute), card.remindLeadMinutes.takeIf { it > 0 && !plan.key.endsWith("#snooze") }?.let { "starts in " + leadLabel(it).removeSuffix(" before") })
            .joinToString(" · ")
    }

    // Notifications ------------------------------------------------------------------------------
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_REMINDERS) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Task, event and habit reminders" }
        )
        if (nm.getNotificationChannel(CHANNEL_ALARMS) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders that ring like an alarm clock"
                // The alarm service plays the sound and vibration itself, on the alarm stream.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    internal fun notificationId(cardId: String) = cardId.hashCode() and 0x7FFFFFFF or 0x10000

    internal fun openAppPending(context: Context, cardId: String): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).setData(Uri.parse("daymark://open/${Uri.encode(cardId)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal fun actionPending(context: Context, action: String, cardId: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("daymark://action/${Uri.encode(cardId)}/${action.substringAfterLast('.')}"))
            .putExtra(EXTRA_CARD, cardId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal fun doneLabel(kind: String) = if (kind == CardKind.EVENT.id) "Got it" else "Done"

    /** A reminder notification with Done and Snooze actions; also used for missed alarms. */
    fun postReminder(context: Context, cardId: String, title: String, text: String, kind: CardKind) {
        ensureChannels(context)
        if (!notificationsAllowed(context)) { Log.w(TAG, "Notifications are off; reminder for $cardId not shown"); return }
        val notification = Notification.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(openAppPending(context, cardId))
            .addAction(Notification.Action.Builder(null, doneLabel(kind.id), actionPending(context, ACTION_DONE, cardId)).build())
            .addAction(Notification.Action.Builder(null, "Snooze ${snoozeMinutes(context)} min", actionPending(context, ACTION_SNOOZE, cardId)).build())
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(notificationId(cardId), notification) }
            .onFailure { Log.w(TAG, "Couldn't post reminder", it) }
    }

    fun sendTestNotification(context: Context) =
        postReminder(context, TEST_ID, "Test notification", "Reminders from Daymark will look like this", CardKind.EVENT)

    /** Sets a real alarm-clock alarm a few seconds out, through the same path as item reminders. */
    @Synchronized
    fun scheduleTestAlarm(context: Context, delaySeconds: Int = 5) {
        val ctx = context.applicationContext
        val now = LocalDateTime.now()
        val plan = ReminderPlan("$TEST_ID#snooze", TEST_ID, System.currentTimeMillis() + delaySeconds * 1000L, REMIND_ALARM, now.toLocalDate().toEpochDay(), now.hour * 60 + now.minute)
        writeMap(ctx, "snoozes", readMap(ctx, "snoozes") + (plan.key to plan))
        setAlarm(ctx, ctx.getSystemService(AlarmManager::class.java), plan)
    }

    // Actions -------------------------------------------------------------------------------------
    /** Removes a reminder's notification and stops its ringing. */
    private fun clear(context: Context, cardId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(cardId))
        AlarmService.stop(context, cardId)
    }

    /** Done: ticks a task off (repeats spawn their next one), checks a habit in, and just dismisses an event. Writer thread. */
    internal fun done(context: Context, cardId: String) {
        val ctx = context.applicationContext
        clear(ctx, cardId)
        if (cardId == TEST_ID) return
        synchronized(this) { writeMap(ctx, "snoozes", readMap(ctx, "snoozes") - "$cardId#snooze") }
        val store = BoardStore(ctx)
        val snapshot = store.load()
        val card = snapshot.cards.firstOrNull { it.id == cardId } ?: return
        val today = LocalDate.now().toEpochDay()
        val next = when (card.kind) {
            CardKind.TASK -> snapshot.completeCard(cardId, today)
            CardKind.HABIT -> if (card.checkedOn(today)) snapshot else snapshot.toggleCheckIn(cardId, today, today)
            else -> snapshot
        }
        if (next !== snapshot) {
            store.save(next)
            WidgetActions.refreshAll(ctx)
            externalEdits.value++
        } else sync(ctx, snapshot)
    }

    /** Snooze: rings (or notifies) again after the snooze length, as the same type. Writer thread. */
    internal fun snooze(context: Context, cardId: String) {
        val ctx = context.applicationContext
        val type = if (AlarmService.ringing.value?.cardId == cardId) REMIND_ALARM else null
        clear(ctx, cardId)
        val snapshot = BoardStore(ctx).load()
        val card = snapshot.cards.firstOrNull { it.id == cardId }
        if (cardId != TEST_ID && card == null) return
        val minutes = snoozeMinutes(ctx)
        val at = System.currentTimeMillis() + minutes * 60_000L
        val later = LocalDateTime.now().plusMinutes(minutes.toLong())
        val plan = ReminderPlan("$cardId#snooze", cardId, at, type ?: card?.remind?.ifBlank { null } ?: REMIND_NOTIFY, later.toLocalDate().toEpochDay(), later.hour * 60 + later.minute)
        synchronized(this) { writeMap(ctx, "snoozes", readMap(ctx, "snoozes") + (plan.key to plan)) }
        Log.i(TAG, "Snoozed $cardId until $later")
        // The test alarm isn't a card, so sync wouldn't set it; set it directly.
        if (cardId == TEST_ID) setAlarm(ctx, ctx.getSystemService(AlarmManager::class.java), plan) else sync(ctx, snapshot)
    }

    internal fun dismiss(context: Context, cardId: String) = clear(context.applicationContext, cardId)

    /** Runs [block] behind pending saves, keeping the receiver alive until it finishes. */
    internal fun BroadcastReceiver.inBackground(block: () -> Unit) {
        val pending = goAsync()
        BoardStore.enqueue {
            try { block() } catch (e: Exception) { Log.e(TAG, "Reminder work failed", e) } finally { pending.finish() }
        }
    }
}

/** Alarm deliveries and notification/alarm-screen actions. Not exported: only our PendingIntents reach it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        with(Reminders) {
            when (intent.action) {
                ACTION_FIRE -> {
                    val key = intent.getStringExtra(EXTRA_KEY) ?: return
                    val at = intent.getLongExtra(EXTRA_AT, 0)
                    inBackground { fire(app, key, at) }
                }
                ACTION_DONE -> intent.getStringExtra(EXTRA_CARD)?.let { id -> inBackground { done(app, id) } }
                ACTION_SNOOZE -> intent.getStringExtra(EXTRA_CARD)?.let { id -> inBackground { snooze(app, id) } }
                ACTION_DISMISS -> intent.getStringExtra(EXTRA_CARD)?.let { id -> dismiss(app, id) }
            }
        }
    }
}

/** Puts alarms back after a reboot, an app update, a clock or time-zone change, or the exact-alarm permission being granted. */
class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val app = context.applicationContext
        // Exact alarms just became allowed (or were taken away again): re-set everything, now exact. Nothing was "missed" by that.
        if (action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED && !Reminders.exactAllowed(app)) return
        with(Reminders) { inBackground { reschedule(app, deliverMissed = action == Intent.ACTION_BOOT_COMPLETED); runCatching { PinnedNotes.sync(app, BoardStore(app).load(), force = true) } } }
    }
}

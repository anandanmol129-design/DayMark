package com.anmol.daymark

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opens a habit in the composer (its daily time and reminder); habits have no tap-to-edit on the board. */
internal val LocalEditHabit = androidx.compose.runtime.staticCompositionLocalOf<((BoardCard) -> Unit)?> { null }

/** Settings › Reminders: defaults for new items, snooze length, what the system allows, and test rows. */
@Composable
internal fun RemindersSettings(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(Reminders.defaultType(context)) }
    var lead by remember { mutableIntStateOf(Reminders.defaultLead(context)) }
    var snooze by remember { mutableIntStateOf(Reminders.snoozeMinutes(context)) }
    // Permissions can change in system settings while this is open; re-check every second.
    var notifications by remember { mutableStateOf(Reminders.notificationsAllowed(context)) }
    var exact by remember { mutableStateOf(Reminders.exactAllowed(context)) }
    var fullScreen by remember { mutableStateOf(Reminders.fullScreenAllowed(context)) }
    var battery by remember { mutableStateOf(Reminders.batteryUnrestricted(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            notifications = Reminders.notificationsAllowed(context); exact = Reminders.exactAllowed(context); fullScreen = Reminders.fullScreenAllowed(context); battery = Reminders.batteryUnrestricted(context)
            delay(1_000)
        }
    }
    fun message(text: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifications = Reminders.notificationsAllowed(context)
        if (granted) afterGrant?.invoke() else message("Notifications are off. Tap “Notifications” below to allow them.")
        afterGrant = null
    }
    fun withNotifications(block: () -> Unit) {
        if (Reminders.needsNotificationPermission(context)) { afterGrant = block; permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else if (!Reminders.notificationsAllowed(context)) message("Notifications are off. Tap “Notifications” below to allow them.")
        else block()
    }
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Reminders.notificationSettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    if (!exact) ReminderSystemHints("")
    SettingsSection("Reminders") {
        SettingsRow(reminderIcon(type) ?: ZenIcon.BellOff, "Default for new items", remindTypeLabel(type) + " · tap to change") {
            val next = listOf("", REMIND_NOTIFY, REMIND_ALARM).let { it[(it.indexOf(type) + 1) % it.size] }
            type = next; Reminders.setDefaultType(context, next)
            if (next.isNotBlank()) withNotifications {}
        }
        SettingsDivider()
        SettingsRow(ZenIcon.Clock, "Default reminder time", leadLabel(lead) + " · tap to change") {
            lead = REMINDER_LEADS[(REMINDER_LEADS.indexOf(lead) + 1) % REMINDER_LEADS.size]; Reminders.setDefaultLead(context, lead)
        }
        SettingsDivider()
        SettingsRow(ZenIcon.Repeat, "Snooze length", "$snooze minutes · tap to change") {
            snooze = SNOOZE_CHOICES[(SNOOZE_CHOICES.indexOf(snooze) + 1) % SNOOZE_CHOICES.size]; Reminders.setSnoozeMinutes(context, snooze)
        }
        SettingsDivider()
        SettingsRow(if (notifications) ZenIcon.CircleCheck else ZenIcon.Alert, "Notifications", if (notifications) "Allowed" else "Blocked · reminders can’t show") {
            if (Reminders.needsNotificationPermission(context)) withNotifications {} else open(Reminders.notificationSettingsIntent(context))
        }
        SettingsDivider()
        SettingsRow(if (exact) ZenIcon.CircleCheck else ZenIcon.Alert, "Exact alarms", if (exact) "Allowed · reminders go off on the minute" else "Not allowed · tap to allow “Alarms & reminders”") {
            open(Reminders.exactAlarmSettingsIntent(context))
        }
        SettingsDivider()
        SettingsRow(if (fullScreen) ZenIcon.CircleCheck else ZenIcon.Alert, "Full-screen alarms", if (fullScreen) "Allowed · alarms open over the lock screen" else "Not allowed · alarms show as a notification") {
            open(Reminders.fullScreenSettingsIntent(context))
        }
        SettingsDivider()
        SettingsRow(if (battery) ZenIcon.CircleCheck else ZenIcon.Alert, "Battery", if (battery) "Unrestricted · alarms aren’t held back" else "Optimised · tap, then Battery, to allow background use") {
            open(Reminders.batterySettingsIntent(context))
        }
        SettingsDivider()
        SettingsRow(ZenIcon.Bell, "Send test notification", "Shows a reminder right now") {
            withNotifications { Reminders.sendTestNotification(context); message("Test notification sent") }
        }
        SettingsDivider()
        SettingsRow(ZenIcon.Alarm, "Test alarm", "Rings in 5 seconds · lock the screen to see it full-screen") {
            withNotifications { Reminders.scheduleTestAlarm(context); message("Alarm rings in 5 seconds") }
        }
    }
    Text("Some phones delay or drop alarms for apps with restricted battery use. If an alarm ever arrives late, set Daymark’s battery use to Unrestricted.",
        color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))
    Text("Reminders are set on each task, event or habit with the Remind chip. Alarms ring on the alarm volume until you dismiss them, and stop by themselves after 5 minutes.",
        color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))
}

/** Inline prompts for system access that makes alarms unreliable: exact alarms (always), full-screen alarms (when [type] is an alarm). Re-checks as the user comes back from system settings. */
@Composable
internal fun ReminderSystemHints(type: String) {
    val context = LocalContext.current
    var exact by remember { mutableStateOf(Reminders.exactAllowed(context)) }
    var fullScreen by remember { mutableStateOf(Reminders.fullScreenAllowed(context)) }
    LaunchedEffect(Unit) { while (true) { exact = Reminders.exactAllowed(context); fullScreen = Reminders.fullScreenAllowed(context); delay(1_000) } }
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Reminders.notificationSettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
    if (!exact) HintLine("Alarms & reminders access is off, so reminders can arrive minutes late.", "Allow") { open(Reminders.exactAlarmSettingsIntent(context)) }
    if (type == REMIND_ALARM && !fullScreen) HintLine("Full-screen alarms are off: this alarm will ring, but only show as a notification.", "Allow") { open(Reminders.fullScreenSettingsIntent(context)) }
}

@Composable
private fun HintLine(text: String, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Amber, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(button, color = TodoBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

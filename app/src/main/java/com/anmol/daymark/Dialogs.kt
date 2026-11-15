package com.anmol.daymark

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class AppChoice(val packageName: String, val label: String)

@Composable
internal fun NewColumnDialog(workspace: Workspace, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable(workspace.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogBlur(); Text("New ${workspace.title.lowercase()} column") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("Column name") }, singleLine = true) },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Add column") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun NewPageDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassDialog,
        title = { DialogBlur(); Text("New folder", color = TodoText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A folder is its own list of tasks. Swipe or tap the chips to switch.", color = TodoMuted, fontSize = 14.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = TodoBlue)) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TodoMuted) } }
    )
}

@Composable
internal fun RenamePageDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassDialog,
        title = { DialogBlur(); Text("Rename folder", color = TodoText) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onRename(name.trim()) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = TodoBlue)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TodoMuted) } }
    )
}

@Composable
internal fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassDialog,
        title = { DialogBlur(); Text("New folder", color = TodoText) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = TodoBlue)) {
                Text("Create folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TodoMuted) } }
    )
}

@Composable
internal fun AppPickerDialog(apps: List<AppChoice>, onDismiss: () -> Unit, onSelect: (AppChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassDialog,
        title = { DialogBlur(); Text("Choose an app") },
        text = {
            if (apps.isEmpty()) Text("No launchable apps found on this device.", color = Muted)
            else LazyColumn(Modifier.height(360.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    TextButton(onClick = { onSelect(app) }, modifier = Modifier.fillMaxWidth()) { Text(app.label, color = Ink, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Green) } }
    )
}

internal fun installedApps(context: Context): List<AppChoice> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { AppChoice(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

internal fun launchTarget(context: Context, card: BoardCard): String? {
    return try {
        if (card.deepLink.isNotBlank()) {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(card.deepLink)).apply {
                if (card.appPackage.isNotBlank()) setPackage(card.appPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(deepLinkIntent)
                null
            } catch (_: ActivityNotFoundException) {
                context.packageManager.getLaunchIntentForPackage(card.appPackage)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
                    ?: "Couldn’t open that app link. Check the link or app."
            }
        } else if (card.appPackage.isNotBlank()) {
            val launch = context.packageManager.getLaunchIntentForPackage(card.appPackage)
            if (launch == null) "${card.appLabel.ifBlank { "That app" }} is no longer available."
            else { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
        } else null
    } catch (_: ActivityNotFoundException) {
        "Couldn’t open ${card.appLabel.ifBlank { "that app" }}."
    } catch (_: SecurityException) {
        "Android blocked this app link. Try launching the app directly."
    }
}

package com.anmol.daymark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * The small sheet a compact task widget opens: the task's page, a complete circle with its title, its due
 * date, and Complete / Edit in app / date quick-picks. A transparent, bottom-anchored activity over the
 * home screen; a tap outside it, or back, dismisses it.
 */
class QuickTaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getStringExtra(EXTRA_TASK)
        val snapshot = BoardStore(this).load()
        val card = snapshot.cards.firstOrNull { it.id == taskId }
        if (taskId == null || card == null) { finish(); return }
        val column = snapshot.columns.firstOrNull { it.id == card.columnId }
        val pageName = snapshot.todoPages.firstOrNull { it.id == column?.pageId }?.name ?: "To-dos"
        val app = applicationContext

        /** Read-modify-write behind any pending saves, then redraw the widgets. */
        fun change(edit: (BoardSnapshot) -> BoardSnapshot) = BoardStore.enqueue {
            val store = BoardStore(app)
            store.save(edit(store.load()))
            WidgetActions.refreshAll(app)
        }

        // Sheets opened from a widget follow the app's theme, in that widget's tab colour.
        Look.load(this)
        Look.accent = Accents.TODOS
        setContent {
            var current by remember { mutableStateOf(card) }
            var done by remember { mutableStateOf(false) }
            val today = LocalDate.now().toEpochDay()
            // Let the tick show for a moment before the sheet goes away.
            LaunchedEffect(done) { if (done) { delay(380); finish() } }

            MaterialTheme(colorScheme = darkColorScheme(primary = TodoBlue, onPrimary = OnAccent, background = TodoBackground, surface = GlassSheet, onSurface = TodoText)) {
                // The scrim dims the home screen; tapping it dismisses.
                Box(Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(onClick = ::finish), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(SheetSolid).clickable(enabled = false) {}
                            .navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 18.dp)
                    ) {
                        Text(pageName, color = TodoMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.padding(top = 2.dp).size(26.dp).clip(CircleShape)
                                    .background(if (done) TodoBlue else Color.Transparent)
                                    .border(2.dp, if (done) TodoBlue else TodoMuted, CircleShape)
                                    .clickable(enabled = !done) { complete(::change, current.id, today) { done = true } },
                                contentAlignment = Alignment.Center
                            ) { if (done) Glyph(ZenIcon.Check, OnAccent, 15.dp) }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                current.title.ifBlank { "Untitled" }, color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(dueText(current), color = if (current.dueDay != null && current.dueDay!! < today) Danger else TodoMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 40.dp))
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Today" to today, "Tomorrow" to today + 1, "No date" to null).forEach { (label, day) ->
                                val selected = current.dueDay == day
                                Box(
                                    Modifier.clip(RoundedCornerShape(14.dp))
                                        .background(if (selected) PaleGreen else Color.Transparent)
                                        .border(1.dp, if (selected) TodoBlue else veil(0.20f), RoundedCornerShape(14.dp))
                                        .clickable {
                                            val id = current.id
                                            current = current.copy(
                                                dueDay = day,
                                                dueMinute = if (day == null) null else current.dueMinute,
                                                repeatAnchor = if (current.repeat.isBlank()) current.repeatAnchor else day
                                            )
                                            change { s ->
                                                s.copy(cards = s.cards.map {
                                                    if (it.id == id) it.copy(
                                                        dueDay = day,
                                                        dueMinute = if (day == null) null else it.dueMinute,
                                                        repeatAnchor = if (it.repeat.isBlank()) it.repeatAnchor else day
                                                    ) else it
                                                })
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) { Text(label, color = if (selected) TodoText else TodoMuted, fontSize = 14.sp, maxLines = 1) }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(TodoBlue)
                                    .clickable(enabled = !done) { complete(::change, current.id, today) { done = true } }.padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("Complete", color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).border(BorderStroke(1.dp, veil(0.20f)), RoundedCornerShape(16.dp))
                                    .clickable {
                                        // Opens the app on this task's edit sheet (WidgetActions.CARD_EDIT).
                                        startActivity(WidgetActions.appIntent(this@QuickTaskActivity, WidgetActions.CARD_EDIT, current.id))
                                        finish()
                                    }.padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("Edit in app", color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            }
        }
    }

    private fun complete(change: ((BoardSnapshot) -> BoardSnapshot) -> Unit, id: String, today: Long, shown: () -> Unit) {
        shown()
        change { it.completeCard(id, today) }
    }

    private fun dueText(card: BoardCard): String {
        val day = card.dueDay ?: return "No date"
        val base = formatDue(day)
        return card.dueMinute?.let { "$base · ${formatClock(it)}" } ?: base
    }

    companion object {
        const val EXTRA_TASK = "task"
    }
}

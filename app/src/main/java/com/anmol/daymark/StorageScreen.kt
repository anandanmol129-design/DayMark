package com.anmol.daymark

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One file in the app's storage and what it belongs to. [owner] is null for a file no note uses any more. */
data class StoredFile(val path: String, val size: Long, val kind: FileKind, val name: String, val owner: String?, val isPrivate: Boolean)

object StorageScan {
    /** Every stored picture and attachment, largest first. Runs on a background thread. */
    fun files(context: Context, snapshot: BoardSnapshot): List<StoredFile> {
        data class Ref(val name: String, val kind: FileKind, val owner: String, val isPrivate: Boolean)
        val saved = snapshot.columns.filter { it.pageId == DEFAULT_SAVED_PAGE_ID }.map { it.id }.toSet()
        val refs = HashMap<String, Ref>()
        snapshot.cards.forEach { card ->
            // Private items never show their titles or pictures outside Private.
            val owner = if (card.isPrivate) "a private note" else "“" + card.displayTitle.ifBlank { "Untitled" } + "”"
            if (card.imagePath.isNotBlank()) {
                val label = if (card.columnId in saved || card.link.isNotBlank()) "Saved picture" else "Cover picture"
                refs.putIfAbsent(card.imagePath, Ref(label, FileKind.IMAGE, owner, card.isPrivate))
            }
            Markdown.imagePaths(card.details).forEach { refs.putIfAbsent(it, Ref("Picture", FileKind.IMAGE, owner, card.isPrivate)) }
            Markdown.attachments(card.details).forEach { (name, path) -> refs.putIfAbsent(path, Ref(if (card.isPrivate) "Private file" else name, Attachments.kindOf(name), owner, card.isPrivate)) }
        }
        return Saves.imageDir(context).listFiles().orEmpty().filter { it.isFile }.map { file ->
            val ref = refs[file.absolutePath]
            StoredFile(
                path = file.absolutePath,
                size = file.length(),
                kind = ref?.kind ?: if (file.extension == "img") FileKind.IMAGE else Attachments.kindOf(file.name),
                name = ref?.name ?: if (file.extension == "img") "Picture" else "File (.${file.extension})",
                owner = ref?.owner,
                isPrivate = ref?.isPrivate == true
            )
        }.sortedByDescending { it.size }
    }

    /** Leftovers from sharing backups and cropping pictures; safe to clear at any time. */
    fun cacheBytes(context: Context): Long {
        // Files under cache/open are mostly hard links to stored attachments (see Attachments.open), so they take no extra space.
        val links = File(context.cacheDir, "open")
        return context.cacheDir.walkTopDown().onEnter { it != links }.filter { it.isFile }.sumOf { it.length() }
    }

    fun clearCache(context: Context) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}

private enum class StorageFilter(val label: String) { ALL("All"), IMAGE("Pictures"), DOCUMENT("Documents"), AUDIO("Audio"), VIDEO("Video"), OTHER("Other"), UNUSED("Not in a note");
    fun matches(file: StoredFile) = when (this) {
        ALL -> true
        UNUSED -> file.owner == null
        else -> file.kind.name == name
    }
}

/**
 * Settings › Storage: every picture and file kept with notes, biggest first. Tick the ones to remove;
 * deleting takes them out of their notes and frees the space.
 */
@Composable
internal fun StorageScreen(snapshot: BoardSnapshot, snackbar: SnackbarHostState, onBack: () -> Unit, onRemoveFromNotes: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val files by produceState<List<StoredFile>?>(null, snapshot, reload) { value = withContext(Dispatchers.IO) { StorageScan.files(context, snapshot) } }
    val cache by produceState(0L, reload) { value = withContext(Dispatchers.IO) { StorageScan.cacheBytes(context) } }
    var filter by remember { mutableStateOf(StorageFilter.ALL) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirming by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val all = files.orEmpty()
    // When the last file of a group is deleted its chip goes away, so go back to All instead of an empty list.
    if (files != null && filter != StorageFilter.ALL && all.none(filter::matches)) filter = StorageFilter.ALL
    val shown = all.filter(filter::matches)
    val chosen = all.filter { it.path in selected }
    val chosenBytes = chosen.sumOf { it.size }

    fun message(text: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }

    fun deleteChosen() {
        val paths = chosen.map { it.path }.toSet()
        val freed = chosenBytes
        working = true
        // Take the files out of their notes first, so nothing is left pointing at a deleted file.
        onRemoveFromNotes(paths)
        scope.launch {
            withContext(Dispatchers.IO) { paths.forEach { runCatching { File(it).delete() } } }
            working = false
            selected = emptySet()
            reload++
            message("Freed ${formatBytes(freed)}")
        }
    }

    val blurred = LocalBackdrop.current
    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                drawRect(TodoBackground)
                blurred?.let { drawBlurred(it) } ?: drawEdgeGlow(Look.accent.fill, EdgeGlowStrength) // the blurred backdrop already carries it
                drawRect(PageTint)
            }
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(ZenIcon.ChevronLeft, TodoText, 24.dp, modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 2.dp))
                        Text("Storage", color = TodoText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TodoCard).border(GlassStroke, RoundedCornerShape(20.dp)).padding(18.dp)) {
                        Text(if (files == null) "…" else formatBytes(all.sumOf { it.size }), color = TodoText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (files == null) "Looking at your files…" else "${all.size} picture${if (all.size == 1) "" else "s"} and files kept with your notes",
                            color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)
                        )
                        if (cache > 0) Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Temporary files · ${formatBytes(cache)}", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                "Clear", color = TodoBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                    val freed = cache
                                    scope.launch { withContext(Dispatchers.IO) { StorageScan.clearCache(context) }; reload++; message("Freed ${formatBytes(freed)}") }
                                }.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        items(StorageFilter.entries.filter { f -> f == StorageFilter.ALL || all.any(f::matches) }) { f ->
                            val active = f == filter
                            val bytes = all.filter(f::matches).sumOf { it.size }
                            Text(
                                "${f.label} · ${formatBytes(bytes)}", color = if (active) OnAccent else TodoText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (active) TodoBlue else TodoCard).border(GlassStroke, RoundedCornerShape(50))
                                    .clickable { filter = f }.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                if (files != null && shown.isEmpty()) item {
                    Text("Nothing here. Pictures and files you add to notes show up here, biggest first.", color = TodoMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 24.dp))
                }
                if (shown.isNotEmpty()) item {
                    val allChosen = shown.all { it.path in selected }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selected.isEmpty()) "Biggest first" else "${selected.size} selected · ${formatBytes(chosenBytes)}",
                            color = TodoMuted, fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (allChosen) "Clear selection" else "Select all", color = TodoBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                selected = if (allChosen) selected - shown.map { it.path }.toSet() else selected + shown.map { it.path }
                            }.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
                items(shown, key = { it.path }) { file ->
                    StorageRow(file, file.path in selected) { selected = if (file.path in selected) selected - file.path else selected + file.path }
                }
            }
            if (selected.isNotEmpty()) Box(Modifier.fillMaxWidth().background(TodoBackground.copy(alpha = .92f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Button(
                    onClick = { confirming = true }, enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = OnAccent),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Glyph(ZenIcon.Trash, OnAccent, 18.dp, modifier = Modifier.padding(end = 8.dp))
                    Text("Delete ${selected.size} · ${formatBytes(chosenBytes)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (selected.isNotEmpty()) 80.dp else 12.dp))
    }

    if (confirming) {
        val inNotes = chosen.count { it.owner != null }
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = GlassDialog,
            title = { DialogBlur(); Text("Delete ${chosen.size} file${if (chosen.size == 1) "" else "s"}?", color = TodoText) },
            text = {
                Text(
                    (if (inNotes > 0) "They’ll be removed from the notes they’re in. " else "") + "This frees ${formatBytes(chosenBytes)} and can’t be undone.",
                    color = TodoMuted
                )
            },
            confirmButton = { Button(onClick = { confirming = false; deleteChosen() }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel", color = TodoMuted) } }
        )
    }
}

@Composable
private fun StorageRow(file: StoredFile, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (checked) TodoBlue.copy(alpha = .16f) else TodoCard)
            .border(1.dp, if (checked) TodoBlue.copy(alpha = .6f) else TodoBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (checked) TodoBlue else Color.Transparent).border(1.5.dp, if (checked) TodoBlue else TodoMuted, CircleShape),
            contentAlignment = Alignment.Center
        ) { if (checked) Glyph(ZenIcon.Check, OnAccent, 13.dp) }
        Spacer(Modifier.width(12.dp))
        // The preview opens the file; the rest of the row selects it.
        val thumb = rememberThumbnail(file.path, enabled = file.kind == FileKind.IMAGE && !file.isPrivate)
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(TodoCardRaised)
                .then(if (file.isPrivate) Modifier else Modifier.clickable { Attachments.open(context, file.path, if (file.kind == FileKind.IMAGE && file.path.endsWith(".img")) "picture.jpg" else file.name) }),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Glyph(if (file.isPrivate) ZenIcon.Lock else file.kind.icon, TodoMuted, 20.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(file.name, color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                file.owner?.let { "In $it" } ?: "Not in any note",
                color = if (file.owner == null) Amber else TodoMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(formatBytes(file.size), color = TodoText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
    }
}

/** A small preview for a list row, decoded at about the size it is shown. */
@Composable
private fun rememberThumbnail(path: String, enabled: Boolean): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path, enabled) {
        value = if (!enabled) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

package com.anmol.daymark

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.fillMaxHeight

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun rememberSavedBitmap(path: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1000) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/** Two-column masonry of saved images, links and snippets. */
@Composable
internal fun SavedGrid(saves: List<BoardCard>, onOpen: (BoardCard) -> Unit, onDelete: (BoardCard) -> Unit, modifier: Modifier, onTogglePrivate: (BoardCard) -> Unit = {}) {
    var viewing by remember { mutableStateOf<BoardCard?>(null) }
    val context = LocalContext.current
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
        verticalItemSpacing = 10.dp,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(saves, key = { it.id }) { save ->
          Box(Modifier.animateItem()) {
            SaveTile(
                save = save,
                // Every save opens like a note; the link and full image are a long-press away.
                onOpen = { if (save.title.isBlank() && save.details.isBlank() && save.link.isBlank() && save.imagePath.isNotBlank()) viewing = save else onOpen(save) },
                onOpenLink = { openLink(context, save.link) },
                onViewImage = { viewing = save },
                onDelete = { onDelete(save) },
                onTogglePrivate = { onTogglePrivate(save) }
            )
          }
        }
    }
    viewing?.let { save -> SavedImageViewer(save) { viewing = null } }
}

/** Full-screen view of a saved image; tap anywhere to close. */
@Composable
internal fun SavedImageViewer(save: BoardCard, onDismiss: () -> Unit) {
    val bitmap = rememberSavedBitmap(save.imagePath)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SaveTile(save: BoardCard, onOpen: () -> Unit, onOpenLink: () -> Unit, onViewImage: () -> Unit, onDelete: () -> Unit, onTogglePrivate: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val bitmap = rememberSavedBitmap(save.imagePath)
    Box {
        Surface(
            color = TodoCard,
            shape = shape,
            border = GlassStroke,
            modifier = Modifier.glassBlur(shape).fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })
        ) {
            Column {
                bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                if (save.title.isNotBlank() || save.link.isNotBlank() || save.details.isNotBlank() || save.comment.isNotBlank()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (save.title.isNotBlank()) Text(save.title, color = TodoText, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (save.details.isNotBlank()) Text(Markdown.preview(save.details, 220), color = TodoText.copy(alpha = .7f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (save.comment.isNotBlank()) CommentPreview(save.comment, 4)
                        if (save.link.isNotBlank()) androidx.compose.foundation.layout.Row(Modifier.padding(top = 2.dp)) {
                            Glyph(ZenIcon.Link, TodoBlue, 12.dp, modifier = Modifier.padding(top = 3.dp, end = 5.dp))
                            Text(save.link, color = TodoBlue, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
            if (save.link.isNotBlank()) DropdownMenuItem(text = { Text("Open link") }, onClick = { menu = false; onOpenLink() })
            if (save.imagePath.isNotBlank()) DropdownMenuItem(text = { Text("View image") }, onClick = { menu = false; onViewImage() })
            DropdownMenuItem(text = { Text(if (save.isPrivate) "Remove from Private" else "Move to Private") }, onClick = { menu = false; onTogglePrivate() })
            DropdownMenuItem(text = { Text("Delete", color = Danger) }, onClick = { menu = false; onDelete() })
        }
    }
}

internal fun openLink(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A remark shown under saved content: sand accent bar, slightly italic, so it reads as yours. */
@Composable
internal fun CommentPreview(text: String, maxLines: Int, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier.padding(top = 4.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.padding(end = 8.dp).width(2.dp).fillMaxHeight().background(Amber.copy(alpha = .7f)))
        Text(text, color = Amber, fontSize = 13.sp, lineHeight = 18.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

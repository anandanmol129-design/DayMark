package com.anmol.daymark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Crop shapes offered as chips; [ratio] is width over height, null for free. */
internal enum class CropAspect(val label: String, val ratio: Float?) {
    Free("Free", null), Square("Square", 1f), FourThree("4:3", 4f / 3f), Wide("16:9", 16f / 9f)
}

private enum class CropHandle { Move, TopLeft, Top, TopRight, Right, BottomRight, Bottom, BottomLeft, Left }

private val FullCrop = Rect(0f, 0f, 1f, 1f)
private const val PreviewEdge = 2048
private const val OutputEdge = 2048

/**
 * Full-screen crop step for a just-picked image. [onResult] gets a stored image path, either the crop
 * or ("Use full image") a plain copy of the original; [onCancel] drops the pick (Cancel or back).
 */
@Composable
internal fun ImageCropDialog(uri: Uri, defaultAspect: CropAspect, onCancel: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val base by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadPreview(context, uri) }.getOrNull() }
        failed = value == null
    }
    var rotation by remember { mutableStateOf(0) }
    val shown: ImageBitmap? = remember(base, rotation) { base?.let { rotated(it, rotation).asImageBitmap() } }
    var aspect by remember { mutableStateOf(defaultAspect) }
    var flipped by remember { mutableStateOf(false) }
    val ratio = aspect.ratio?.let { if (flipped) 1f / it else it }
    var crop by remember { mutableStateOf(FullCrop) }
    var dragging by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val thirds by animateFloatAsState(if (dragging) 1f else 0f, tween(180), label = "thirds")

    fun imageAspect() = shown?.let { it.width.toFloat() / it.height } ?: 1f
    // A new image or a turn starts from the biggest crop the current shape allows.
    LaunchedEffect(shown) { if (shown != null) crop = ratio?.let { centeredCrop(it, imageAspect()) } ?: FullCrop }

    fun chooseAspect(next: CropAspect) {
        if (next == aspect && next.ratio != null && next != CropAspect.Square) flipped = !flipped
        else { aspect = next; flipped = false }
        val r = next.ratio?.let { if (flipped) 1f / it else it } ?: return
        crop = centeredCrop(r, imageAspect())
    }

    fun finishWith(work: suspend () -> String?) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val path = withContext(Dispatchers.IO) { work() }
            busy = false
            if (path != null) onResult(path) else error = "Couldn’t save this image"
        }
    }

    fun useFull() = finishWith { Saves.copyImage(context, uri) }
    fun done() {
        val untouched = rotation == 0 && crop.left < .002f && crop.top < .002f && crop.right > .998f && crop.bottom > .998f
        if (untouched) useFull() else finishWith { runCatching { cropToFile(context, uri, crop, rotation) }.getOrNull() }
    }

    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(TodoBackground).navigationBarsPadding().padding(top = 28.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Cancel", color = TodoMuted, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.CenterStart).clip(CircleShape).clickable(enabled = !busy, onClick = onCancel).padding(8.dp)
                )
                Text("Crop", color = TodoText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                if (shown != null) Box(
                    Modifier.align(Alignment.CenterEnd).size(38.dp).clip(CircleShape).background(TodoCard).border(GlassStroke, CircleShape)
                        .clickable(enabled = !busy) { rotation = (rotation + 90) % 360 }.semantics { contentDescription = "Rotate" },
                    contentAlignment = Alignment.Center
                ) { Glyph(ZenIcon.Repeat, TodoText, 17.dp) }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val image = shown
                when {
                    image != null -> Canvas(
                        Modifier.fillMaxSize().onSizeChanged { canvasSize = it }
                            .pointerInput(image, ratio) {
                                val inset = 22.dp.toPx()
                                val touch = 28.dp.toPx()
                                val minSide = 56.dp.toPx()
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (busy) return@awaitEachGesture
                                    val bounds = fitRect(Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()), image.width, image.height, inset)
                                    val start = denormalize(crop, bounds)
                                    val handle = hitHandle(down.position, start, touch) ?: return@awaitEachGesture
                                    down.consume()
                                    dragging = true
                                    try {
                                        drag(down.id) { change ->
                                            change.consume()
                                            val moved = resize(start, handle, change.position - down.position, bounds, ratio, minSide)
                                            crop = normalize(moved, bounds)
                                        }
                                    } finally {
                                        dragging = false
                                    }
                                }
                            }
                    ) {
                        val bounds = fitRect(size, image.width, image.height, 22.dp.toPx())
                        drawImage(
                            image,
                            dstOffset = androidx.compose.ui.unit.IntOffset(bounds.left.toInt(), bounds.top.toInt()),
                            dstSize = IntSize(bounds.width.toInt(), bounds.height.toInt())
                        )
                        drawCropFrame(denormalize(crop, bounds), bounds, thirds)
                    }
                    failed -> Text("Can’t show this image. You can still add it as it is.", color = TodoMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                    else -> Text("Opening…", color = TodoMuted, fontSize = 14.sp)
                }
            }

            error?.let { Text(it, color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                CropAspect.entries.forEach { option ->
                    val label = if (option == aspect && flipped && option.ratio != null) option.label.split(':').reversed().joinToString(":") else option.label
                    AspectChip(label, option == aspect, enabled = shown != null && !busy) { chooseAspect(option) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Use full image", color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(TodoCard).border(GlassStroke, CircleShape)
                        .clickable(enabled = !busy, onClick = ::useFull).padding(vertical = 14.dp)
                )
                Text(
                    if (busy) "Saving…" else "Done", color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(if (shown != null) TodoBlue else TodoBlue.copy(alpha = .4f))
                        .clickable(enabled = !busy && shown != null, onClick = ::done).padding(vertical = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun AspectChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) TodoText else TodoMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(if (selected) GlassAccent else TodoCard).border(GlassStroke, CircleShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

private fun DrawScope.drawCropFrame(frame: Rect, bounds: Rect, thirds: Float) {
    val shade = Color.Black.copy(alpha = .58f)
    // Darken the picture around the crop.
    drawRect(shade, Offset(bounds.left, bounds.top), Size(bounds.width, frame.top - bounds.top))
    drawRect(shade, Offset(bounds.left, frame.bottom), Size(bounds.width, bounds.bottom - frame.bottom))
    drawRect(shade, Offset(bounds.left, frame.top), Size(frame.left - bounds.left, frame.height))
    drawRect(shade, Offset(frame.right, frame.top), Size(bounds.right - frame.right, frame.height))

    if (thirds > 0f) {
        val line = Color.White.copy(alpha = .45f * thirds)
        val w = 1.dp.toPx()
        for (i in 1..2) {
            val x = frame.left + frame.width * i / 3f
            val y = frame.top + frame.height * i / 3f
            drawLine(line, Offset(x, frame.top), Offset(x, frame.bottom), w)
            drawLine(line, Offset(frame.left, y), Offset(frame.right, y), w)
        }
    }
    drawRect(Color.White.copy(alpha = .85f), frame.topLeft, frame.size, style = Stroke(1.dp.toPx()))

    val handle = Color.White
    val thick = 3.dp.toPx()
    val arm = min(18.dp.toPx(), min(frame.width, frame.height) / 2.5f)
    val o = thick / 2f
    val corners = listOf(
        Triple(Offset(frame.left - o, frame.top - o), 1f, 1f), Triple(Offset(frame.right + o, frame.top - o), -1f, 1f),
        Triple(Offset(frame.right + o, frame.bottom + o), -1f, -1f), Triple(Offset(frame.left - o, frame.bottom + o), 1f, -1f)
    )
    corners.forEach { (p, sx, sy) ->
        drawLine(handle, p, Offset(p.x + sx * arm, p.y), thick, StrokeCap.Round)
        drawLine(handle, p, Offset(p.x, p.y + sy * arm), thick, StrokeCap.Round)
    }
    val bar = min(12.dp.toPx(), min(frame.width, frame.height) / 5f)
    val cx = frame.center.x
    val cy = frame.center.y
    drawLine(handle, Offset(cx - bar, frame.top - o), Offset(cx + bar, frame.top - o), thick, StrokeCap.Round)
    drawLine(handle, Offset(cx - bar, frame.bottom + o), Offset(cx + bar, frame.bottom + o), thick, StrokeCap.Round)
    drawLine(handle, Offset(frame.left - o, cy - bar), Offset(frame.left - o, cy + bar), thick, StrokeCap.Round)
    drawLine(handle, Offset(frame.right + o, cy - bar), Offset(frame.right + o, cy + bar), thick, StrokeCap.Round)
}

/** Where an image of [w]x[h] sits when fitted inside [area] with [inset] all round. */
private fun fitRect(area: Size, w: Int, h: Int, inset: Float): Rect {
    val availW = (area.width - inset * 2).coerceAtLeast(1f)
    val availH = (area.height - inset * 2).coerceAtLeast(1f)
    val scale = min(availW / w, availH / h)
    val dw = w * scale
    val dh = h * scale
    val left = (area.width - dw) / 2f
    val top = (area.height - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

private fun denormalize(r: Rect, b: Rect) = Rect(b.left + r.left * b.width, b.top + r.top * b.height, b.left + r.right * b.width, b.top + r.bottom * b.height)
private fun normalize(r: Rect, b: Rect) = Rect(
    ((r.left - b.left) / b.width).coerceIn(0f, 1f), ((r.top - b.top) / b.height).coerceIn(0f, 1f),
    ((r.right - b.left) / b.width).coerceIn(0f, 1f), ((r.bottom - b.top) / b.height).coerceIn(0f, 1f)
)

/** The largest crop of shape [ratio] centred on an image whose width over height is [imageAspect], in 0..1 units. */
private fun centeredCrop(ratio: Float, imageAspect: Float): Rect {
    val rel = ratio / imageAspect
    val (w, h) = if (rel >= 1f) 1f to 1f / rel else rel to 1f
    return Rect((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f)
}

private fun hitHandle(p: Offset, f: Rect, touch: Float): CropHandle? {
    fun near(a: Offset) = (p - a).getDistance() <= touch
    when {
        near(f.topLeft) -> return CropHandle.TopLeft
        near(f.topRight) -> return CropHandle.TopRight
        near(f.bottomRight) -> return CropHandle.BottomRight
        near(f.bottomLeft) -> return CropHandle.BottomLeft
    }
    val withinX = p.x in (f.left - touch)..(f.right + touch)
    val withinY = p.y in (f.top - touch)..(f.bottom + touch)
    return when {
        withinX && abs(p.y - f.top) <= touch -> CropHandle.Top
        withinX && abs(p.y - f.bottom) <= touch -> CropHandle.Bottom
        withinY && abs(p.x - f.left) <= touch -> CropHandle.Left
        withinY && abs(p.x - f.right) <= touch -> CropHandle.Right
        f.contains(p) -> CropHandle.Move
        else -> null
    }
}

/** The crop after dragging [handle] by [delta] from [start], kept inside [b], at least [minSide] and in [ratio] if set. */
private fun resize(start: Rect, handle: CropHandle, delta: Offset, b: Rect, ratio: Float?, minSide: Float): Rect {
    if (handle == CropHandle.Move) {
        val dx = delta.x.coerceIn(b.left - start.left, b.right - start.right)
        val dy = delta.y.coerceIn(b.top - start.top, b.bottom - start.bottom)
        return start.translate(dx, dy)
    }
    val minW = min(minSide, b.width)
    val minH = min(minSide, b.height)
    if (ratio == null) {
        var (l, t, r, bt) = listOf(start.left, start.top, start.right, start.bottom)
        if (handle in setOf(CropHandle.Left, CropHandle.TopLeft, CropHandle.BottomLeft)) l = (l + delta.x).coerceIn(b.left, r - minW)
        if (handle in setOf(CropHandle.Right, CropHandle.TopRight, CropHandle.BottomRight)) r = (r + delta.x).coerceIn(l + minW, b.right)
        if (handle in setOf(CropHandle.Top, CropHandle.TopLeft, CropHandle.TopRight)) t = (t + delta.y).coerceIn(b.top, bt - minH)
        if (handle in setOf(CropHandle.Bottom, CropHandle.BottomLeft, CropHandle.BottomRight)) bt = (bt + delta.y).coerceIn(t + minH, b.bottom)
        return Rect(l, t, r, bt)
    }
    val minWidth = max(minW, minH * ratio)
    return when (handle) {
        CropHandle.Left, CropHandle.Right -> {
            val cy = start.center.y
            val room = if (handle == CropHandle.Right) b.right - start.left else start.right - b.left
            val maxW = min(room, 2f * min(cy - b.top, b.bottom - cy) * ratio)
            val w = (start.width + if (handle == CropHandle.Right) delta.x else -delta.x).coerceIn(min(minWidth, maxW), maxW)
            val h = w / ratio
            if (handle == CropHandle.Right) Rect(start.left, cy - h / 2, start.left + w, cy + h / 2) else Rect(start.right - w, cy - h / 2, start.right, cy + h / 2)
        }
        CropHandle.Top, CropHandle.Bottom -> {
            val cx = start.center.x
            val room = if (handle == CropHandle.Bottom) b.bottom - start.top else start.bottom - b.top
            val maxH = min(room, 2f * min(cx - b.left, b.right - cx) / ratio)
            val h = (start.height + if (handle == CropHandle.Bottom) delta.y else -delta.y).coerceIn(min(minWidth / ratio, maxH), maxH)
            val w = h * ratio
            if (handle == CropHandle.Bottom) Rect(cx - w / 2, start.top, cx + w / 2, start.top + h) else Rect(cx - w / 2, start.bottom - h, cx + w / 2, start.bottom)
        }
        else -> {
            // Corners pivot on the opposite corner; whichever way the finger pulls further wins.
            val sx = if (handle == CropHandle.TopRight || handle == CropHandle.BottomRight) 1f else -1f
            val sy = if (handle == CropHandle.BottomLeft || handle == CropHandle.BottomRight) 1f else -1f
            val ax = if (sx > 0) start.left else start.right
            val ay = if (sy > 0) start.top else start.bottom
            val px = (if (sx > 0) start.right else start.left) + delta.x
            val py = (if (sy > 0) start.bottom else start.top) + delta.y
            var w = max(sx * (px - ax), sy * (py - ay) * ratio)
            val maxW = min(if (sx > 0) b.right - ax else ax - b.left, (if (sy > 0) b.bottom - ay else ay - b.top) * ratio)
            w = w.coerceIn(min(minWidth, maxW), maxW)
            val h = w / ratio
            Rect(min(ax, ax + sx * w), min(ay, ay + sy * h), max(ax, ax + sx * w), max(ay, ay + sy * h))
        }
    }
}

// ---- Decoding and saving ----

private fun orientationOf(context: Context, uri: Uri): Int = runCatching {
    context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
}.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

/** Turns a stored picture upright the way its EXIF orientation says. */
private fun orientationMatrix(orientation: Int) = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
    }
}

private fun boundsOf(context: Context, uri: Uri): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

private fun sampleFor(longEdge: Int, limit: Int): Int {
    var sample = 1
    while (longEdge / sample > limit) sample *= 2
    return sample
}

/** A screen-sized, upright copy of the picture for the crop page. */
private fun loadPreview(context: Context, uri: Uri): Bitmap? {
    val (w, h) = boundsOf(context, uri) ?: return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(max(w, h), PreviewEdge) }
    val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val matrix = orientationMatrix(orientationOf(context, uri))
    return if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

private fun rotated(bitmap: Bitmap, degrees: Int): Bitmap =
    if (degrees == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { setRotate(degrees.toFloat()) }, true)

/**
 * Cuts [crop] (0..1 units of the upright, turned picture) out of the original at up to [OutputEdge] px on the
 * long edge, decoding only that region where the format allows, and stores it beside other saved images.
 */
private fun cropToFile(context: Context, uri: Uri, crop: Rect, rotation: Int): String? {
    val (rawW, rawH) = boundsOf(context, uri) ?: return null
    val orientation = orientationOf(context, uri)
    val toShown = orientationMatrix(orientation).apply { postRotate(rotation.toFloat()) }
    val shownBounds = RectF(0f, 0f, rawW.toFloat(), rawH.toFloat()).also { toShown.mapRect(it) }
    toShown.postTranslate(-shownBounds.left, -shownBounds.top)
    val wanted = RectF(crop.left * shownBounds.width(), crop.top * shownBounds.height(), crop.right * shownBounds.width(), crop.bottom * shownBounds.height())
    val toRaw = Matrix().also { toShown.invert(it) }
    toRaw.mapRect(wanted)
    val region = android.graphics.Rect(
        floor(wanted.left).toInt().coerceIn(0, rawW - 1), floor(wanted.top).toInt().coerceIn(0, rawH - 1),
        ceil(wanted.right).toInt().coerceIn(1, rawW), ceil(wanted.bottom).toInt().coerceIn(1, rawH)
    )
    if (region.width() <= 0 || region.height() <= 0) return null

    // Largest power-of-two step that still leaves at least OutputEdge px, then an exact scale below.
    var sample = 1
    while (max(region.width(), region.height()) / (sample * 2) >= OutputEdge) sample *= 2
    val part = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapRegionDecoder.newInstance(input)?.let { decoder ->
                try { decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample }) } finally { decoder.recycle() }
            }
        }
    }.getOrNull() ?: run {
        // Formats the region decoder can't read: decode the whole picture (bounded) and cut from that.
        val wholeSample = max(sample, sampleFor(max(rawW, rawH), 4096))
        val whole = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = wholeSample }) } ?: return null
        val sx = whole.width.toFloat() / rawW
        val sy = whole.height.toFloat() / rawH
        val x = (region.left * sx).toInt().coerceIn(0, whole.width - 1)
        val y = (region.top * sy).toInt().coerceIn(0, whole.height - 1)
        val w = (region.width() * sx).toInt().coerceIn(1, whole.width - x)
        val h = (region.height() * sy).toInt().coerceIn(1, whole.height - y)
        Bitmap.createBitmap(whole, x, y, w, h)
    }
    val upright = orientationMatrix(orientation).apply { postRotate(rotation.toFloat()) }
    val scale = min(1f, OutputEdge.toFloat() / max(part.width, part.height))
    if (scale < 1f) upright.postScale(scale, scale)
    val out = if (upright.isIdentity) part else Bitmap.createBitmap(part, 0, 0, part.width, part.height, upright, true)
    val file = File(Saves.imageDir(context), "${UUID.randomUUID()}.img")
    file.outputStream().use { out.compress(if (out.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (out.hasAlpha()) 100 else 92, it) }
    return file.absolutePath
}

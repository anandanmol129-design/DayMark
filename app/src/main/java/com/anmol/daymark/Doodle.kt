package com.anmol.daymark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class DoodleTool(val icon: ZenIcon, val label: String) {
    PEN(ZenIcon.Pencil, "Pen"), LINE(ZenIcon.ShapeLine, "Line"), ARROW(ZenIcon.ShapeArrow, "Arrow"),
    RECT(ZenIcon.ShapeRect, "Rectangle"), OVAL(ZenIcon.ShapeOval, "Circle"), ERASER(ZenIcon.Eraser, "Eraser");
    val freehand get() = this == PEN || this == ERASER
}

/** One thing drawn: a pen or eraser stroke through [points], or a shape from the first point to the last. */
class DoodleMark(val tool: DoodleTool, val color: Int, val width: Float, val points: List<Offset>) {
    fun mapped(scale: Float, dx: Float, dy: Float) = DoodleMark(tool, color, width * scale, points.map { Offset(it.x * scale + dx, it.y * scale + dy) })
}

private val DoodleColors = listOf(Color(0xFFEDEAE3), Color(0xFFB4CDBF), Color(0xFFD9BC8C), Color(0xFFDE8E80), Color(0xFF9DB7D0))
internal val DoodlePaper = Color(0xFF1C1C1A)
// The pad is always dark paper, so its own controls use fixed light colours whatever the app theme is.
private val PadText = Color(0xFFEDEAE3)
private val PadMuted = Color(0x99EDEAE3)
private const val PEN_DP = 5f
private const val ERASER_DP = 28f

/**
 * Drawing pad with a pen, straight lines, arrows, rectangles, circles and an eraser. "Done" saves a PNG and hands back
 * its path. Given [editPath] it opens that picture again: a doodle comes back with every mark still editable, and any
 * other picture becomes the paper to draw on.
 */
@Composable
fun DoodleDialog(editPath: String? = null, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val marks = remember { mutableStateListOf<DoodleMark>() }
    var current by remember { mutableStateOf<DoodleMark?>(null) }
    var tool by remember { mutableStateOf(DoodleTool.PEN) }
    var color by remember { mutableStateOf(DoodleColors[0]) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // What an opened picture brings with it: the paper under the ink (not erasable), and older ink as pixels (erasable).
    var under by remember { mutableStateOf<Bitmap?>(null) }
    var inkBase by remember { mutableStateOf<Bitmap?>(null) }
    var layout by remember { mutableStateOf<DoodleLayout?>(null) }
    var changed by remember { mutableStateOf(false) }
    val loaded by produceState<DoodleFile.Loaded?>(null, editPath) {
        value = editPath?.let { path -> withContext(Dispatchers.IO) { DoodleFile.load(path) } }
    }
    // Once the pad has a size, fit what was opened into it.
    LaunchedEffect(loaded, size) {
        val opened = loaded ?: return@LaunchedEffect
        if (size == IntSize.Zero || layout != null) return@LaunchedEffect
        val fit = DoodleLayout.fit(opened.width, opened.height, size)
        layout = fit
        under = opened.under
        inkBase = opened.inkBase
        marks.clear(); marks.addAll(opened.marks.map { it.mapped(fit.scale, fit.dx, fit.dy) })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(DoodlePaper).navigationBarsPadding().padding(top = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", color = PadMuted, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
                Spacer(Modifier.weight(1f))
                Text("Undo", color = if (marks.isNotEmpty()) PadText else PadMuted.copy(alpha = .4f), fontSize = 16.sp, modifier = Modifier.clickable(enabled = marks.isNotEmpty()) { marks.removeAt(marks.lastIndex) }.padding(8.dp))
                Text("Clear", color = PadText, fontSize = 16.sp, modifier = Modifier.clickable { marks.clear(); if (inkBase != null) { inkBase = null; changed = true } }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                val canSave = marks.isNotEmpty() || inkBase != null || under != null || changed
                Text(
                    "Done", color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (canSave) TodoBlue else TodoBlue.copy(alpha = .4f))
                        .clickable(enabled = canSave) { DoodleFile.save(context, marks.toList(), size, under, inkBase, layout)?.let(onSaved) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { size = it }) {
                // The paper an opened picture sits on.
                Canvas(Modifier.fillMaxSize()) {
                    val fit = layout
                    under?.let { bmp -> fit?.let { drawIntoCanvas { c -> c.nativeCanvas.drawBitmap(bmp, null, it.rect, Paint(Paint.FILTER_BITMAP_FLAG)) } } }
                }
                // The ink, on its own layer so the eraser clears ink and leaves the paper alone.
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .pointerInput(tool, color) {
                            val width = (if (tool == DoodleTool.ERASER) ERASER_DP else PEN_DP) * density
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                val points = ArrayList<Offset>().apply { add(down.position) }
                                fun mark() = DoodleMark(tool, color.toArgb(), width, if (tool.freehand) points.toList() else listOf(points.first(), points.last()))
                                current = mark()
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    if (tool.freehand) points.add(change.position) else { if (points.size > 1) points.removeAt(1); points.add(change.position) }
                                    current = mark()
                                }
                                val done = mark()
                                current = null
                                // A tap makes a dot with the pen; a shape needs a little size to be worth keeping.
                                if (tool.freehand || hypot(done.points.last().x - done.points.first().x, done.points.last().y - done.points.first().y) > 6 * density) marks += done
                            }
                        }
                ) {
                    drawIntoCanvas { c ->
                        val canvas = c.nativeCanvas
                        val fit = layout
                        inkBase?.let { bmp -> if (fit != null) canvas.drawBitmap(bmp, null, fit.rect, Paint(Paint.FILTER_BITMAP_FLAG)) }
                        marks.forEach { DoodleFile.paint(canvas, it) }
                        current?.let { DoodleFile.paint(canvas, it) }
                    }
                    // Show where the eraser is while it is down.
                    current?.takeIf { it.tool == DoodleTool.ERASER }?.points?.lastOrNull()?.let {
                        drawCircle(Color.White.copy(alpha = .7f), ERASER_DP * density / 2f, it, style = Stroke(1.5f * density))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                DoodleTool.entries.forEach { option ->
                    val active = option == tool
                    Box(
                        Modifier.padding(horizontal = 5.dp).size(46.dp).clip(CircleShape)
                            .background(if (active) TodoBlue else Color.White.copy(alpha = .07f))
                            .clickable { tool = option }.semantics { contentDescription = option.label },
                        contentAlignment = Alignment.Center
                    ) { Glyph(option.icon, if (active) OnAccent else PadText, 21.dp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                DoodleColors.forEach { swatch ->
                    Box(
                        Modifier.padding(horizontal = 9.dp).size(34.dp).clip(CircleShape).background(swatch)
                            .then(if (swatch == color && tool != DoodleTool.ERASER) Modifier.border(3.dp, Color.White.copy(alpha = .9f), CircleShape) else Modifier)
                            // Picking a colour while erasing goes back to the pen.
                            .clickable { color = swatch; if (tool == DoodleTool.ERASER) tool = DoodleTool.PEN }
                    )
                }
            }
        }
    }
}

/** Where a picture opened for editing sits on the pad: scaled to fit and centred. */
class DoodleLayout(val scale: Float, val dx: Float, val dy: Float, val rect: RectF) {
    companion object {
        fun fit(width: Int, height: Int, pad: IntSize): DoodleLayout {
            val scale = min(pad.width / width.toFloat(), pad.height / height.toFloat())
            val dx = (pad.width - width * scale) / 2f
            val dy = (pad.height - height * scale) / 2f
            return DoodleLayout(scale, dx, dy, RectF(dx, dy, dx + width * scale, dy + height * scale))
        }
    }
}

/**
 * Doodles are ordinary PNGs (so they show, back up and share like any picture) with the marks tucked into
 * private chunks that image viewers skip. That is what lets a doodle be opened and edited again later.
 */
object DoodleFile {
    private const val MARKS = "dmDk"   // the marks, as deflated JSON
    private const val UNDER = "dmBg"   // the picture drawn on, if any
    private const val INK = "dmIn"     // older ink kept as pixels (a doodle from before marks were saved)

    class Loaded(val width: Int, val height: Int, val marks: List<DoodleMark>, val under: Bitmap?, val inkBase: Bitmap?)

    fun paint(canvas: AndroidCanvas, mark: DoodleMark) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mark.color; strokeWidth = mark.width; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            if (mark.tool == DoodleTool.ERASER) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val pts = mark.points
        if (pts.isEmpty()) return
        val a = pts.first()
        val b = pts.last()
        when (mark.tool) {
            DoodleTool.PEN, DoodleTool.ERASER -> if (pts.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(a.x, a.y, mark.width / 2f, paint)
            } else {
                // Curves through the midpoints, so fast strokes come out smooth rather than as straight segments.
                val path = AndroidPath().apply {
                    moveTo(a.x, a.y)
                    for (i in 1 until pts.size - 1) {
                        val p = pts[i]; val n = pts[i + 1]
                        quadTo(p.x, p.y, (p.x + n.x) / 2f, (p.y + n.y) / 2f)
                    }
                    lineTo(b.x, b.y)
                }
                canvas.drawPath(path, paint)
            }
            DoodleTool.LINE -> canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            DoodleTool.ARROW -> {
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                val angle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                val head = min(mark.width * 4.5f, hypot(b.x - a.x, b.y - a.y) * .45f)
                val spread = Math.toRadians(28.0)
                val path = AndroidPath().apply {
                    moveTo((b.x - head * cos(angle - spread)).toFloat(), (b.y - head * sin(angle - spread)).toFloat())
                    lineTo(b.x, b.y)
                    lineTo((b.x - head * cos(angle + spread)).toFloat(), (b.y - head * sin(angle + spread)).toFloat())
                }
                canvas.drawPath(path, paint)
            }
            DoodleTool.RECT -> canvas.drawRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), paint)
            DoodleTool.OVAL -> canvas.drawOval(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), paint)
        }
    }

    fun save(context: Context, marks: List<DoodleMark>, size: IntSize, under: Bitmap?, inkBase: Bitmap?, layout: DoodleLayout?): String? = runCatching {
        val w = size.width.coerceAtLeast(1)
        val h = size.height.coerceAtLeast(1)
        val ink = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        AndroidCanvas(ink).let { canvas ->
            if (inkBase != null && layout != null) canvas.drawBitmap(inkBase, null, layout.rect, Paint(Paint.FILTER_BITMAP_FLAG))
            marks.forEach { paint(canvas, it) }
        }
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        AndroidCanvas(picture).apply {
            drawColor(DoodlePaper.toArgb())
            if (under != null && layout != null) drawBitmap(under, null, layout.rect, Paint(Paint.FILTER_BITMAP_FLAG))
            drawBitmap(ink, 0f, 0f, null)
        }
        val json = JSONObject().put("v", 1).put("w", w).put("h", h)
        layout?.rect?.let { json.put("rect", JSONArray(listOf(it.left, it.top, it.right, it.bottom).map(Float::toDouble))) }
        json.put("marks", JSONArray().apply {
            marks.forEach { m ->
                put(JSONObject().put("t", m.tool.name).put("c", m.color).put("w", m.width.toDouble())
                    .put("p", JSONArray().apply { m.points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }))
            }
        })
        val extra = buildList {
            add(MARKS to deflate(json.toString().toByteArray()))
            under?.let { add(UNDER to encode(it, Bitmap.CompressFormat.JPEG)) }
            inkBase?.let { add(INK to encode(it, Bitmap.CompressFormat.PNG)) }
        }
        val png = withChunks(encode(picture, Bitmap.CompressFormat.PNG), extra)
        val file = File(Saves.imageDir(context), "${UUID.randomUUID()}.img")
        file.writeBytes(png)
        file.absolutePath
    }.getOrNull()

    fun load(path: String): Loaded? = runCatching {
        val bytes = File(path).readBytes()
        val chunks = chunks(bytes)
        val data = chunks[MARKS]?.let { JSONObject(String(inflate(it))) }
        if (data != null) {
            val w = data.getInt("w")
            val h = data.getInt("h")
            val marks = data.optJSONArray("marks")?.let { list ->
                (0 until list.length()).map { i ->
                    val m = list.getJSONObject(i)
                    val p = m.getJSONArray("p")
                    DoodleMark(DoodleTool.valueOf(m.getString("t")), m.getInt("c"), m.getDouble("w").toFloat(), (0 until p.length() / 2).map { Offset(p.getDouble(it * 2).toFloat(), p.getDouble(it * 2 + 1).toFloat()) })
                }
            }.orEmpty()
            // Pictures under or beneath the ink were placed inside "rect"; put them back there by padding them out to the page.
            val rect = data.optJSONArray("rect")?.let { RectF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) }
            fun page(raw: ByteArray?): Bitmap? {
                val bmp = raw?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
                if (rect == null) return bmp
                return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { AndroidCanvas(it).drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG)) }
            }
            Loaded(w, h, marks, page(chunks[UNDER]), page(chunks[INK]))
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }) ?: return null
            // A doodle saved before marks were kept: lift its ink off the paper so the eraser can still remove it.
            if (isOldDoodle(bmp)) Loaded(bmp.width, bmp.height, emptyList(), null, keyOutPaper(bmp))
            else Loaded(bmp.width, bmp.height, emptyList(), bmp, null)
        }
    }.getOrNull()

    private fun isOldDoodle(bmp: Bitmap): Boolean {
        val paper = DoodlePaper.toArgb()
        val corners = listOf(bmp.getPixel(0, 0), bmp.getPixel(bmp.width - 1, 0), bmp.getPixel(0, bmp.height - 1), bmp.getPixel(bmp.width - 1, bmp.height - 1))
        return corners.all { close(it, paper, 3) }
    }

    private fun close(a: Int, b: Int, tolerance: Int) =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tolerance &&
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tolerance &&
            kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= tolerance

    private fun keyOutPaper(bmp: Bitmap): Bitmap {
        val paper = DoodlePaper.toArgb()
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in pixels.indices) if (close(pixels[i], paper, 10)) pixels[i] = 0
        return Bitmap.createBitmap(pixels, bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, if (format == Bitmap.CompressFormat.JPEG) 92 else 100, it) }.toByteArray()

    /** Adds chunks just before a PNG's closing IEND chunk. */
    private fun withChunks(png: ByteArray, extra: List<Pair<String, ByteArray>>): ByteArray {
        val end = png.size - 12
        val out = ByteArrayOutputStream()
        out.write(png, 0, end)
        val data = DataOutputStream(out)
        extra.forEach { (type, body) ->
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(body.size); data.write(name); data.write(body)
            data.writeInt(CRC32().apply { update(name); update(body) }.value.toInt())
        }
        out.write(png, end, 12)
        return out.toByteArray()
    }

    private fun chunks(png: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        if (png.size < 8 || png[1] != 'P'.code.toByte()) return out
        val buffer = ByteBuffer.wrap(png)
        var at = 8
        while (at + 12 <= png.size) {
            val length = buffer.getInt(at)
            if (length < 0 || at + 12 + length > png.size) break
            val type = String(png, at + 4, 4, Charsets.US_ASCII)
            if (type.startsWith("dm")) out[type] = png.copyOfRange(at + 8, at + 8 + length)
            at += 12 + length
        }
        return out
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(bytes); finish() }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater().apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }
}

package com.anmol.daymark

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A calendar on the phone, as the calendar provider lists it. */
internal data class DeviceCalendar(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val name: String,
    val color: Int,
    val accessLevel: Int,
    val visible: Boolean
) {
    val writable get() = accessLevel >= Calendars.CAL_ACCESS_CONTRIBUTOR
    val isGoogle get() = accountType == "com.google"
}

/** Card changes worked out by a sync, applied by the app to whatever it currently shows (see [CalendarSync.drainPatches]). */
internal class CalendarPatch(
    private val updates: Map<String, BoardCard> = emptyMap(),
    private val removals: Set<String> = emptySet(),
    private val additions: List<BoardCard> = emptyList(),
    /** Card id → phone event id for every card Daymark has copied to the sync calendar; other cards get null. */
    private val mirror: Map<String, Long>? = null
) {
    fun apply(snapshot: BoardSnapshot): BoardSnapshot {
        var cards = snapshot.cards
        if (removals.isNotEmpty()) cards = cards.filter { it.id !in removals }
        if (updates.isNotEmpty()) cards = cards.map { card ->
            updates[card.id]?.let { n ->
                card.copy(title = n.title, details = n.details, dueDay = n.dueDay, dueMinute = n.dueMinute, durationMinutes = n.durationMinutes,
                    calendarColor = n.calendarColor, calendarSourceId = n.calendarSourceId)
            } ?: card
        }
        if (additions.isNotEmpty()) {
            val ids = cards.map { it.id }.toSet()
            val sources = cards.mapNotNull { it.calendarSourceId.ifBlank { null } }.toSet()
            cards = cards + additions.filter { it.id !in ids && it.calendarSourceId !in sources }
        }
        if (mirror != null) cards = cards.map { card -> mirror[card.id].let { if (card.providerEventId != it) card.copy(providerEventId = it) else card } }
        return if (cards == snapshot.cards) snapshot else snapshot.copy(cards = cards)
    }
}

/**
 * Two-way sync with the phone's calendar provider. Google (and any other account) calendars are synced to the
 * cloud by the system's own sync adapter, so no Google API is needed.
 *
 * Pull (on open/resume and "Sync now"): events from the calendars ticked in Settings › Calendar become EVENT cards;
 * changes made on the phone update them, and events deleted there (or calendars unticked) remove them. Events Daymark
 * pushed itself are never imported.
 *
 * Push (after every save, debounced): Daymark's own events, and optionally timed to-dos, are copied to the one
 * "Sync Daymark events to" calendar; edits update the copy and deletes delete it. Edits to imported events are written
 * back when the event is single (not recurring) and its calendar is writable. Deleting an imported event in Daymark
 * only hides it here; it is never deleted on the phone or in Google.
 *
 * Bookkeeping lives in its own preferences: per card, the phone event it is linked to plus a fingerprint of the
 * content last seen on each side, so a change is attributed to the side that made it and nothing ping-pongs.
 * All provider I/O and link bookkeeping run on one background thread.
 */
internal object CalendarSync {
    const val OFF = -1L
    private const val PREFS = "daymark_calendar_sync"
    private const val PUSH_DELAY_MS = 1_200L
    private const val AUTO_PULL_GAP_MS = 20_000L

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pushQueued = AtomicBoolean(false)
    /** Set while a pull's card changes wait to be applied by the app; pushing before that would see stale cards. */
    @Volatile private var awaitingPatchSince = 0L
    @Volatile private var lastPull = 0L

    private val patchQueue = ConcurrentLinkedQueue<CalendarPatch>()
    private val _patchTick = MutableStateFlow(0)
    val patchTick: StateFlow<Int> = _patchTick

    // ---- Settings -------------------------------------------------------------------------------------------------

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun canRead(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun canWrite(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** True once the user has chosen calendars on the Calendar page. */
    fun configured(context: Context) = prefs(context).getBoolean("configured", false)

    /** Before anyone has opened the Calendar page, people who imported before keep importing from every calendar. */
    private fun legacy(snapshot: BoardSnapshot) = snapshot.cards.any { it.calendarSourceId.isNotBlank() }

    /** The ticked calendars, or null meaning "all of them" (an old import, not yet configured). */
    fun checkedIds(context: Context, snapshot: BoardSnapshot): Set<Long>? =
        if (configured(context)) prefs(context).getStringSet("checked", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        else if (legacy(snapshot)) null else emptySet()

    fun setChecked(context: Context, ids: Set<Long>) {
        prefs(context).edit().putBoolean("configured", true).putStringSet("checked", ids.map { it.toString() }.toSet()).apply()
    }

    /** Whether an import has anything to read. */
    fun hasChoice(context: Context, snapshot: BoardSnapshot) = checkedIds(context, snapshot).let { it == null || it.isNotEmpty() }

    fun target(context: Context) = prefs(context).getLong("target", OFF)
    fun setTarget(context: Context, id: Long) { prefs(context).edit().putLong("target", id).apply(); schedulePush(context, 0) }

    fun includeTodos(context: Context) = prefs(context).getBoolean("todos", false)
    fun setIncludeTodos(context: Context, on: Boolean) { prefs(context).edit().putBoolean("todos", on).apply(); schedulePush(context, 0) }

    // ---- Calendars --------------------------------------------------------------------------------------------------

    /** The phone's calendars, or null when they can't be read (no permission, provider error). Call off the main thread. */
    fun calendars(context: Context): List<DeviceCalendar>? {
        if (!canRead(context)) return null
        return runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE),
                null, null, null
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) add(DeviceCalendar(
                        id = c.getLong(0),
                        accountName = c.getString(1).orEmpty(),
                        accountType = c.getString(2).orEmpty(),
                        name = c.getString(3).orEmpty().ifBlank { c.getString(1).orEmpty() },
                        color = if (c.isNull(4)) 0 else c.getInt(4),
                        accessLevel = if (c.isNull(5)) 0 else c.getInt(5),
                        visible = !c.isNull(6) && c.getInt(6) == 1
                    ))
                }
            }
        }.getOrNull()
    }

    // ---- Triggers ---------------------------------------------------------------------------------------------------

    /** Queues a push of local changes (called after every save). Cheap; the work happens later on the sync thread. */
    fun schedulePush(context: Context, delayMs: Long = PUSH_DELAY_MS) {
        val app = context.applicationContext
        if (!pushQueued.compareAndSet(false, true)) return
        executor.schedule({
            pushQueued.set(false)
            runCatching { push(app) }.onFailure { cachedLinks = null }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Pulls from the ticked calendars, then lets the queued push run. [manual] reports the outcome through [onMessage]
     * (on the main thread); automatic pulls are silent and skipped if one ran in the last few seconds unless [force].
     */
    fun syncNow(context: Context, manual: Boolean, force: Boolean = manual, onMessage: (String) -> Unit = {}) {
        val app = context.applicationContext
        if (!canRead(app)) { if (manual) onMessage("Allow calendar access to import events"); return }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPull < AUTO_PULL_GAP_MS) return
        lastPull = now
        executor.execute {
            val message = runCatching { pull(app) }.getOrElse { cachedLinks = null; "Couldn’t read calendar events" }
            if (manual) main.post { onMessage(message) }
            schedulePush(app, 0)
        }
    }

    /** Hands the app each pending change set; call on the main thread whenever [patchTick] moves. */
    fun drainPatches(context: Context, apply: (CalendarPatch) -> Unit) {
        val app = context.applicationContext
        var any = false
        while (true) { val patch = patchQueue.poll() ?: break; apply(patch); any = true }
        if (any || awaitingPatchSince != 0L) {
            // Runs on the store's writer after the save the patch caused, so the next push reads the patched cards.
            BoardStore.enqueue { awaitingPatchSince = 0L; schedulePush(app, 300) }
        }
    }

    private fun post(patch: CalendarPatch, blocksPush: Boolean) {
        if (blocksPush) awaitingPatchSince = SystemClock.elapsedRealtime()
        patchQueue.add(patch)
        _patchTick.update { it + 1 }
    }

    // ---- Links ------------------------------------------------------------------------------------------------------

    /** A card tied to a phone event. [pushed]: Daymark made the event; otherwise it was imported. eventId 0 = copy deleted on the phone. */
    private data class Link(
        val eventId: Long,
        val calendarId: Long,
        val pushed: Boolean,
        val recurring: Boolean = false,
        val source: String = "",
        val providerKey: String = "",
        val cardKey: String = "",
        /** The card has been seen in a saved snapshot (a freshly imported card may not have reached the app yet). */
        val seen: Boolean = true
    )

    private var cachedLinks: MutableMap<String, Link>? = null

    private fun loadLinks(context: Context): MutableMap<String, Link> = cachedLinks ?: runCatching {
        val root = JSONObject(prefs(context).getString("links", "{}") ?: "{}")
        root.keys().asSequence().associateWith { id ->
            val o = root.getJSONObject(id)
            Link(o.optLong("e"), o.optLong("c"), o.optBoolean("p"), o.optBoolean("r"), o.optString("s"), o.optString("pk"), o.optString("ck"), o.optBoolean("seen", true))
        }.toMutableMap()
    }.getOrElse { mutableMapOf() }.also { cachedLinks = it }

    private fun saveLinks(context: Context, links: Map<String, Link>) {
        val root = JSONObject()
        links.forEach { (id, l) ->
            root.put(id, JSONObject().apply {
                put("e", l.eventId); put("c", l.calendarId); put("p", l.pushed); put("r", l.recurring); put("s", l.source)
                put("pk", l.providerKey); put("ck", l.cardKey); put("seen", l.seen)
            })
        }
        prefs(context).edit().putString("links", root.toString()).apply()
    }

    private fun hidden(context: Context) = prefs(context).getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
    private fun saveHidden(context: Context, set: Set<String>) { prefs(context).edit().putStringSet("hidden", set.toList().takeLast(2_000).toSet()).apply() }
    private fun hideKey(recurring: Boolean, eventId: Long, source: String) = if (recurring) "s:$source" else "e:$eventId"

    // ---- Content fingerprints and event fields ----------------------------------------------------------------------

    private fun key(title: String, details: String, day: Long?, minute: Int?, duration: Int): String {
        val raw = "${title.trim()}\u0001${details.trim()}\u0001$day\u0001$minute\u0001$duration"
        return "${raw.length}:${raw.hashCode()}"
    }

    private fun cardKey(card: BoardCard) = key(card.title, card.details, card.dueDay, card.dueMinute, card.durationMinutes)

    private data class Slot(val day: Long, val minute: Int?, val duration: Int)

    /** All-day events are stored at UTC midnight; timed ones in local time. */
    private fun slot(begin: Long, end: Long?, allDay: Boolean): Slot {
        if (allDay) return Slot(Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(), null, 0)
        val start = Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault())
        val duration = if (end == null) 0 else ((end - begin) / 60_000).toInt().coerceIn(0, 24 * 60)
        return Slot(start.toLocalDate().toEpochDay(), start.hour * 60 + start.minute, duration)
    }

    /** Images in Daymark's details are local files; leave them out of the phone's copy. */
    private fun plainDetails(details: String) = details.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "").trim()

    /** Event columns for a card's title and time, plus the fingerprint of exactly what is written. */
    private fun eventValues(title: String, details: String, day: Long, minute: Int?, duration: Int, defaultDuration: Int): Pair<ContentValues, String> {
        val values = ContentValues()
        val description = plainDetails(details)
        values.put(Events.TITLE, title.trim())
        values.put(Events.DESCRIPTION, description)
        val written: Int
        if (minute == null) {
            val start = LocalDate.ofEpochDay(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            values.put(Events.ALL_DAY, 1)
            values.put(Events.DTSTART, start)
            values.put(Events.DTEND, start + 24 * 60 * 60_000L)
            values.put(Events.EVENT_TIMEZONE, "UTC")
            written = 0
        } else {
            val zone = ZoneId.systemDefault()
            val start = LocalDate.ofEpochDay(day).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
            written = if (duration > 0) duration else defaultDuration
            values.put(Events.ALL_DAY, 0)
            values.put(Events.DTSTART, start)
            values.put(Events.DTEND, start + written * 60_000L)
            values.put(Events.EVENT_TIMEZONE, zone.id)
        }
        return values to key(title, description, day, minute, written)
    }

    private fun eligible(card: BoardCard, todos: Boolean) = !card.isPrivate && card.calendarSourceId.isBlank() && card.dueDay != null && (
        (card.workspace == Workspace.CALENDAR && card.kind == CardKind.EVENT) ||
            (todos && card.workspace == Workspace.TODOS && card.kind == CardKind.TASK && card.dueMinute != null))

    private fun defaultDuration(card: BoardCard) = if (card.kind == CardKind.EVENT) 60 else 30
    private fun cardUri(card: BoardCard) = "daymark://card/${card.id}"

    private data class EventRow(
        val id: Long, val calendarId: Long, val title: String, val description: String, val start: Long, val end: Long?, val allDay: Boolean,
        val deleted: Boolean, val recurring: Boolean, val editableByUs: Boolean, val customUri: String
    )

    /** Reads events by id, in chunks. Missing ids are simply absent from the result. */
    private fun events(context: Context, ids: Collection<Long>): Map<Long, EventRow> {
        if (ids.isEmpty()) return emptyMap()
        val projection = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.DELETED,
            Events.RRULE, Events.RDATE, Events.ORIGINAL_ID, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY, Events.CUSTOM_APP_URI)
        val result = mutableMapOf<Long, EventRow>()
        ids.distinct().chunked(400).forEach { chunk ->
            context.contentResolver.query(Events.CONTENT_URI, projection, "${Events._ID} IN (${chunk.joinToString(",")})", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val organizer = if (c.isNull(11)) true else c.getString(11).let { it == "1" || it.equals("true", true) }
                    val row = EventRow(
                        id = c.getLong(0), calendarId = c.getLong(1), title = c.getString(2).orEmpty(), description = c.getString(3).orEmpty(),
                        start = c.getLong(4), end = if (c.isNull(5)) null else c.getLong(5), allDay = c.getInt(6) == 1, deleted = c.getInt(7) == 1,
                        recurring = !c.getString(8).isNullOrBlank() || !c.getString(9).isNullOrBlank() || !c.isNull(10),
                        editableByUs = organizer || (!c.isNull(12) && c.getInt(12) == 1),
                        customUri = c.getString(13).orEmpty()
                    )
                    result[row.id] = row
                }
            }
        }
        return result
    }

    private fun insertEvent(context: Context, calendarId: Long, card: BoardCard): Pair<Long, String>? {
        val (values, written) = eventValues(card.title, card.details, card.dueDay ?: return null, card.dueMinute, card.durationMinutes, defaultDuration(card))
        values.put(Events.CALENDAR_ID, calendarId)
        values.put(Events.CUSTOM_APP_PACKAGE, context.packageName)
        values.put(Events.CUSTOM_APP_URI, cardUri(card))
        // No reminders are added: Daymark rings its own, and a second alert from the calendar would double up.
        val uri = runCatching { context.contentResolver.insert(Events.CONTENT_URI, values) }.getOrNull() ?: return null
        return ContentUris.parseId(uri) to written
    }

    /** Updates an event's title, details and time; returns the fingerprint written, or null if nothing was updated. */
    private fun updateEvent(context: Context, eventId: Long, calendarId: Long, title: String, details: String, day: Long, minute: Int?, duration: Int, defaultDuration: Int): String? {
        val (values, written) = eventValues(title, details, day, minute, duration, defaultDuration)
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val where = "${Events.CALENDAR_ID} = ?"
        val args = arrayOf(calendarId.toString())
        val rows = runCatching { context.contentResolver.update(uri, values, where, args) }.getOrElse {
            // An event that carries a DURATION can't also take a DTEND; clear it and try once more.
            values.putNull(Events.DURATION)
            runCatching { context.contentResolver.update(uri, values, where, args) }.getOrDefault(0)
        }
        return if (rows > 0) written else null
    }

    private fun deleteEvent(context: Context, eventId: Long, calendarId: Long) {
        if (eventId <= 0) return
        runCatching { context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), "${Events.CALENDAR_ID} = ?", arrayOf(calendarId.toString())) }
    }

    // ---- Push -------------------------------------------------------------------------------------------------------

    private fun push(context: Context) {
        if (!canRead(context)) return
        // Without write access, imported cards are still tracked (and hidden when deleted), but nothing is written.
        val write = canWrite(context)
        val target = target(context)
        val links = loadLinks(context)
        if (target == OFF && links.isEmpty()) return
        val since = awaitingPatchSince
        if (since != 0L) {
            if (SystemClock.elapsedRealtime() - since < 15_000) { schedulePush(context); return }
            awaitingPatchSince = 0L
        }
        val calendars = calendars(context)?.associateBy { it.id } ?: return
        val snapshot = BoardStore(context).load()
        val cards = snapshot.cards.associateBy { it.id }
        val todos = includeTodos(context)
        val targetCalendar = calendars[target]?.takeIf { it.writable }
        // A sync calendar that has gone away (account removed, sync off): leave Daymark's copies alone until it returns or is changed.
        val pushPaused = target != OFF && targetCalendar == null
        val hidden = hidden(context)
        var linksChanged = false
        var hiddenChanged = false
        // Imported events whose card was edited here: read them to see whether the edit can be written back.
        val importedEvents = if (!write) emptyMap() else events(context, links.filter { (id, l) -> !l.pushed && l.eventId > 0 && cards[id]?.let { cardKey(it) != l.cardKey } == true }.values.map { it.eventId })

        for ((cardId, link) in links.toList()) {
            val card = cards[cardId]
            if (link.pushed) {
                if (pushPaused || !write) continue
                val keep = card != null && targetCalendar != null && link.calendarId == target && eligible(card, todos)
                if (!keep) {
                    // Deleted in Daymark, no longer synced (sync off, other calendar, to-dos excluded): remove the copy.
                    deleteEvent(context, link.eventId, link.calendarId)
                    links.remove(cardId); linksChanged = true
                    continue
                }
                val ck = cardKey(card!!)
                if (ck == link.cardKey) continue
                val written = if (link.eventId > 0) updateEvent(context, link.eventId, target, card.title, card.details, card.dueDay!!, card.dueMinute, card.durationMinutes, defaultDuration(card)) else null
                if (written != null) links[cardId] = link.copy(cardKey = ck, providerKey = written)
                else insertEvent(context, target, card)?.let { (id, key) -> links[cardId] = link.copy(eventId = id, cardKey = ck, providerKey = key) }
                linksChanged = true
            } else {
                if (card == null) {
                    if (link.seen) {
                        // An imported event deleted in Daymark: hide it here only, so the next import doesn't bring it back.
                        hidden += hideKey(link.recurring, link.eventId, link.source); hiddenChanged = true
                        links.remove(cardId); linksChanged = true
                    }
                    continue
                }
                var next = if (link.seen) link else link.copy(seen = true)
                val ck = cardKey(card)
                if (ck != link.cardKey) {
                    val event = importedEvents[link.eventId]
                    val calendar = event?.let { calendars[it.calendarId] }
                    val writable = write && event != null && !event.deleted && !event.recurring && event.editableByUs && calendar?.writable == true && card.dueDay != null
                    val written = if (writable) updateEvent(context, link.eventId, event!!.calendarId, card.title, card.details, card.dueDay!!, card.dueMinute, card.durationMinutes, 0) else null
                    // Recurring or read-only: the edit stays in Daymark only.
                    next = next.copy(cardKey = ck, providerKey = written ?: next.providerKey)
                }
                if (next != link) { links[cardId] = next; linksChanged = true }
                if (hidden.remove(hideKey(link.recurring, link.eventId, link.source))) hiddenChanged = true
            }
        }

        // New copies: Daymark's events and timed to-dos that aren't linked yet (finished or long-past ones are left out).
        if (targetCalendar != null && write) {
            val today = LocalDate.now().toEpochDay()
            val fresh = snapshot.cards.filter { it.id !in links && eligible(it, todos) && !it.completed && (it.dueDay ?: 0) >= today - 30 }
            // A card restored from a backup may still point at its event: take it back instead of making a second copy.
            val claimed = events(context, fresh.mapNotNull { it.providerEventId })
            for (card in fresh) {
                val existing = card.providerEventId?.let { claimed[it] }?.takeIf { !it.deleted && it.calendarId == target && it.customUri == cardUri(card) }
                if (existing != null) {
                    links[card.id] = Link(existing.id, target, pushed = true, providerKey = "", cardKey = ""); linksChanged = true
                    updateEvent(context, existing.id, target, card.title, card.details, card.dueDay!!, card.dueMinute, card.durationMinutes, defaultDuration(card))?.let {
                        links[card.id] = Link(existing.id, target, pushed = true, providerKey = it, cardKey = cardKey(card))
                    }
                    continue
                }
                insertEvent(context, target, card)?.let { (id, key) ->
                    links[card.id] = Link(id, target, pushed = true, providerKey = key, cardKey = cardKey(card)); linksChanged = true
                }
            }
        }

        if (linksChanged) saveLinks(context, links)
        if (hiddenChanged) saveHidden(context, hidden)
        val mirror = links.filter { it.value.pushed && it.value.eventId > 0 }.mapValues { it.value.eventId }
        if (snapshot.cards.any { it.providerEventId != mirror[it.id] }) post(CalendarPatch(mirror = mirror), blocksPush = false)
    }

    // ---- Pull -------------------------------------------------------------------------------------------------------

    private fun pull(context: Context): String {
        val calendars = calendars(context) ?: return "Couldn’t read your calendars"
        val calendarIds = calendars.map { it.id }.toSet()
        val snapshot = BoardStore(context).load()
        val checked = (checkedIds(context, snapshot) ?: calendarIds).filter { it in calendarIds }.toSet()
        val links = loadLinks(context)
        val hidden = hidden(context)
        val cards = snapshot.cards.associateBy { it.id }
        val updates = mutableMapOf<String, BoardCard>()
        val removals = mutableSetOf<String>()
        val additions = mutableListOf<BoardCard>()
        var linksChanged = false
        var hiddenChanged = false

        val zone = ZoneId.systemDefault()
        val windowStart = LocalDate.now().minusDays(90).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = LocalDate.now().plusDays(366).atStartOfDay(zone).toInstant().toEpochMilli()

        // Events Daymark wrote itself are never imported, whichever calendar they sit in.
        val ownIds = links.values.filter { it.pushed && it.eventId > 0 }.map { it.eventId }.toMutableSet()
        runCatching {
            context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CUSTOM_APP_PACKAGE} = ?", arrayOf(context.packageName), null)
                ?.use { c -> while (c.moveToNext()) ownIds += c.getLong(0) }
        }

        val column = snapshot.columnsFor(Workspace.CALENDAR).firstOrNull()
        val seen = mutableSetOf<String>()
        if (checked.isNotEmpty() && column != null) {
            val imported = snapshot.cards.filter { it.calendarSourceId.isNotBlank() }
            val bySource = imported.associateBy { it.calendarSourceId }
            val byEvent = imported.groupBy { it.calendarSourceId.substringBefore(':').toLongOrNull() }
            val linkedBySource = links.filter { !it.value.pushed && it.value.recurring }.entries.associate { it.value.source to it.key }
            val linkedByEvent = links.filter { !it.value.pushed && !it.value.recurring }.entries.associate { it.value.eventId to it.key }
            val uri = Instances.CONTENT_URI.buildUpon().appendPath(windowStart.toString()).appendPath(windowEnd.toString()).build()
            val projection = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.DESCRIPTION, Instances.DISPLAY_COLOR, Instances.EVENT_COLOR,
                Instances.CALENDAR_COLOR, Instances.ALL_DAY, Instances.CALENDAR_ID, Instances.RRULE, Instances.RDATE, Instances.ORIGINAL_ID)
            val inCalendars = "${Instances.CALENDAR_ID} IN (${checked.joinToString(",")})"
            val resolver = context.contentResolver
            val cursor: Cursor? = runCatching { resolver.query(uri, projection, "$inCalendars AND ${Events.DELETED} = 0", null, "${Instances.BEGIN} ASC") }
                .getOrElse { resolver.query(uri, projection, inCalendars, null, "${Instances.BEGIN} ASC") }
            // No answer from the provider: stop here rather than treat every imported card as deleted.
            if (cursor == null) return "Couldn’t read calendar events"
            cursor.use { c ->
                while (c.moveToNext()) {
                    val eventId = c.getLong(0)
                    if (eventId in ownIds) continue
                    val title = c.getString(1)?.trim().orEmpty()
                    val begin = c.getLong(2)
                    val end = if (c.isNull(3)) null else c.getLong(3)
                    val description = c.getString(4).orEmpty().trim()
                    val color = listOf(5, 6, 7).firstNotNullOfOrNull { i -> if (c.isNull(i)) null else c.getInt(i).takeIf { it != 0 } }
                        ?.let { if (it ushr 24 == 0) it or (0xFF shl 24) else it }?.toLong()
                    val allDay = c.getInt(8) == 1
                    val calendarId = c.getLong(9)
                    val recurring = !c.getString(10).isNullOrBlank() || !c.getString(11).isNullOrBlank() || !c.isNull(12)
                    val source = "$eventId:$begin"
                    val slot = slot(begin, end, allDay)
                    val pk = key(title, description, slot.day, slot.minute, slot.duration)

                    // Single events are matched by event id, so moving one (here or on the phone) keeps the same card.
                    val cardId = if (recurring) linkedBySource[source] ?: bySource[source]?.id
                        else linkedByEvent[eventId] ?: bySource[source]?.id ?: byEvent[eventId]?.firstOrNull { it.id !in seen }?.id
                    if (cardId != null && cardId in seen) continue
                    val card = cardId?.let { cards[it] }
                    val link = cardId?.let { links[it] }
                    if (card == null) {
                        // A linked card the app has since deleted is waiting for the push to hide it.
                        if (link != null && link.seen) continue
                        if (title.isBlank() || (link == null && hideKey(recurring, eventId, source) in hidden)) continue
                        val fresh = BoardCard(id = cardId ?: UUID.randomUUID().toString(), workspace = Workspace.CALENDAR, columnId = column.id, kind = CardKind.EVENT,
                            title = title, details = description, dueDay = slot.day, dueMinute = slot.minute, durationMinutes = slot.duration,
                            calendarSourceId = source, calendarColor = color)
                        additions += fresh
                        seen += fresh.id
                        links[fresh.id] = Link(eventId, calendarId, pushed = false, recurring = recurring, source = source, providerKey = pk, cardKey = cardKey(fresh), seen = false)
                        linksChanged = true
                        continue
                    }
                    seen += card.id
                    val next: BoardCard
                    val nextLink: Link
                    when {
                        link == null -> {
                            // Imported before two-way sync: refresh its time and colour as before, and start tracking both sides from here.
                            next = card.copy(dueDay = slot.day, dueMinute = slot.minute, durationMinutes = slot.duration, calendarColor = color ?: card.calendarColor, calendarSourceId = source)
                            nextLink = Link(eventId, calendarId, pushed = false, recurring = recurring, source = source, providerKey = pk, cardKey = cardKey(next))
                        }
                        pk != link.providerKey -> {
                            // Changed on the phone or in Google since the last sync: the phone's version wins.
                            next = card.copy(title = title.ifBlank { card.title }, details = description, dueDay = slot.day, dueMinute = slot.minute, durationMinutes = slot.duration,
                                calendarColor = color ?: card.calendarColor, calendarSourceId = source)
                            nextLink = link.copy(eventId = eventId, calendarId = calendarId, recurring = recurring, source = source, providerKey = pk, cardKey = cardKey(next))
                        }
                        else -> {
                            next = card.copy(calendarColor = color ?: card.calendarColor, calendarSourceId = source)
                            nextLink = link.copy(calendarId = calendarId, source = source)
                        }
                    }
                    if (next != card) updates[card.id] = next
                    if (nextLink != link) { links[card.id] = nextLink; linksChanged = true }
                    if (hidden.remove(hideKey(recurring, eventId, source))) hiddenChanged = true
                }
            }
        }

        // Imported cards that weren't found: gone on the phone, or their calendar was unticked.
        val unseen = snapshot.cards.filter { it.calendarSourceId.isNotBlank() && it.id !in seen && it.id !in updates }
        val rows = events(context, unseen.mapNotNull { it.calendarSourceId.substringBefore(':').toLongOrNull() })
        for (card in unseen) {
            val eventId = card.calendarSourceId.substringBefore(':').toLongOrNull() ?: continue
            val begin = card.calendarSourceId.substringAfter(':').toLongOrNull()
            val row = rows[eventId]
            val remove = if (row == null || row.deleted) {
                // Gone from the phone: remove it, unless its calendar itself went away (account removed, sync off).
                val calendarId = links[card.id]?.calendarId?.takeIf { it > 0 }
                calendarId == null || calendarId in calendarIds
            } else if (row.calendarId !in calendarIds) false
            else if (row.calendarId !in checked) true
            else column != null && begin != null && begin in windowStart until windowEnd
            if (remove) { removals += card.id; if (links.remove(card.id) != null) linksChanged = true }
        }

        // Daymark's own copies: pick up edits made on the phone, and notice copies deleted there.
        var pushedUpdates = 0
        val pushed = links.filter { it.value.pushed && it.value.eventId > 0 }
        val pushedRows = events(context, pushed.values.map { it.eventId })
        for ((cardId, link) in pushed) {
            val card = cards[cardId] ?: continue
            val row = pushedRows[link.eventId]
            if (row == null || row.deleted) {
                // Deleted in Google or on the phone: keep the card, and only copy it again if it changes in Daymark.
                links[cardId] = link.copy(eventId = 0, cardKey = cardKey(card)); linksChanged = true
                continue
            }
            val slot = slot(row.start, row.end, row.allDay)
            val pk = key(row.title, row.description.trim(), slot.day, slot.minute, slot.duration)
            if (pk == link.providerKey) continue
            val keepsImages = Markdown.imagePaths(card.details).isNotEmpty()
            val next = card.copy(
                title = row.title.trim().ifBlank { card.title },
                details = if (keepsImages) card.details else row.description.trim(),
                dueDay = slot.day,
                dueMinute = if (card.kind == CardKind.TASK && slot.minute == null) card.dueMinute else slot.minute,
                durationMinutes = if (card.kind == CardKind.EVENT) slot.duration else card.durationMinutes
            )
            if (next != card) { updates[cardId] = next; pushedUpdates++ }
            links[cardId] = link.copy(providerKey = pk, cardKey = cardKey(next)); linksChanged = true
        }

        if (linksChanged) saveLinks(context, links)
        if (hiddenChanged) saveHidden(context, hidden)
        if (updates.isNotEmpty() || removals.isNotEmpty() || additions.isNotEmpty()) post(CalendarPatch(updates, removals, additions), blocksPush = true)

        if (checked.isEmpty()) return if (pushedUpdates > 0) "Updated $pushedUpdates event${plural(pushedUpdates)} from your calendar" else "No calendars chosen · pick some in Settings › Calendar"
        if (column == null) return "Create a calendar column before importing"
        return buildList {
            if (additions.isNotEmpty()) add("Imported ${additions.size} event${plural(additions.size)}")
            if (updates.isNotEmpty()) add("updated ${updates.size}")
            if (removals.isNotEmpty()) add("removed ${removals.size}")
        }.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifBlank { "Calendar is up to date" }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}

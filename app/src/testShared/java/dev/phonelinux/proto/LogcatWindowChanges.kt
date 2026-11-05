package dev.phonelinux.proto

/** Auxiliary ordered-request evidence for the managed desktop comparison.
 *
 *  The pinned X server (termux-x11 29d4460b, cmdentrypoint.cpp) logs EVERY
 *  EVENT_SCREEN_SIZE it dequeues, on the server thread, BEFORE configure:
 *      E/tx11-request: window changed: <w> <h> <name>
 *  That line is a SERVER-OBSERVED request: it proves the server received and
 *  dequeued the event, not what the Java side emitted or when. Separately,
 *  lorieConfigureNotify logs on every apply-PATH attempt:
 *      V/LorieNative: New reported framerate is <fps>
 *  which is emitted inside the changed-branch and is NOT proof that
 *  RRCrtcNotify/RRScreenSizeSet succeeded.
 *  The androidTest side emits in-buffer BEGIN/END markers with a random id
 *  through the same tag, then performs ONE post-hoc bounded `logcat -d`
 *  dump (never -c, never a blocking follow). This object caps, scopes and
 *  classifies that dump. Pure string work, unit-testable off-device;
 *  nothing here touches Android APIs.
 *
 *  Honesty contract (encoded, not just documented):
 *   - Scope is BETWEEN the case's own BEGIN/END markers only. Entries
 *     before the last BEGIN are stale and counted, never interpreted.
 *   - ABSENCE of a line is UNKNOWN: logd ring-buffer loss inside the
 *     marked window is not audited, marker absence is detected and
 *     reported UNAVAILABLE, and a truncated capture is flagged. A missing
 *     intermediate request is NEVER proof that none was sent.
 *   - REQUEST lines are server-observed dequeued requests (pre-configure);
 *     APPLY lines mark the apply path being entered, not its success. Their
 *     correlation is ORDER-ONLY. Neither direction alone establishes
 *     causality for a client-side stuck window.
 *   - The raw tail is tag-filtered but may carry same-tag lines from OUTSIDE
 *     this case's marked window (session startup, other app activity on the
 *     emulator). Every line carries pid/tid in threadtime; the case's server
 *     PID/TID must be correlated with session evidence before attribution.
 *   - Unparseable lines inside the window are kept raw as OTHER, never
 *     silently dropped and never guessed. */
internal object LogcatWindowChanges {

    enum class Kind { REQUEST, APPLY, OTHER }

    data class Event(val kind: Kind, val width: Int, val height: Int, val raw: String)

    enum class Status { OK, UNAVAILABLE }

    data class Result(
        val status: Status,
        val reason: String,
        val truncated: Boolean,
        val staleDropped: Int,
        val events: List<Event>)

    const val MAX_LINES = 2000
    const val MAX_BYTES = 65536
    const val MAX_LINE = 1024

    /** threadtime: "MM-DD HH:MM:SS.mmm  PID  TID PRIO TAG: message". */
    private val LINE = Regex(
        """^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEA]\s+([^\s:]+):\s?(.*)$""")
    private val MARKER = Regex(
        """^LDOA-MANAGED-CASE MARKER (BEGIN|END) case=(original|patched) id=([0-9a-f]{16})$""")
    private val REQUEST = Regex("""^window changed: (\d+) (\d+)(?: (\S.*))?$""")
    private val APPLY = Regex("""^New reported framerate is (\d+)$""")
    private val MARKER_ID = Regex("""[0-9a-f]{16}""")

    /** Compat for the observed transport (run 42034582889) that wraps the
     *  marker body in ONE enclosing ASCII quote pair. Bare or exactly one
     *  enclosing pair is accepted; nested, mismatched or partial quoting
     *  never unwraps. The strict full-match grammar applies after unwrap;
     *  no substring, prefix/suffix or trim-all-quotes matching. */
    private fun unwrapOneQuotePair(msg: String): String {
        if (msg.length >= 2 && msg.first() == '\'' && msg.last() == '\'') {
            val inner = msg.substring(1, msg.length - 1)
            return if (inner.indexOf('\'') < 0) inner else msg
        }
        return msg
    }

    /** Exact marker body emitted through `log -t tx11-request`. */
    fun markerLine(begin: Boolean, case: String, id: String): String {
        require(case == "original" || case == "patched") { "case identity" }
        require(MARKER_ID.matches(id)) { "marker id shape" }
        return "LDOA-MANAGED-CASE MARKER " + (if (begin) "BEGIN" else "END") +
            " case=" + case + " id=" + id
    }

    /** Keeps the MOST RECENT bounded tail: at most [MAX_LINES] lines and at
     *  most [MAX_BYTES] UTF-8 BYTES (byte-accurate, whole lines dropped from
     *  the head), over-long lines capped at [MAX_LINE] chars with an explicit
     *  suffix. Returns (capped text, truncated). Never throws. */
    fun cap(capture: String): Pair<String, Boolean> {
        var truncated = false
        var lines = capture.lines()
        if (lines.size > MAX_LINES) { lines = lines.subList(lines.size - MAX_LINES, lines.size); truncated = true }
        lines = lines.map { if (it.length > MAX_LINE) { truncated = true; it.substring(0, MAX_LINE) + " [line-capped]" } else it }
        var totalBytes = 0
        var start = lines.size
        for (idx in lines.indices.reversed()) {
            val b = lines[idx].toByteArray(Charsets.UTF_8).size + 1
            if (totalBytes + b > MAX_BYTES) break
            totalBytes += b; start = idx
        }
        if (start > 0) truncated = true
        return lines.subList(start, lines.size).joinToString("\n") to truncated
    }

    private fun classify(tag: String, msg: String, raw: String): Event {
        if (tag == "tx11-request") {
            val r = REQUEST.matchEntire(msg)
            if (r != null) {
                val w = r.groupValues[1].toIntOrNull()
                val h = r.groupValues[2].toIntOrNull()
                if (w != null && h != null) return Event(Kind.REQUEST, w, h, raw)
            }
        }
        if (tag == "LorieNative" && APPLY.matchEntire(msg) != null) return Event(Kind.APPLY, 0, 0, raw)
        return Event(Kind.OTHER, 0, 0, raw)
    }

    /** Scopes [capture] (already capped) between the case's own markers and
     *  classifies the ordered contents. Fail-closed: any structural gap is
     *  UNAVAILABLE with an explicit reason, never a silent partial. */
    fun parse(capture: String?, case: String, id: String, truncated: Boolean, beginSent: Boolean, endSent: Boolean): Result {
        if (!beginSent) return Result(Status.UNAVAILABLE, "BEGIN marker emission failed - buffer not scoped by this run", truncated, 0, emptyList())
        if (!endSent) return Result(Status.UNAVAILABLE, "END marker emission failed - window end unknown", truncated, 0, emptyList())
        if (capture == null) return Result(Status.UNAVAILABLE, "bounded logcat dump failed or timed out", truncated, 0, emptyList())
        val lines = capture.lines()
        var beginIdx = -1
        for ((idx, ln) in lines.withIndex()) {
            val m = LINE.matchEntire(ln.trimEnd('\r')) ?: continue
            if (m.groupValues[1] != "tx11-request") continue
            val mm = MARKER.matchEntire(unwrapOneQuotePair(m.groupValues[2].trim()))
            if (mm != null && mm.groupValues[1] == "BEGIN" && mm.groupValues[2] == case && mm.groupValues[3] == id) beginIdx = idx
        }
        if (beginIdx < 0) return Result(Status.UNAVAILABLE,
            "BEGIN marker absent - ring-buffer loss, wrong run, or emission failure; contents NOT case-scoped", truncated, 0, emptyList())
        var stale = 0
        for (idx in 0 until beginIdx) {
            val m = LINE.matchEntire(lines[idx].trimEnd('\r')) ?: continue
            if (m.groupValues[1] == "tx11-request" || m.groupValues[1] == "LorieNative") stale++
        }
        var endIdx = -1
        for (idx in beginIdx + 1 until lines.size) {
            val m = LINE.matchEntire(lines[idx].trimEnd('\r')) ?: continue
            if (m.groupValues[1] != "tx11-request") continue
            val mm = MARKER.matchEntire(unwrapOneQuotePair(m.groupValues[2].trim()))
            if (mm != null && mm.groupValues[1] == "END" && mm.groupValues[2] == case && mm.groupValues[3] == id) { endIdx = idx; break }
        }
        if (endIdx < 0) return Result(Status.UNAVAILABLE,
            "END marker absent - capture predates case end or buffer wrapped; window incomplete", truncated, stale, emptyList())
        val events = mutableListOf<Event>()
        for (idx in beginIdx + 1 until endIdx) {
            val ln = lines[idx].trimEnd('\r')
            if (ln.isEmpty()) continue
            val m = LINE.matchEntire(ln)
            if (m == null) { events.add(Event(Kind.OTHER, 0, 0, ln)); continue }
            events.add(classify(m.groupValues[1], m.groupValues[2].trim(), ln))
        }
        return Result(Status.OK, "scoped window captured", truncated, stale, events)
    }

    /** Deterministic, self-contained artifact body. Caveats travel with the
     *  data so a reader never interprets absence as proof. */
    fun render(result: Result, case: String, id: String, beginWallMs: Long, endWallMs: Long, raw: String?): String {
        val b = StringBuilder()
        b.append("case=").append(case).append(" marker=").append(id).append('\n')
        b.append("capture=").append(result.status).append(" reason=").append(result.reason).append('\n')
        b.append("begin_wall_ms=").append(beginWallMs).append(" end_wall_ms=").append(endWallMs).append('\n')
        b.append("truncated=").append(result.truncated)
            .append(" stale_dropped=").append(result.staleDropped)
            .append(" events=").append(result.events.size).append('\n')
        b.append("scope_caveat=ordered requests/applies observed between this run's in-buffer markers only; ")
            .append("absence of a line is UNKNOWN (logd ring loss inside the window is not audited), never proof none was sent\n")
        b.append("request_vs_apply=tx11-request 'window changed' lines are SERVER-OBSERVED dequeued requests ")
            .append("(logged pre-configure; they do not prove what Java emitted); LorieNative 'New reported framerate' ")
            .append("lines mark the apply PATH being entered, NOT proof RRScreenSizeSet succeeded; correlation is order-only\n")
        b.append("privacy_scope=tag-filtered raw tail may carry same-tag lines from OUTSIDE this case's marked window ")
            .append("(session startup, other app activity on this emulator); every line carries pid/tid in threadtime - ")
            .append("correlate the case's server PID/TID with session evidence before attribution\n")
        b.append("events:\n")
        result.events.forEachIndexed { i, e ->
            b.append(i).append(' ').append(e.kind)
            if (e.kind == Kind.REQUEST) b.append(' ').append(e.width).append('x').append(e.height)
            b.append(" | ").append(e.raw).append('\n')
        }
        b.append("raw_capture_begin\n")
        if (raw != null) b.append(raw).append('\n')
        b.append("raw_capture_end\n")
        return b.toString()
    }
}

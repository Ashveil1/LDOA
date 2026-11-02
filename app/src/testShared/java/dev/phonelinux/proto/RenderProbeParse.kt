package dev.phonelinux.proto

/**
 * Parsers for the guest-side window-probe output used by the M1
 * render-qualification evidence (MobileDesktopEntryTest).
 *
 * Formats are source-verified, not assumed:
 *  - xwininfo.c Display_Stats_Info prints "  Absolute upper-left X:  %d",
 *    "  Width: %d", "  Height: %d", "  Map State: %s" with the map-state
 *    strings IsViewable / IsUnMapped / IsUnviewable.
 *  - xwininfo.c display_tree_info_1 prints one child per line as
 *    "<indent>0x%lx ["name"]: ("res_name" "res_class")  %ux%u+%d+%d  +%d+%d"
 *    (class hint pair, or "()" when none), nesting shown by deeper
 *    indentation, top-level children at the minimal indent.
 *  - xprop renders "<ATOM>(<TYPE>) = <value>" lines and
 *    "<ATOM>:  not found." when absent.
 *
 * Every parser returns null on ANY deviation from the verified shape -
 * the caller fails closed; unknown output is never interpreted.
 */
object RenderProbeParse {

    /** Parsed xwininfo stats block (per-window or root). */
    data class WinStats(val absX: Int, val absY: Int, val w: Int, val h: Int, val mapState: String) {
        fun overlaps(x: Int, y: Int, rw: Int, rh: Int): Boolean =
            absX < x + rw && x < absX + w && absY < y + rh && y < absY + h
        fun area(): Long = w.toLong() * h.toLong()
    }

    private fun intField(text: String, name: String): Int? =
        Regex("(?m)^  $name:\\s+(-?[0-9]+)$").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** xwininfo -id W / -root stats. Requires abs x/y, POSITIVE Width and
     *  Height and a known Map State; anything missing or unknown is
     *  unreadable output, never a zero. */
    fun winStats(text: String): WinStats? {
        val x = intField(text, "Absolute upper-left X") ?: return null
        val y = intField(text, "Absolute upper-left Y") ?: return null
        val w = intField(text, "Width")?.takeIf { it > 0 } ?: return null
        val h = intField(text, "Height")?.takeIf { it > 0 } ?: return null
        val ms = Regex("(?m)^  Map State: ([A-Za-z]+)$").find(text)?.groupValues?.get(1)
        if (ms != "IsViewable" && ms != "IsUnMapped" && ms != "IsUnviewable") return null
        return WinStats(x, y, w, h, ms)
    }

    /** One top-level child line from xwininfo -root -children. */
    data class ChildLine(val id: Long, val geomW: Int, val geomH: Int, val absX: Int, val absY: Int)

    private val childRe = Regex(
        "^( +)0x[0-9a-f]+(?: \"[^\"]*\")?: \\([^\\n]*\\)  " +
            "([0-9]+)x([0-9]+)\\+(-?[0-9]+)\\+(-?[0-9]+)  \\+(-?[0-9]+)\\+(-?[0-9]+)$")

    /** Top-level children of the root, STRICT: the root's declared
     *  "N children:" count must match the number of child records at the
     *  header's own indentation (nesting is +4 spaces per level), and EVERY
     *  record at that indentation must parse - a malformed record is never
     *  dropped, the whole listing is unreadable (null). "0 children." is a
     *  valid empty result. */
    fun topLevelChildren(text: String): List<ChildLine>? {
        val lines = text.lines()
        val headerRe = Regex("^( +)([0-9]+) child(?:ren)?:$")
        val zeroRe = Regex("^ +0 children\\.$")
        val hdrIdx = lines.indexOfFirst { headerRe.matches(it) || zeroRe.matches(it) }
        if (hdrIdx < 0) return null
        if (zeroRe.matches(lines[hdrIdx])) return emptyList()
        val hm = headerRe.matchEntire(lines[hdrIdx]) ?: return null
        val indent = hm.groupValues[1].length
        val declared = hm.groupValues[2].toInt()
        val out = mutableListOf<ChildLine>()
        var i = hdrIdx + 1
        while (i < lines.size) {
            val ln = lines[i]
            if (ln.isBlank()) break
            val ind = ln.takeWhile { it == ' ' }.length
            if (ind < indent) break
            if (ind == indent) {
                val m = childRe.find(ln) ?: return null   // malformed record: fail, never drop
                val g = m.groupValues
                val id = Regex("0x[0-9a-f]+").find(ln)?.value?.removePrefix("0x")?.toLong(16)
                    ?: return null
                out.add(ChildLine(id, g[2].toInt(), g[3].toInt(), g[6].toInt(), g[7].toInt()))
            }
            i++  // deeper indent = nested content, skipped by structure
        }
        if (out.size != declared) return null
        return out
    }

    /** Map an X-coordinate rect into bitmap coordinates via the surface
     *  bounds (offX/offY = surface origin in the bitmap, sx/sy = surface
     *  size / root size). Returns [l,t,r,b) or null on ANY invalid
     *  transform: non-positive or non-finite scale, empty rect, mapped
     *  rect not ENTIRELY inside the bitmap, or fewer than minSamples grid
     *  points - an unsampleable region is never silently clamped or
     *  vacuously passed. */
    fun mapRect(x: Int, y: Int, w: Int, h: Int,
                sx: Float, sy: Float, offX: Int, offY: Int,
                bmpW: Int, bmpH: Int, grid: Int = 32, minSamples: Int = 4): IntArray? {
        if (!sx.isFinite() || !sy.isFinite() || sx <= 0f || sy <= 0f) return null
        if (w <= 0 || h <= 0) return null
        val l = offX + (x * sx).toInt()
        val t = offY + (y * sy).toInt()
        val r = offX + ((x + w) * sx).toInt()
        val b = offY + ((y + h) * sy).toInt()
        if (l < 0 || t < 0 || r > bmpW || b > bmpH || r <= l || b <= t) return null
        var nx = 0; var px = l; while (px < r) { nx++; px += grid }
        var ny = 0; var py = t; while (py < b) { ny++; py += grid }
        if (nx * ny < minSamples) return null
        return intArrayOf(l, t, r, b)
    }

    /** _NET_WM_PID(CARDINAL) = 8603 -> 8603. Absent property ("not found")
     *  and malformed output both yield null; callers distinguish presence
     *  from absence via xpropHasPid. */
    fun xpropPid(text: String): Long? =
        Regex("(?m)^_NET_WM_PID\\(CARDINAL\\) = ([0-9]+)$").find(text)?.groupValues?.get(1)?.toLongOrNull()

    /** True when xprop explicitly reported the property absent. */
    fun xpropPidAbsent(text: String): Boolean =
        Regex("(?m)^_NET_WM_PID: +not found\\.").containsMatchIn(text)

    /** WM_CLASS(STRING) = "xterm", "XTerm" -> ("xterm", "XTerm"). */
    fun xpropClass(text: String): Pair<String, String>? {
        val m = Regex("(?m)^WM_CLASS\\(STRING\\) = \"([^\"]*)\", \"([^\"]*)\"$").find(text)
            ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** _NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_DESKTOP[, ...] ->
     *  the type list. Per EWMH a window may carry SEVERAL types; xprop
     *  renders them comma-separated on one line. An explicitly ABSENT
     *  property ("not found.") is an empty list (EWMH: unset is treated
     *  as _NET_WM_WINDOW_TYPE_NORMAL), NOT an error. Any other shape -
     *  missing line, empty value, non-atom token - is null (fail closed). */
    fun xpropWmTypes(text: String): List<String>? {
        if (Regex("(?m)^_NET_WM_WINDOW_TYPE: +not found\\.").containsMatchIn(text)) return emptyList()
        val m = Regex("(?m)^_NET_WM_WINDOW_TYPE\\(ATOM\\) = (.+)$").find(text) ?: return null
        val parts = m.groupValues[1].split(",").map { it.trim() }
        if (parts.any { !it.matches(Regex("_NET_WM_WINDOW_TYPE_[A-Z0-9_]+")) }) return null
        return parts
    }

    /** True when ANY two qualified desktop rects overlap - overlapping
     *  desktops make z-order/painter attribution ambiguous anywhere in
     *  the shared region, so the caller fails closed. */
    fun anyOverlap(rects: List<IntArray>): Boolean {
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2] &&
                a[1] < b[1] + b[3] && b[1] < a[1] + a[3]) return true
        }
        return false
    }

    /** xrandr --listmonitors record. ACTIVE means w>0 && h>0: xrandr.c
     *  does not print the protocol active flag; a client-defined INACTIVE
     *  monitor reports 0x0 geometry (xserver rrmonitor.c RRMonitorMakeList
     *  zero-area rule - get_active=False keeps it in the list). */
    data class RrMonitor(val index: Int, val primary: Boolean, val active: Boolean,
                         val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /** xrandr --listmonitors, format SOURCE-VERIFIED against xrandr.c:
     *  header "Monitors: N" then N records printed as
     *  " i: <auto?'+':''><primary?'*':''><name> <w>/<mmw>x<h>/<mmh>+<x>+<y> <outputs...>"
     *  - both flags OPTIONAL, in that order (automatic '+', primary '*')
     *  - coordinates: a LITERAL '+' then %d, so a negative x renders
     *    "+-100" - that is the real negative format; a bare "-100" is NOT
     *  - the output list is OPTIONAL (zero outputs: the line ends after
     *    the position; an inactive client-defined monitor has none)
     *  STRICT: exactly N records with indices EXACTLY 0..N-1 each once,
     *  at most ONE primary and never on an inactive monitor, every number
     *  inside the X wire ranges (coords INT16, dims CARD16) via
     *  overflow-safe parsing, and a monitor is either fully 0x0
     *  (inactive) or fully positive (active) - half-zero geometry is
     *  malformed. "RandR 1.5 not supported" or ANY other deviation is
     *  null: topology unknown, never guessed. */
    fun xrandrListMonitors(text: String): List<RrMonitor>? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val hdr = Regex("^Monitors: ([0-9]+)$").matchEntire(lines[0].trim()) ?: return null
        val declared = hdr.groupValues[1].toLongOrNull()?.takeIf { it in 0..1024 }?.toInt() ?: return null
        val rec = Regex("^ ([0-9]+): (\\+?)(\\*?)(\\S+) ([0-9]+)/([0-9]+)x([0-9]+)/([0-9]+)" +
            "\\+(-?[0-9]+)\\+(-?[0-9]+)((?:\\s+\\S+)*)\\s*$")
        val out = mutableListOf<RrMonitor>()
        for (ln in lines.drop(1)) {
            val m = rec.matchEntire(ln) ?: return null
            val g = m.groupValues
            val idx = g[1].toLongOrNull()?.takeIf { it in 0..1023 }?.toInt() ?: return null
            val w = g[5].toLongOrNull() ?: return null
            val mmw = g[6].toLongOrNull() ?: return null
            val h = g[7].toLongOrNull() ?: return null
            val mmh = g[8].toLongOrNull() ?: return null
            val x = g[9].toLongOrNull() ?: return null
            val y = g[10].toLongOrNull() ?: return null
            if (w !in 0..65535 || h !in 0..65535 || mmw !in 0..65535 || mmh !in 0..65535) return null
            if (x !in -32768..32767 || y !in -32768..32767) return null
            if ((w == 0L) != (h == 0L)) return null  // half-zero geometry is malformed
            val active = w > 0
            val primary = g[3] == "*"
            if (primary && !active) return null      // primary needs geometry to anchor on
            out.add(RrMonitor(idx, primary, active, g[4], x.toInt(), y.toInt(), w.toInt(), h.toInt()))
        }
        if (out.size != declared) return null
        val sorted = out.sortedBy { it.index }
        for (i in sorted.indices) if (sorted[i].index != i) return null  // duplicate/gap
        if (out.count { it.primary } > 1) return null
        return out
    }

    /** xdpyinfo -ext XINERAMA topology state. The three DECLARED states
     *  are evidence; MALFORMED output is not (null, and the caller never
     *  falls back to RandR on null). */
    enum class XineramaState { ABSENT, INACTIVE, ACTIVE }

    /** heads are root-relative [x, y, w, h]; meaningful only when ACTIVE. */
    data class XineramaInfo(val state: XineramaState, val heads: List<IntArray>)

    /** xdpyinfo -ext XINERAMA, format SOURCE-VERIFIED against xdpyinfo.c
     *  (print_xinerama_info + print_standard_extension_info + the -ext
     *  dispatch):
     *  - extension not supported by the server: "XINERAMA extension not
     *    supported by server" -> ABSENT (declared, no topology evidence)
     *  - present but inactive: "XINERAMA version M.m opcode: N" then
     *    "  Xinerama is inactive." -> INACTIVE (declared zero screens)
     *  - active: header then "  head #i: <w>x<h> @ <x>,<y>" records
     *    (coordinates are plain signed %d - NO '+') with indices EXACTLY
     *    0..N-1 -> ACTIVE. ZERO head records under a bare header is
     *    ACTIVE with an empty list - the REAL lorie state (vendored
     *    pseudoramiX.c ProcPseudoramiXIsActive: rep.state =
     *    !noPseudoramiXExtension, i.e. TRUE with zero registered
     *    screens), not an error and not topology evidence.
     *  The core-display preamble ("number of screens:", "screen #0:",
     *  "dimensions:") is a DIFFERENT section and is never consulted.
     *  ANY other line inside the section, an inactive marker mixed with
     *  head records, duplicate/gapped indices, non-positive or
     *  out-of-wire-range dims, or a header contradicting a not-supported
     *  line is null - malformed, fail closed. */
    fun xdpyinfoXinerama(text: String): XineramaInfo? {
        val lines = text.lines()
        val hdrRe = Regex("^XINERAMA version ([0-9]+)\\.([0-9]+) opcode: ([0-9]+)" +
            "(, base event: [0-9]+)?(, base error: [0-9]+)?$")
        val notSupported = "XINERAMA extension not supported by server"
        val absent = lines.any { it == notSupported }
        val hdrIdx = lines.indexOfFirst { hdrRe.matches(it) }
        if (hdrIdx < 0) {
            // A column-0 XINERAMA line that is neither a VALID header nor the
            // exact not-supported line is a MALFORMED section header - never
            // let it ride along into an ABSENT classification.
            if (lines.any { it.startsWith("XINERAMA") && it != notSupported }) return null
            return if (absent) XineramaInfo(XineramaState.ABSENT, emptyList()) else null
        }
        if (absent) return null  // header AND not-supported: contradictory
        // a column-0 XINERAMA line BEFORE the valid header is equally malformed
        if (lines.take(hdrIdx).any { it.startsWith("XINERAMA") }) return null
        // Section = everything after the header; trailing blanks dropped,
        // an interior blank or any non-record line is malformed.
        val tail = lines.drop(hdrIdx + 1).dropLastWhile { it.isBlank() }
        if (tail.any { it.isBlank() }) return null
        val headRe = Regex("^  head #([0-9]+): ([0-9]+)x([0-9]+) @ (-?[0-9]+),(-?[0-9]+)$")
        val heads = mutableListOf<Pair<Int, IntArray>>()
        var inactive = false
        for (ln in tail) {
            if (ln == "  Xinerama is inactive.") {
                if (inactive) return null  // duplicate marker: malformed
                inactive = true; continue
            }
            val m = headRe.matchEntire(ln) ?: return null
            val g = m.groupValues
            val idx = g[1].toLongOrNull()?.takeIf { it in 0..1023 }?.toInt() ?: return null
            val w = g[2].toLongOrNull() ?: return null
            val h = g[3].toLongOrNull() ?: return null
            val x = g[4].toLongOrNull() ?: return null
            val y = g[5].toLongOrNull() ?: return null
            if (w !in 1..65535 || h !in 1..65535) return null
            if (x !in -32768..32767 || y !in -32768..32767) return null
            heads.add(idx to intArrayOf(x.toInt(), y.toInt(), w.toInt(), h.toInt()))
        }
        if (inactive) return if (heads.isEmpty()) XineramaInfo(XineramaState.INACTIVE, emptyList()) else null
        val sorted = heads.sortedBy { it.first }
        for (i in sorted.indices) if (sorted[i].first != i) return null  // duplicate/gap
        return XineramaInfo(XineramaState.ACTIVE, sorted.map { it.second })
    }

    /** EXACT one-to-one geometry correspondence between desktop window
     *  rects and server monitor rects ([x, y, w, h] each); returns the
     *  desktop->monitor index map, or null unless every desktop matches
     *  exactly one monitor and vice versa. Tolerance is ZERO by
     *  construction, not neglect: pcmanfm realizes each FmDesktop at its
     *  GDK monitor's exact pixel geometry (source: desktop.c) and both
     *  xwininfo and xrandr report whole pixels of the SAME RandR
     *  geometry, so ANY mismatch is a mis-realized or phantom window,
     *  never measurement noise. Duplicate monitor rects (clone topology)
     *  make the correspondence ambiguous: null. */
    fun matchMonitorBijection(desks: List<IntArray>, mons: List<IntArray>): IntArray? {
        if (desks.size != mons.size) return null
        if (desks.any { it.size != 4 } || mons.any { it.size != 4 }) return null
        val map = IntArray(desks.size) { -1 }
        val used = BooleanArray(mons.size)
        for (di in desks.indices) {
            var found = -1
            for (mi in mons.indices) {
                if (used[mi]) continue
                val d = desks[di]; val m = mons[mi]
                if (d[0] == m[0] && d[1] == m[1] && d[2] == m[2] && d[3] == m[3]) {
                    if (found >= 0) return null  // duplicate monitor rects: ambiguous
                    found = mi
                }
            }
            if (found < 0) return null
            map[di] = found; used[found] = true
        }
        return map
    }

    /** One settle-sampler observation reduced to what the control flow
     *  gates on. [doneNanos] is the round COMPLETION time on the budget's
     *  clock; a round is late when it COMPLETED at/past the subdeadline,
     *  whenever it started. */
    data class SettleObservation(val reason: String?, val state: TopoState?, val doneNanos: Long)

    class SettleFailure(message: String) : AssertionError(message)

    /** The settle-sampler control flow, with clock/sample/sleep injected so
     *  deadline, slow-call and stability behavior are unit-testable with
     *  synthetic times. Rules (identical in instrument and fixtures):
     *  [needed] CONSECUTIVE consistent observations whose states are EQUAL
     *  on the full stable key; any inconsistent observation resets the
     *  streak; a sample or revalidation COMPLETING at/past the subdeadline
     *  ALWAYS fails; the budget ending without stability ALWAYS fails (a
     *  final lucky frame never substitutes for stability); after stability,
     *  one COMPLETE extra observation under the same budget must still be
     *  consistent and key-equal (full current-state revalidation, never a
     *  partial guard). [onSample] receives every observation for logging.
     *
     *  NOT closed (Owner C): a destroy/recreate of the SAME process+window
     *  identity INSIDE one round is theoretically undetectable by pid/
     *  class/XID rereads; the full current-state revalidation each round
     *  is the mitigation. This case is explicitly not claimed closed. */
    fun settleLoop(budget: SettleBudget, needed: Int = 3, intervalMs: Long = 500L,
                   now: () -> Long, sleep: (Long) -> Unit,
                   sample: () -> SettleObservation,
                   onSample: (Int, SettleObservation) -> Unit = { _, _ -> }): SettleObservation {
        val tracker = SettleTracker(needed)
        var stable: SettleObservation? = null
        var idx = 0
        while (now() < budget.endNanos) {
            val s = sample()
            budget.lateReason(s.doneNanos)?.let {
                throw SettleFailure("settle sample COMPLETED after the subdeadline ($it)" +
                    " - return-late ALWAYS fails")
            }
            onSample(idx, s)
            idx++
            if (tracker.offer(s.reason == null, s.state)) { stable = s; break }
            val remainMs = budget.remainingMs(now())
            if (remainMs <= 0) break
            sleep(minOf(intervalMs, remainMs))
        }
        val st = stable ?: throw SettleFailure("topology NEVER STABILIZED within the" +
            " bounded sampler budget - FAIL (a final passing frame cannot substitute for" +
            " stability)")
        // Pre-final deadline check: the extracted control flow never ISSUES
        // the revalidation once the budget is gone (the instrument's
        // per-probe caps would also refuse, but the contract holds without
        // relying on the sample's own wiring).
        budget.lateReason(now())?.let {
            throw SettleFailure("budget EXHAUSTED before post-stability revalidation" +
                " ($it) - never issued")
        }
        val fin = sample()
        budget.lateReason(fin.doneNanos)?.let {
            throw SettleFailure("post-stability revalidation COMPLETED after the" +
                " subdeadline ($it) - return-late ALWAYS fails")
        }
        onSample(idx, fin)   // the revalidation is logged like every observation
        budget.lateReason(now())?.let {
            throw SettleFailure("post-revalidation bookkeeping completed after the" +
                " subdeadline ($it) - return-late ALWAYS fails")
        }
        if (fin.reason != null || fin.state == null || !topoStatesEqual(fin.state!!, st.state!!))
            throw SettleFailure("post-stability revalidation shows CHURN (reason=" +
                (fin.reason ?: "stable-key mismatch") + ") - UNKNOWN")
        return st
    }

    /** Bounded settle-sampler clock. Every decision takes an EXPLICIT
     *  caller-supplied now (System.nanoTime() at the call site) so the
     *  behavior is unit-testable with synthetic times.
     *  subdeadline = min(start + cap, globalEnd - margin). A call that
     *  ENDS at/past the subdeadline is ALWAYS late, whenever it started. */
    class SettleBudget(startNanos: Long, globalEndNanos: Long,
                       capNanos: Long, marginNanos: Long) {
        val endNanos: Long = minOf(startNanos + capNanos, globalEndNanos - marginNanos)
        fun remainingMs(nowNanos: Long): Long =
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(endNanos - nowNanos)
        /** Non-null when now is at/past the subdeadline (return-late = fail). */
        fun lateReason(nowNanos: Long): String? =
            if (nowNanos >= endNanos) "settle-subdeadline-exceeded" else null
    }

    /** One settle-sampler topology observation keyed on IDENTITY, not just
     *  geometry: the owned pcmanfm process (pid + /proc starttime) and the
     *  desktop XID are part of the stable key, so a re-realized window or
     *  restarted process resets stability even at identical rects.
     *  monRects/deskRects are canonically content-sorted by the caller. */
    data class TopoState(val pcmanPid: Int, val pcmanStart: String,
                         val deskId: Long, val deskRect: IntArray,
                         val deskRects: List<IntArray>, val monRects: List<IntArray>,
                         val primaryMonIdx: Int, val rootRect: IntArray)

    /** Full stable-key equality (identity + geometry + intended selection). */
    fun topoStatesEqual(a: TopoState, b: TopoState): Boolean =
        a.pcmanPid == b.pcmanPid && a.pcmanStart == b.pcmanStart &&
        a.deskId == b.deskId && a.deskRect.contentEquals(b.deskRect) &&
        a.deskRects.size == b.deskRects.size &&
        a.deskRects.zip(b.deskRects).all { (x, y) -> x.contentEquals(y) } &&
        a.monRects.size == b.monRects.size &&
        a.monRects.zip(b.monRects).all { (x, y) -> x.contentEquals(y) } &&
        a.primaryMonIdx == b.primaryMonIdx &&
        a.rootRect.contentEquals(b.rootRect)

    /** The FULL render-qualification gate set over one sampled topology as
     *  a typed reason (null = consistent) - the same invariants the gates
     *  assert: positive root, at least one active monitor, no monitor
     *  overlap, every monitor inside the root framebuffer, qualified
     *  desktop count == active monitor count, exact desktop-to-monitor
     *  bijection, and a unique primary whenever multiple desktops qualify. */
    fun topologyConsistency(rootRect: IntArray, monRects: List<IntArray>,
                            primaryCount: Int, deskRects: List<IntArray>): String? {
        if (rootRect.size != 4 || rootRect[2] <= 0 || rootRect[3] <= 0) return "root-degenerate"
        if (monRects.isEmpty()) return "zero-active-monitors"
        if (anyOverlap(monRects)) return "monitor-overlap"
        if (monRects.any { it.size != 4 || it[0] < rootRect[0] || it[1] < rootRect[1] ||
                it[0] + it[2] > rootRect[0] + rootRect[2] ||
                it[1] + it[3] > rootRect[1] + rootRect[3] }) return "monitor-outside-root"
        if (deskRects.size != monRects.size)
            return "qualified(" + deskRects.size + ")!=monitors(" + monRects.size + ")"
        if (matchMonitorBijection(deskRects, monRects) == null) return "no-desktop-monitor-bijection"
        if (deskRects.size > 1 && primaryCount != 1) return "primary-not-unique"
        return null
    }

    /** Bounded settle-sampler streak tracker. offer() one KNOWN observation
     *  (consistent = the full gate set passed on THAT sample's own current
     *  topology). Stability = [need] CONSECUTIVE observations that are EACH
     *  consistent AND EQUAL on the full stable key. An inconsistent known
     *  observation resets the streak; individually-consistent-but-DIFFERENT
     *  states never stabilize. Unknown observations never reach here - the
     *  caller fails them immediately. */
    class SettleTracker(private val need: Int = 3) {
        private var streak = 0
        private var last: TopoState? = null
        fun offer(consistent: Boolean, state: TopoState?): Boolean {
            if (!consistent || state == null) { streak = 0; last = null; return false }
            streak = if (last != null && topoStatesEqual(last!!, state)) streak + 1 else 1
            last = state
            return streak >= need
        }
    }

    /** xdotool search: one DECIMAL window id per line. Any other line
     *  makes the whole result unreadable (never silently drop output). */
    fun searchIds(text: String): List<Long>? {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        return if (lines.all { it.matches(Regex("[0-9]+")) }) lines.map { it.toLong() } else null
    }
}

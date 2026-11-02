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

    /** xdotool search: one DECIMAL window id per line. Any other line
     *  makes the whole result unreadable (never silently drop output). */
    fun searchIds(text: String): List<Long>? {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        return if (lines.all { it.matches(Regex("[0-9]+")) }) lines.map { it.toLong() } else null
    }
}

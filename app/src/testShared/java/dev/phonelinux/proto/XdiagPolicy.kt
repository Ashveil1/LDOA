package dev.phonelinux.proto

/** Pure budget/truncation arithmetic for the xdiag diagnostic so the
 *  numbers are unit-testable off-device. The androidTest code consumes
 *  these; nothing here touches Android APIs. */
internal object XdiagPolicy {
    /** Fixed post-ready observation window. */
    const val OBSERVE_MS = 40_000L
    /** Per-guest-command budget; the effective timeout is the minimum of
     *  this and the remaining overall budget. */
    const val COMMAND_BUDGET_MS = 20_000L
    /** Slack over startup+observe for smoke, snapshots, versions and
     *  their per-command waits inside the ONE overall deadline. */
    const val TOTAL_MARGIN_MS = 240_000L
    /** Cleanup ladders run outside the overall deadline but stay fixed
     *  and bounded; the in-guest proxy lifetime covers deadline+cleanup. */
    const val CLEANUP_MARGIN_MS = 60_000L
    /** Hard in-guest trace file cap (enforced by ulimit -f at source,
     *  512-byte blocks) and the smaller host-side export cap. */
    const val TRACE_FILE_CAP_BYTES = 32L * 1024 * 1024
    const val EXPORT_CAP_BYTES = 5L * 1024 * 1024
    const val TRACE_READ_CAP = 256 * 1024

    fun totalBudgetMs(startBudgetMs: Long): Long =
        startBudgetMs + OBSERVE_MS + TOTAL_MARGIN_MS

    /** In-guest proxy lifetime: the overall budget plus the cleanup
     *  margin, in whole seconds. Never silently longer - the test asserts
     *  lifetime*1000 <= totalBudgetMs + CLEANUP_MARGIN_MS. */
    fun proxyLifetimeSec(totalBudgetMs: Long): Int =
        ((totalBudgetMs + CLEANUP_MARGIN_MS + 999) / 1000).toInt()

    /** ulimit -f block count (512-byte units, dash-verified) for the
     *  in-guest trace file cap. */
    fun traceCapBlocks512(): Long = TRACE_FILE_CAP_BYTES / 512

    /** Head/marker/tail layout for truncating [size] bytes into
     *  [remaining] bytes. Returns null when the file fits or when the
     *  remaining budget cannot even hold marker + 1 head + 1 tail byte
     *  (fail closed, never a negative tail). */
    private fun truncLayout(size: Long, remaining: Long, markerLen: Int): Triple<Int, Int, Int>? {
        if (size <= remaining) return null
        val avail = remaining - markerLen
        if (avail < 2) return null
        val head = (avail / 2).toInt()
        val tail = (avail - head).toInt()
        return Triple(head, markerLen, tail)
    }

    /** Strict WM_CLASS parser: `WM_CLASS(STRING) = "instance", "class"`;
     *  true when either field is exactly pcmanfm (case-insensitive). No
     *  wildcard/contains matching. */
    private val WM_CLASS_RX = Regex("^WM_CLASS\\(STRING\\) = \"([^\"]*)\", \"([^\"]*)\"$")
    fun wmClassIsPcmanfm(xpropOutput: String): Boolean =
        xpropOutput.lineSequence().map { it.trim() }.mapNotNull { WM_CLASS_RX.find(it) }
            .any { it.groupValues[1].equals("pcmanfm", true) || it.groupValues[2].equals("pcmanfm", true) }

    /** Connection ids in an xtrace window that carry ALL of: client setup
     *  handshake, Success setup reply, and a GetMonitors request - the
     *  same-id correlation the smoke probe requires. Line shapes are
     *  grounded in actual xtrace output (`NNN:<: am lsb-first`,
     *  `NNN:>: Success, version is 11:0`, `NNN:<:seq: ... GetMonitors`). */
    fun correlatedGetMonitorsConnections(window: String): Set<String> {
        // xtrace prints ids with %03d - a MINIMUM width (source-verified):
        // ids >= 1000 print wider, so a fixed 3-digit pattern silently
        // stops matching exactly when the session gets busy.
        val setup = Regex("(?m)^(\\d+):<: am lsb-first").findAll(window).map { it.groupValues[1] }.toSet()
        val ok = Regex("(?m)^(\\d+):>: Success, version is 11:0").findAll(window).map { it.groupValues[1] }.toSet()
        val gm = Regex("(?m)^(\\d+):<:[0-9a-f]+: .*GetMonitors").findAll(window).map { it.groupValues[1] }.toSet()
        return setup.intersect(ok).intersect(gm)
    }

    /** Per-file export decision under a hard total cap. */
    sealed class ExportPlan {
        data class Full(val bytes: Long) : ExportPlan()
        data class Truncated(val head: Int, val markerLen: Int, val tail: Int) : ExportPlan()
        object Omit : ExportPlan() // does not fit even as a marker - caller must fail loudly
    }
    fun exportPlan(size: Long, remaining: Long, markerLen: Int): ExportPlan = when {
        size <= remaining -> ExportPlan.Full(size)
        else -> {
            val l = truncLayout(size, remaining, markerLen)
            if (l != null) ExportPlan.Truncated(l.first, l.second, l.third)
            else if (markerLen <= remaining) ExportPlan.Truncated(0, markerLen, 0)
            else ExportPlan.Omit
        }
    }

    /** /proc scan entry classification, pure: foreign-UID entries are
     *  skipped (cannot be ours); vanished = gone; own+readable = use;
     *  own+unreadable = UNKNOWN (fail closed). */
    enum class ScanDecision { SKIP_FOREIGN, GONE, USE, UNKNOWN }
    fun scanDecision(isOwnUid: Boolean, readable: Boolean, stillExists: Boolean): ScanDecision = when {
        !isOwnUid -> ScanDecision.SKIP_FOREIGN
        readable -> ScanDecision.USE
        !stillExists -> ScanDecision.GONE
        else -> ScanDecision.UNKNOWN
    }
}

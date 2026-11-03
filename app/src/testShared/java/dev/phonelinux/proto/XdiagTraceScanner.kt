package dev.phonelinux.proto

/** Bounded STREAMING xtrace shape scanner (pure; no Android APIs), the
 *  bounded streaming replacement for the single capped read that threw in
 *  run 41590351769. Fed fixed-size byte chunks in file order; finds the
 *  connection ids carrying ALL of: client setup handshake, Success setup
 *  reply, and a GetMonitors request - the same-id correlation the smoke
 *  probe requires - without ever holding the window in memory.
 *
 *  Every bound fails closed into unknownReason (never silent):
 *   - caller contract: at most XdiagPolicy.SCAN_CHUNK_BYTES per feed;
 *   - exactly one partial line carried across chunks, <= SCAN_CARRY_CAP;
 *   - shape state retained only for ids with an in-window setup, at most
 *     SCAN_MAX_CONNECTIONS ids (a never-setup id's lines are ignored);
 *   - a second in-window setup for an already-seen id (id reuse);
 *   - at finish(), a trailing partial line with a traced-line prefix
 *     (the window may hide a shape).
 *  Lines decode ISO-8859-1 (1:1 byte->char): no UTF-8 failure mode, and
 *  the ASCII shapes match iff the bytes match, with byte-exact offsets.
 *
 *  [firstLineComplete] comes from the caller's base-1 boundary probe:
 *  false means the bytes up to the first newline are a mid-line FRAGMENT -
 *  discarded, counted in discardedFragmentBytes, never matched. The probe
 *  distinguishes boundary from fragment under the append-only invariant;
 *  it does not prove freshness under delayed flush. */
internal class XdiagTraceScanner(private val firstLineComplete: Boolean) {
    private var carry = ByteArray(0)
    private var firstLineHandled = false
    private var unknown: String? = null
    private val setup = LinkedHashSet<String>()
    private val ok = mutableSetOf<String>()
    private val gm = mutableSetOf<String>()
    var discardedFragmentBytes: Long = 0; private set
    var scannedBytes: Long = 0; private set
    /** Bounded shape tallies for diagnostics (setup <= MAX_CONNECTIONS+1
     *  before overflow; ok/gm only counted for retained ids). */
    var setupSeen: Int = 0; private set
    var okSeen: Int = 0; private set
    var gmSeen: Int = 0; private set

    /** Non-null once any bound overflowed; the scanner then freezes
     *  (later feeds are ignored) and the caller must fail UNKNOWN. */
    fun unknownReason(): String? = unknown

    /** Raw correlated set; callers MUST check unknownReason() first. */
    fun correlated(): Set<String> = setup intersect ok intersect gm

    fun feed(chunk: ByteArray, off: Int = 0, len: Int = chunk.size - off) {
        require(len <= XdiagPolicy.SCAN_CHUNK_BYTES) {
            "feed chunk " + len + " exceeds SCAN_CHUNK_BYTES" }
        if (unknown != null || len <= 0) return
        var i = off
        val end = off + len
        while (i < end) {
            var nl = i
            while (nl < end && chunk[nl] != '\n'.code.toByte()) nl++
            val segEnd = if (nl < end) nl else end
            val seg = chunk.copyOfRange(i, segEnd)
            scannedBytes += seg.size
            if (nl >= end) { // no newline: extend the carry, bounded
                carry = carry + seg
                if (carry.size > XdiagPolicy.SCAN_CARRY_CAP) {
                    unknown = "line carry overflow (" + carry.size + " > " +
                        XdiagPolicy.SCAN_CARRY_CAP + " bytes)"
                    return
                }
                return
            }
            // Combined-line bound on BOTH branches: carry + segment is the
            // full line, and it must obey the cap before handleLine ever
            // sees it (a full carry must not bypass the cap via newline).
            if (carry.size + seg.size > XdiagPolicy.SCAN_CARRY_CAP) {
                unknown = "line carry overflow (" + (carry.size + seg.size) + " > " +
                    XdiagPolicy.SCAN_CARRY_CAP + " bytes, completed line)"
                carry = ByteArray(0)
                return
            }
            scannedBytes += 1 // the newline
            val line = if (carry.isEmpty()) seg else carry + seg
            carry = ByteArray(0)
            handleLine(line)
            if (unknown != null) return
            i = nl + 1
        }
    }

    /** Terminal evaluation: a trailing partial line is EXCLUDED from the
     *  predicate - unless it starts a traced line, in which case the
     *  window end may hide a shape and the verdict is UNKNOWN. */
    fun finish() {
        if (unknown != null || carry.isEmpty()) return
        if (!firstLineHandled && !firstLineComplete) {
            discardedFragmentBytes += carry.size
            firstLineHandled = true
            carry = ByteArray(0)
            return
        }
        if (terminalPartialAmbiguous()) {
            unknown = "window ends mid-line on a possible traced line (" +
                carry.size + " carried bytes)"
        }
        carry = ByteArray(0)
    }

    /** True when the carried partial line could still grow into a traced
     *  shape line - conservatively: any digit-led partial (traced lines
     *  start with the decimal connection id; short prefixes like "010"
     *  or "010:<" count). The sampled window end is then ambiguous: PASS
     *  must be deferred, and finish() fails closed UNKNOWN. The not-yet-
     *  handled leading fragment is excluded here by design (it is
     *  discarded, never matched). */
    fun terminalPartialAmbiguous(): Boolean =
        carry.isNotEmpty() && (firstLineHandled || firstLineComplete) &&
            carry[0] >= '0'.code.toByte() && carry[0] <= '9'.code.toByte()

    private val setupRx = Regex("^(\\d+):<: am lsb-first")
    private val okRx = Regex("^(\\d+):>: Success, version is 11:0")
    private val gmRx = Regex("^(\\d+):<:[0-9a-f]+: .*GetMonitors")

    private fun handleLine(line: ByteArray) {
        if (!firstLineHandled) {
            firstLineHandled = true
            if (!firstLineComplete) { discardedFragmentBytes += line.size; return }
        }
        val s = String(line, Charsets.ISO_8859_1)
        val su = setupRx.find(s)
        if (su != null) {
            val id = su.groupValues[1]
            if (!setup.add(id)) {
                unknown = "same-id reuse: second in-window setup for connection " + id
                return
            }
            setupSeen++
            if (setup.size > XdiagPolicy.SCAN_MAX_CONNECTIONS) {
                unknown = "connection state overflow (> " + XdiagPolicy.SCAN_MAX_CONNECTIONS +
                    " in-window setups)"
            }
            return
        }
        val o = okRx.find(s)
        if (o != null) { val id = o.groupValues[1]; if (id in setup) { if (ok.add(id)) okSeen++ }; return }
        val g = gmRx.find(s)
        if (g != null) { val id = g.groupValues[1]; if (id in setup) { if (gm.add(id)) gmSeen++ } }
    }
}

/** Bounded region-feed control loop (pure; unit-testable with a fake
 *  reader). Pulls EXACTLY [totalBytes] from [readChunk] in
 *  <= SCAN_CHUNK_BYTES reads, checking the deadline and the scanner's
 *  UNKNOWN state BETWEEN chunks. [readChunk](buf, maxLen) returns bytes
 *  read, 0 for no progress, negative for EOF. Only bytes actually fed
 *  advance the caller's cursor - a short read is never silently skipped. */
internal enum class RegionFeedResult { DONE, SCANNER_UNKNOWN, SHORT_READ, NO_PROGRESS, DEADLINE }
internal data class RegionFeed(val result: RegionFeedResult, val bytesFed: Long)

internal fun feedRegion(
    scanner: XdiagTraceScanner,
    totalBytes: Long,
    deadlineNs: Long,
    nowNs: () -> Long = System::nanoTime,
    readChunk: (ByteArray, Int) -> Int,
): RegionFeed {
    if (scanner.unknownReason() != null) return RegionFeed(RegionFeedResult.SCANNER_UNKNOWN, 0)
    val buf = ByteArray(XdiagPolicy.SCAN_CHUNK_BYTES)
    var left = totalBytes
    var fed = 0L
    while (left > 0) {
        if (nowNs() >= deadlineNs) return RegionFeed(RegionFeedResult.DEADLINE, fed)
        val want = minOf(buf.size.toLong(), left).toInt()
        val r = readChunk(buf, want)
        if (r < 0) return RegionFeed(RegionFeedResult.SHORT_READ, fed)
        if (r == 0) return RegionFeed(RegionFeedResult.NO_PROGRESS, fed)
        scanner.feed(buf, 0, r)
        fed += r; left -= r
        if (scanner.unknownReason() != null) return RegionFeed(RegionFeedResult.SCANNER_UNKNOWN, fed)
    }
    return RegionFeed(RegionFeedResult.DONE, fed)
}

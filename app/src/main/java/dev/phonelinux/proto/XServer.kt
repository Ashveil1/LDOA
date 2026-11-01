package dev.phonelinux.proto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlin.concurrent.thread

/**
 * Starts the vendored termux-x11 X server in a separate process via
 * app_process + com.termux.x11.CmdEntryPoint, then opens its MainActivity.
 *
 * Verified topology (vendored source + CI):
 * - The server needs XKB_CONFIG_ROOT with real keymap data (XkbData).
 * - libXlorie.so is preloaded via the XSERVER_LIB env hook (vendored patch
 *   c): under extractNativeLibs=true the classloader-resource path upstream
 *   uses would resolve to a compressed in-APK entry that cannot be dlopened.
 * - Socket: $TMPDIR/.X11-unix/X0; guests reach it through a proot bind.
 */
object XServer {
    private const val TAG = "PhoneLinuxX"
    private var process: Process? = null

    /** The guest session process (proot root) of the running desktop.
     *  Tracked so the lifecycle service can watch session end and
     *  stopDesktopMode can tear the session down by identity. */
    @Volatile var sessionProcess: Process? = null

    /** Launch-CONFIRMED process identities for verified teardown. Captured
     *  when this launch's readiness gate passes (the bus pidfile exists by
     *  then): an identity read at stop time cannot guard a PID reused
     *  after the session died (custom-worker review lesson, Phase A
     *  patch 3/8). */
    @Volatile private var sessionRootIdent: PIdent? = null
    @Volatile private var sessionBusIdent: PIdent? = null
    @Volatile private var gnomeSession: Pair<String, Process>? = null

    /** True only while a session whose readiness gate FAILED is retained
     *  for Stop session. openDesktop tears such a session down before
     *  relaunching (retry implies stop): launchOrReuseGnomeSession would
     *  otherwise "reuse" the still-running wedged process, so no new
     *  session script ever runs and the gate times out with
     *  "guest-gnome.log has no new output" (POCO VD 2026-10-31 14:18:
     *  exactly that second failure shape). A HEALTHY retained session
     *  (gate passed) never sets this and is still reused/brought forward. */
    @Volatile private var retainedSessionFailed = false

    /** True only while a session whose readiness gate TIMED OUT with the
     *  process still alive and NO definitive failure evidence is retained:
     *  readiness is UNKNOWN (still starting), not failed. A retry re-gates
     *  THIS session instead of tearing it down - no relaunch-over-starting
     *  (phone bundle 2026-10-31: relaunch-kill-relaunch loop, every retry
     *  reset boot to zero, 3 fresh launches in 5 min incl. a SIGTERM to a
     *  session that had reached the RUNNING phase). Stop session remains
     *  the only teardown path for it. */
    @Volatile internal var retainedSessionStarting = false
        private set

    /** True once THIS session's shell registered (gate pass or the bounded
     *  late-readiness watch). A healthy reuse may only BYPASS the gate when
     *  the gate passed before: identity+bus+X health without a registered
     *  shell would forward to an empty X display. */
    @Volatile private var sessionGatePassed = false

    /** guest-gnome.log offset this session's gate scans from. Kept so a
     *  retry re-gate and the late-readiness watch re-scan the SAME launch
     *  window - an own-bus marker already written passes instantly. */
    @Volatile private var sessionGateLogBase = 0L

    /** Session process the bounded late-readiness watch is on; guards
     *  against stacking a second watch for the same session on retry. */
    @Volatile private var lateWatchFor: Process? = null

    /** Bounded late-readiness window after a gate timeout on a live
     *  session (deadline, never an unconditional infinite poll). */
    private const val LATE_READINESS_BUDGET_MS = 240_000L

    /** Readiness/ownership timeline for the diagnostic export: a bounded
     *  ring of gate decisions with monotonic + wall-clock stamps, so a late
     *  failure dialog can be matched to the exact launch it judged.
     *  Diagnostics only - never read by control flow. */
    internal data class GateEvent(val monoMs: Long, val wallMs: Long, val text: String)
    private val gateTimeline = ArrayDeque<GateEvent>()
    internal fun gateLog(text: String) {
        synchronized(gateTimeline) {
            gateTimeline.addLast(GateEvent(SystemClock.elapsedRealtime(), System.currentTimeMillis(), text))
            while (gateTimeline.size > 128) gateTimeline.removeFirst()
        }
        Log.i(TAG, "gate: " + text)
    }

    /** Diagnostic snapshot for the export ZIP: session/ownership state +
     *  the gate timeline. Diagnostics only; contains no user data. */
    internal fun diagnosticSnapshot(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.append("build_version=").append(BuildConfig.VERSION_NAME).append('\n')
        sb.append("build_git=").append(BuildConfig.GIT_SHA).append('\n')
        sb.append("now_wall_ms=").append(System.currentTimeMillis()).append('\n')
        sb.append("now_mono_ms=").append(SystemClock.elapsedRealtime()).append('\n')
        sb.append("ubuntu_desktop_active=").append(prefs.getBoolean("ubuntu_desktop_active", false)).append('\n')
        sb.append("ubuntu_desktop_degraded=").append(prefs.getBoolean("ubuntu_desktop_degraded", false)).append('\n')
        sb.append("preparing=").append(preparing).append('\n')
        sb.append("xserver_alive=").append(process?.isAlive).append('\n')
        sb.append("gnome_session_alive=").append(gnomeSession?.second?.isAlive).append('\n')
        sb.append("gnome_session_reusable_ubuntu=").append(gnomeSessionActive(Distros.UBUNTU)).append('\n')
        sb.append("retained_session_failed=").append(retainedSessionFailed).append('\n')
        sb.append("retained_session_starting=").append(retainedSessionStarting).append('\n')
        sb.append("session_gate_passed=").append(sessionGatePassed).append('\n')
        sb.append("ownership_generation=").append(ownershipGeneration).append('\n')
        sb.append("session_bus=").append(gnomeSessionBus ?: "(none)").append('\n')
        sb.append("session_root_ident=").append(sessionRootIdent?.let { "pid=" + it.pid + " start=" + it.start + " uid=" + it.uid } ?: "(none)").append('\n')
        sb.append("session_bus_ident=").append(sessionBusIdent?.let { "pid=" + it.pid + " start=" + it.start + " uid=" + it.uid } ?: "(none)").append('\n')
        // GNOME diagnostic sampler file (guest /tmp/ldoa-probes.log on the
        // ubuntu rootfs): presence/size/freshness only - the file itself is
        // exported as logs/ldoa-probes.log by Diagnostics.
        val probes = File(ctx.filesDir, "distros/ubuntu/tmp/ldoa-probes.log")
        sb.append("ldoa_probes_present=").append(probes.isFile).append('\n')
        sb.append("ldoa_probes_bytes=").append(if (probes.isFile) probes.length() else 0).append('\n')
        sb.append("ldoa_probes_mtime=").append(if (probes.isFile) isoTime(probes.lastModified()) else "(none)").append('\n')
        sb.append("-- gate timeline (mono_ms | wall | event) --\n")
        synchronized(gateTimeline) {
            for (e in gateTimeline) sb.append(e.monoMs).append(" | ").append(isoTime(e.wallMs)).append(" | ").append(e.text).append('\n')
        }
        return sb.toString()
    }

    private fun isoTime(wallMs: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).format(java.util.Date(wallMs))

    /** Bus address of the TRACKED session, captured from its own
     *  "gnome-launch: session bus" log line when the gate first sees it.
     *  A retry on the REUSED session gets no fresh bus line, so the gate
     *  matches registration markers against this tracked address instead.
     *  Cross-session acceptance is impossible: the address embeds the
     *  bus guid, unique per dbus-launch. Cleared on fresh launch + stop. */
    @Volatile private var gnomeSessionBus: String? = null

    /** ONE lock for every session/X-server ownership transition: snapshot,
     *  establish (launch, gate-confirm, failure-retain) and clear. The
     *  epoch is only read or written while holding it, which is what makes
     *  stopDesktopMode's "epoch unchanged -> clear" atomic against
     *  establishers: no launch/confirm/retain can interleave between the
     *  check and the clears. Identity-verified kills run OUTSIDE the lock:
     *  they re-verify (starttime, uid) at signal time, so a pid reused
     *  mid-teardown is never signaled. */
    private val ownershipLock = Any()
    private var ownershipGeneration = 0L
    /** Generation at which sessionBusIdent was assigned: a capture that
     *  started in an older generation than the stored identity's never
     *  overwrites a newer world's bus. */
    private var busIdentGeneration = 0L

    /** Test-only seam: invoked inside stopDesktopMode after the ownership
     *  snapshot and before any kill or clear, so a regression test can
     *  interleave a simulated relaunch deterministically. App code never
     *  sets this. */
    @Volatile internal var teardownHookForTest: (() -> Unit)? = null
    @Volatile var preparing = false
        private set
    // In-flight attempt routing: while a start runs, the VISIBLE caller
    // RE-ATTACHES (its callbacks replace these) instead of being refused;
    // completion routes to the latest caller's activity so a recreated
    // MainActivity still receives the outcome (POCO 2026-11-01: the second
    // open got "tap ignored", its preparing overlay never cleared, and the
    // app had no escape - the user was trapped on a spinner).
    @Volatile private var attemptActivity: Activity? = null
    @Volatile private var attemptOnError: ((String) -> Unit)? = null
    @Volatile private var attemptOnStage: ((String) -> Unit)? = null

    /** Simulate an in-flight attempt (regression tests only). */
    internal fun setPreparingForTest(value: Boolean) { preparing = value }

    /** True when a GNOME desktop session for this distro is already alive:
     *  re-entering the desktop then only brings the X11 activity forward
     *  instead of racing a second gnome-session onto :0. */
    fun gnomeSessionActive(d: Distro): Boolean =
        gnomeSession?.let { it.first == d.id && it.second.isAlive } == true

    /** Single tracked launch path shared by desktop entry and restart tests. */
    fun launchOrReuseGnomeSession(ctx: Context, distro: Distro, rootfs: File): Process = synchronized(ownershipLock) {
        val current = gnomeSession
        if (current != null && current.first == distro.id && current.second.isAlive) {
            Log.i(TAG, "GNOME session already running - bringing desktop forward")
            return@synchronized current.second
        }
        Log.i(TAG, "launching GNOME session")
        return@synchronized GuestUbuntu.launchGnomeSession(ctx, rootfs).also {
            gnomeSession = distro.id to it; gnomeSessionBus = null; ownershipGeneration++
            retainedSessionStarting = false; sessionGatePassed = false; lateWatchFor = null
            // Capture the root identity AT SPAWN: the launcher knows exactly
            // which process it started. Capturing only at gate-confirm left
            // every pre-confirm Stop/teardown with NO launch identity -
            // stopDesktopMode skipped its tree walk and destroy()ed the
            // tracer alone, orphaning the guest tree (the same leak class
            // as a name-kill, just quieter). Gate-confirm re-captures below.
            sessionRootIdent = pidOf(it)?.let { pid -> identityOf(pid) }
            if (sessionRootIdent == null) Log.w(TAG,
                "GNOME session root identity NOT captured at spawn: any teardown before gate-confirm degrades to tracer-destroy only (guest tree leaks)")
        }
    }

    /** True once the session root's launch-captured identity
     *  (pid+starttime+uid) exists and an identity-verified teardown can
     *  walk the tree. Read-only test seam for qualified teardown waits. */
    internal fun hasSessionRootIdentity(): Boolean = synchronized(ownershipLock) { sessionRootIdent != null }

    internal class BusIdentRecord(val pid: Int, val startTicks: String, val uid: Int, val nonce: String)

    /** The creation-time identity record the session script wrote right
     *  after spawning THIS launch's bus (GuestUbuntu.kt, .gnome-bus.ident).
     *  Missing or unparseable = no record = never adopt any bus. */
    private fun busIdentRecord(ctx: Context): BusIdentRecord? {
        val f = File(DistroInstaller(ctx).rootfsDir(Distros.UBUNTU), "root/.gnome-bus.ident")
        val text = try { f.readText() } catch (t: Throwable) { return null }
        val m = HashMap<String, String>()
        for (tok in text.trim().split(Regex("\\s+"))) {
            val i = tok.indexOf('=')
            if (i > 0) m[tok.substring(0, i)] = tok.substring(i + 1)
        }
        val pid = m["pid"]?.toIntOrNull() ?: return null
        val ticks = m["startticks"]?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } } ?: return null
        val uid = m["uid"]?.toIntOrNull() ?: return null
        val nonce = m["nonce"]?.takeIf { it.isNotEmpty() } ?: return null
        return BusIdentRecord(pid, ticks, uid, nonce)
    }

    /** THIS launch's session-bus identity, bound by the launcher-written
     *  creation record - or null (preserve, never kill) on ANY failure:
     *   1. the creation record exists and parses (pid, startticks, uid,
     *      nonce all present);
     *   2. the record's uid is ours and the LIVE process identity
     *      (pid+starttime+uid) verifies as ours;
     *   3. the live start ticks EQUAL the record's creation ticks - the
     *      actual binding: a pid reused after the recorded bus died
     *      belongs to a different process, whatever its name or timing;
     *   4. the live cmdline is the session bus shape (dbus-daemon +
     *      --session) - same-uid coincidence is not ownership.
     *  killIdentsVerified re-verifies the identity again before every
     *  signal, so a replacement between capture and signal is never
     *  signaled. Never a by-name adoption of any bus. */
    private fun validatedSessionBusIdent(ctx: Context): PIdent? {
        val rec = busIdentRecord(ctx)
            ?: return null.also { lastBusCaptureRejection = "creation record missing or unparseable" }
        if (rec.uid != android.os.Process.myUid())
            return null.also { lastBusCaptureRejection = "record uid is not ours" }
        val ident = identityOf(rec.pid)
            ?: return null.also { lastBusCaptureRejection = "no live own-uid identity for the record pid" }
        if (ident.start != rec.startTicks)
            return null.also { lastBusCaptureRejection = "live start ticks differ from the creation record (pid reuse)" }
        if (synchronized(ownershipLock) { deniedBusRolePidsForTest.contains(rec.pid) })
            return null.also { lastBusCaptureRejection = "session-bus role denied by the test seam" }
        val cmd = try {
            File("/proc/" + rec.pid + "/cmdline").readText().replace('\u0000', ' ')
        } catch (t: Throwable) {
            return null.also { lastBusCaptureRejection = "live cmdline unreadable" }
        }
        if (!(cmd.contains("dbus-daemon") && cmd.contains("--session")))
            return null.also { lastBusCaptureRejection = "live cmdline is not the session-bus shape" }
        return ident
    }

    /** The nonce THIS launch logged with its session-bus line, read from
     *  bytes after [logBase] (the boundary taken before THIS launch) and
     *  after ITS OWN launch-id line in that window - never whatever
     *  record or tail is latest. Null when this launch's bus line is not
     *  visible: the launch's bus is then unknown and must be preserved. */
    private fun ownLaunchBusNonce(ctx: Context, logBase: Long): String? {
        val f = File(ctx.filesDir, "guest-gnome.log")
        val tail = try {
            if (!f.isFile) return null
            val base = if (f.length() >= logBase) logBase else 0L
            f.readText().substring(base.toInt().coerceAtMost(f.length().toInt()))
        } catch (t: Throwable) { return null }
        val pos = tail.lastIndexOf("gnome-launch: launch id=")
        if (pos < 0) return null
        return tail.substring(pos).lineSequence()
            .lastOrNull { it.contains("gnome-launch: session bus") && it.contains("nonce=") }
            ?.substringAfter("nonce=", "")?.trim()?.takeWhile { !it.isWhitespace() }
    }

    /** Capture the session bus for the launch whose log window starts at
     *  [logBase]: the launcher-written creation record + exact live
     *  tick/uid/role binding (validatedSessionBusIdent) + THIS launch's
     *  OWN logged nonce. Null - preserve, never kill - when the launch's
     *  nonce is unknown or any binding fails. */
    private fun captureOwnLaunchBusIdent(ctx: Context, logBase: Long): PIdent? {
        lastBusCaptureRejection = null
        val nonce = ownLaunchBusNonce(ctx, logBase)
            ?: return null.also { lastBusCaptureRejection = "no own-launch bus nonce line in window (base=" + logBase + ")" }
        val rec = busIdentRecord(ctx)
            ?: return null.also { lastBusCaptureRejection = "creation record missing or unparseable" }
        if (rec.nonce != nonce)
            return null.also { lastBusCaptureRejection = "record nonce does not match the launch-window nonce" }
        val validated = validatedSessionBusIdent(ctx)
        if (validated == null && lastBusCaptureRejection == null)
            lastBusCaptureRejection = "record/live binding failed (uid, liveness, ticks, or bus role)"
        return validated
    }

    /** Launch-confirm bus-identity merge: the capture is bound to the
     *  ORIGINATING launch window ([captureBase]) - on a reused session
     *  that is the original gate base (sessionGateLogBase), on a fresh
     *  launch this call's own logBase, same base the gate/retain/watch
     *  paths use. A null capture on a REUSED session means "no new
     *  launch in that window" - UNCERTAIN, never contradictory: a
     *  still-valid retained identity is KEPT (never clobbered) and no
     *  record is adopted. A null capture on a FRESH launch clears the
     *  previous world's identity (that world is gone). */
    internal fun mergeLaunchConfirmBusIdent(ctx: Context, captureBase: Long, reused: Boolean) {
        synchronized(ownershipLock) {
            val found = captureOwnLaunchBusIdent(ctx, captureBase)
            if (found != null) {
                sessionBusIdent = found
                busIdentGeneration = ownershipGeneration
            } else if (!reused) {
                sessionBusIdent = null
                Log.w(TAG, "session bus identity NOT captured at launch-confirm " +
                    "(no own-launch nonce/record, stale ticks, or reused pid) - stop will preserve, never kill, the bus")
            } else if (sessionBusIdent != null) {
                gateLog("reused session: no new launch in the current call's window - retained bus identity kept (uncertain is never contradictory)")
            } else {
                Log.w(TAG, "reused session: session bus identity unknown " +
                    "(no own-launch nonce/record in the originating window) - stop will preserve, never kill, the bus")
            }
        }
    }

    /** Assign a validated bus identity, guarded by generation: a capture
     *  that began in an OLDER generation than the stored identity's never
     *  overwrites a newer world's bus. True when [ident] was stored. */
    private fun assignBusIdentGuarded(ident: PIdent?, genAtEntry: Long): Boolean =
        synchronized(ownershipLock) {
            if (ident == null || busIdentGeneration > genAtEntry) return@synchronized false
            sessionBusIdent = ident
            busIdentGeneration = genAtEntry
            true
        }

    /** Test seam: capture THIS launch's session-bus identity so a teardown
     *  can end the daemonized bus too (it escapes the parentage walk).
     *  The capture is bound by the launcher-written creation record
     *  (validatedSessionBusIdent) AND by [expectedNonce] - the nonce THIS
     *  launch logged with its session-bus line, so a record left by any
     *  other launch is never adopted by reading the latest tail. Any
     *  missing/ambiguous record, nonce mismatch, or reused pid = NOT
     *  captured (preserve, never kill). */
    internal fun captureSessionBusIdentityForTest(ctx: Context, expectedNonce: String): Boolean {
        lastBusCaptureRejection = null
        if (expectedNonce.isEmpty()) return false.also { lastBusCaptureRejection = "empty expected nonce" }
        val genAtEntry = synchronized(ownershipLock) { ownershipGeneration }
        val rec = busIdentRecord(ctx)
            ?: return false.also { lastBusCaptureRejection = "creation record missing or unparseable" }
        if (rec.nonce != expectedNonce)
            return false.also { lastBusCaptureRejection = "record nonce does not match the expected nonce" }
        val validated = validatedSessionBusIdent(ctx)
        if (validated == null) {
            if (lastBusCaptureRejection == null)
                lastBusCaptureRejection = "record/live binding failed (uid, liveness, ticks, or bus role)"
            return false
        }
        return assignBusIdentGuarded(validated, genAtEntry)
            .also { if (!it) lastBusCaptureRejection = "generation guard: an older-generation capture never overwrites a newer world" }
    }

    /** Test-only FULL ownership-state reset: the bus-ident @After clear
     *  alone left retainedSessionFailed/sessionProcess/root identity/
     *  gate flags leaking across fixture instances (shared statics in
     *  one instrumentation process). App code never calls this. */
    internal fun resetOwnershipStateForTest() = synchronized(ownershipLock) {
        retainedSessionFailed = false
        retainedSessionStarting = false
        sessionGatePassed = false
        lateWatchFor = null
        sessionProcess = null
        sessionRootIdent = null
        sessionBusIdent = null
        busIdentGeneration = 0L
        sessionGateLogBase = 0L
        lastBusCaptureRejection = null
        deniedBusRolePidsForTest.clear()
    }

    /** Test-only seam: deny ONLY the session-bus role binding for one
     *  pid, so the non-bus-role fixture mutates exactly one field of an
     *  otherwise-valid capture (same pid, record, ticks, uid, nonce).
     *  App code never calls this. */
    private val deniedBusRolePidsForTest = mutableSetOf<Int>()
    internal fun denyBusRoleForTest(pid: Int) =
        synchronized(ownershipLock) { deniedBusRolePidsForTest.add(pid) }

    /** Test-only seam: set the STILL-STARTING retention flag directly
     *  (fixtures that simulate a retained starting session). App code
     *  never calls this. */
    internal fun setRetainedSessionStartingForTest(value: Boolean) =
        synchronized(ownershipLock) { retainedSessionStarting = value }

    /** Test-only diagnostics: WHY the last bus-capture attempt rejected
     *  (never WHAT to adopt - the binding rules above own that). Read by
     *  the ProotKillOnExitTest fixtures to make rejection assertions
     *  carry their actual reason. App code never reads this. */
    @Volatile internal var lastBusCaptureRejection: String? = null

    /** Test-only seam: install a bus identity at an explicit generation
     *  (a simulated newer world) so the assignment guard can be exercised.
     *  App code never calls this. */
    internal fun installBusIdentForTest(ident: PIdent?, generation: Long) =
        synchronized(ownershipLock) {
            sessionBusIdent = ident
            busIdentGeneration = generation
        }

    /** True when [p] is the ALREADY-RUNNING tracked session (the reuse
     *  branch of launchOrReuseGnomeSession), not a fresh launch. */
    private fun isReusedSession(distro: Distro, p: Process, wasAlive: Boolean): Boolean =
        wasAlive && p === (gnomeSession?.second) && gnomeSessionActive(distro)

    /** Reuse health for a session that passed its gate earlier. Process
     *  liveness alone is not health: the tracked root must be the SAME
     *  process (pid + start time + uid, so a recycled pid is never "our"
     *  session and can never be signaled by mistake), the session bus
     *  must be alive, and the X server must not be younger than the
     *  session (a restarted X orphaned every guest client - forwarding
     *  there would show an empty display). Returns null when healthy,
     *  else a short diagnosis reason. Never signals or kills anything. */
    internal fun healthyReuseOrReason(ctx: Context, distro: Distro, p: Process): String? {
        val pid = pidOf(p) ?: return "session pid unreadable"
        val snap = synchronized(ownershipLock) { Triple(sessionRootIdent, sessionBusIdent, process) }
        val nowIdent = identityOf(pid) ?: return "session process exited between checks"
        val captured = snap.first ?: return "no launch-time identity captured"
        if (captured.pid != nowIdent.pid || captured.start != nowIdent.start || captured.uid != nowIdent.uid)
            return "session pid " + pid + " identity changed (a recycled pid is not our session)"
        val busPid = sessionBusPid(ctx) ?: return "session bus pid file missing"
        val busNow = identityOf(busPid) ?: return "session bus process gone"
        val busCaptured = snap.second
        if (busCaptured != null && (busCaptured.start != busNow.start || busCaptured.uid != busNow.uid))
            return "session bus pid " + busPid + " identity changed"
        val xs = snap.third
        if (xs == null || !xs.isAlive) return "X server not running"
        val xpid = pidOf(xs) ?: return "X server pid unreadable"
        val xIdent = identityOf(xpid) ?: return "X server identity unreadable"
        val xStart = xIdent.start.toLongOrNull(); val sStart = nowIdent.start.toLongOrNull()
        if (xStart != null && sStart != null && xStart > sStart)
            return "X server restarted after the session launched - its guest clients died with the old server"
        // A live bus daemon is NOT desktop health: gnome-shell must still
        // exist in the session's own process tree, or bypassing the gate
        // would forward the user to a desktop that is no longer there.
        // ACCEPTED GAP: this is process-tree liveness, not a bus-owner
        // check - a shell process that lost org.gnome.Shell ownership but
        // stayed alive still passes. A NameHasOwner probe needs a guest
        // round-trip per reuse (proot exec, seconds + new failure modes),
        // so it is deliberately not on this path.
        if (!treeHasProcessNamed(pid, Regex("gnome-shell")))
            return "gnome-shell gone from the session tree (a live session bus does not imply a live desktop)"
        return null
    }

    /** True when any process in [rootPid]'s live tree has [name] in its
     *  cmdline. Reads /proc directly; signals nothing. */
    private fun treeHasProcessNamed(rootPid: Int, name: Regex): Boolean {
        val all = scanProc()
        if (!all.containsKey(rootPid)) return false
        for (tpid in treeOf(rootPid, all)) {
            val cmd = try { File("/proc/" + tpid + "/cmdline").readText() } catch (t: Throwable) { continue }
            if (name.containsMatchIn(cmd)) return true
        }
        return false
    }

    /** Test-only seam: install session/X state exactly as launch-confirm
     *  would, from caller-owned processes. App code never calls this. */
    internal fun installSessionStateForTest(distro: Distro, session: Process?, xserver: Process?) {
        synchronized(ownershipLock) {
            gnomeSession = session?.let { distro.id to it }
            sessionRootIdent = session?.let { pidOf(it)?.let { pid -> identityOf(pid) } }
            sessionBusIdent = null
            busIdentGeneration = 0L
            process = xserver
        }
    }

    fun tmpDir(ctx: Context): File = File(ctx.filesDir, "usr/tmp")
    fun socketDir(ctx: Context): File = File(tmpDir(ctx), ".X11-unix")

    fun start(ctx: Context) {
        synchronized(ownershipLock) { if (process?.isAlive == true) return }
        XkbData.ensure(ctx)
        socketDir(ctx).mkdirs()
        val lib = File(ctx.applicationInfo.nativeLibraryDir, "libXlorie.so")
        check(lib.isFile) { "libXlorie.so missing from nativeLibraryDir" }
        val log = File(ctx.filesDir, "xserver.log")
        // Same bounded retention as the guest log: keep one previous
        // generation instead of growing forever.
        if (log.isFile && log.length() > 4L * 1024 * 1024) {
            val prev = File(ctx.filesDir, "xserver.prev.log")
            try { prev.delete(); if (!log.renameTo(prev)) Log.w(TAG, "xserver log rotation rename failed") }
            catch (t: Throwable) { Log.w(TAG, "xserver log rotation failed", t) }
        }
        val pb = ProcessBuilder("/system/bin/app_process", "/", "com.termux.x11.CmdEntryPoint", ":0")
        val env = pb.environment()
        env["CLASSPATH"] = ctx.applicationInfo.sourceDir
        env["TMPDIR"] = tmpDir(ctx).absolutePath
        env["XKB_CONFIG_ROOT"] = XkbData.root(ctx).absolutePath
        env["XSERVER_LIB"] = lib.absolutePath
        env["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        // Phone-native mode: never composite the X software cursor sprite
        // (renderer.cpp, settings.gradle.kts vendored patch). Injection and
        // app-drawn text carets are unaffected.
        env["LORIE_HIDE_CURSOR"] = "1"
        // The compositing renderer (lorie renderer.cpp, loaded via LorieView
        // JNI) runs in THIS app process, not in the spawned server, so the
        // server env alone never reaches its per-frame getenv (CI evidence:
        // run 40916848175 shots 07/08 show the cursor composited). Set it
        // here too, before the X11 activity opens and renders its first
        // frame. If setenv fails, degrade to a visible cursor instead of
        // failing the desktop launch.
        try {
            Os.setenv("LORIE_HIDE_CURSOR", "1", true)
        } catch (t: Throwable) {
            Log.w(TAG, "LORIE_HIDE_CURSOR setenv failed; X cursor will stay visible", t)
        }
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        val spawned = pb.start()
        synchronized(ownershipLock) {
            if (process?.isAlive == true) {
                // A concurrent start won: keep the tracked server, drop
                // this duplicate instead of orphaning the field.
                try { spawned.destroy() } catch (t: Throwable) { }
                return
            }
            process = spawned
        }
        Log.i(TAG, "X server spawned; log=$log")
    }

    /** Poll until the session script's alive/fallback marker appears in the
     *  NEW portion of the session log (past logBase), the process dies, or
     *  the budget runs out. Returns the matched marker line, or null. */
    private fun awaitSessionMarker(p: Process, log: File, logBase: Long, budgetMs: Long, vararg markers: String): String? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return null
            if (log.isFile && log.length() > logBase) {
                val fresh = log.readText().substring(logBase.toInt().coerceAtMost(log.length().toInt()))
                val hit = fresh.lineSequence().firstOrNull { line -> markers.any { line.contains(it) } }
                if (hit != null) return hit.trim()
            }
            Thread.sleep(2_000)
        }
        return null
    }

    /** Readiness gate: poll the NEW portion of the session log (past
     *  logBase) until the session script reports the GNOME shell registered
     *  on THIS launch's session bus (bus address must match the bus line
     *  written by the same launch), the process dies, or the budget runs
     *  out. Returns the matched line, or null. */
    internal fun awaitShellRegistered(p: Process, log: File, logBase: Long, budgetMs: Long): String? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return null
            if (log.isFile && log.length() > logBase) {
                val fresh = log.readText().substring(logBase.toInt().coerceAtMost(log.length().toInt()))
                // The launcher appends " nonce=<per-launch>" to the bus line
                // for identity binding (be11508); the registration marker's
                // bus= never carries it. Compare the ADDRESS only, or every
                // fresh launch mismatches and gates out with GNOME running
                // (POCO phone 2026-11-01, bf6798d: marker present at ~+56s,
                // gate still timed out at 210s, desktop never shown).
                val bus = fresh.lineSequence().firstOrNull { it.contains("gnome-launch: session bus ") }
                    ?.substringAfter("gnome-launch: session bus ")?.trim()
                    ?.substringBefore(" nonce=")
                // Log only on change: re-logging every 2s poll flooded the
                // 128-entry gate ring and evicted real events (phone export).
                if (bus != null && bus != gnomeSessionBus) gateLog("session bus captured")
                if (bus != null) gnomeSessionBus = bus
                // Reused session: no fresh bus line - fall back to the
                // tracked address of THIS process's own launch. A marker
                // naming any other bus (stale or foreign session) still
                // fails the equality check.
                val expected = bus ?: gnomeSessionBus
                val hit = fresh.lineSequence().firstOrNull { line ->
                    (line.contains("gnome-launch: shell registered") ||
                        line.contains("gnome-launch: fallback shell registered")) &&
                        (expected == null || line.substringAfter("bus=").trim() == expected)
                }
                if (hit != null && expected != null) return hit.trim()
                // The script's first-phase verdict ("shell missing after
                // 60s") is NOT definitive: the unified bare-shell fallback
                // may still register. The fallback-phase verdict IS - fail
                // fast instead of burning the whole budget.
                if (fresh.contains("gnome-launch: fallback shell missing after 90s")) {
                    gateLog("script reported definitive fallback failure - fail fast")
                    return null
                }
            }
            Thread.sleep(2_000)
        }
        return null
    }

    /** Open the desktop fail-CLOSED: the X11 activity only launches when a
     *  desktop session actually started; every failure path reports through
     *  onError with detail instead of opening a silent black screen
     *  (real-device report 2026-10-29: tap -> screen unchanged, cause
     *  invisible). onStage receives one-line progress for the preparing UI. */
    fun openDesktop(activity: Activity, distro: Distro,
                    onStage: (String) -> Unit = {},
                    onError: (String) -> Unit = {}) {
        if (preparing) {
            // Re-attach, never refuse: follow the in-flight attempt so its
            // stage lines and outcome reach the screen the user is actually
            // looking at. The single-attempt guard stands - no second
            // session is launched.
            attemptActivity = activity
            attemptOnError = onError
            attemptOnStage = onStage
            onStage("a desktop start is already in progress - following it now")
            return
        }
        preparing = true
        attemptActivity = activity
        attemptOnError = onError
        attemptOnStage = onStage
        thread(name = "xserver-start") {
            val ctx = activity.applicationContext
            // Stage lines route to the LATEST attached caller, not the
            // (possibly destroyed) screen that began the attempt.
            val stage: (String) -> Unit = { s -> attemptOnStage?.invoke(s) }
            var launched = false
            var failure: String? = null
            try {
                gateLog("openDesktop begin distro=" + distro.id)
                if (teardownRetainedFailedSession(ctx)) gateLog("tore down retained FAILED session before relaunch")
                stage("Starting X server")
                start(ctx)
                val installer = DistroInstaller(ctx)
                if (installer.isInstalled(distro)) {
                    val rootfs = installer.rootfsDir(distro)
                    when (distro.pkgMgr) {
                        PkgMgr.APK -> {
                            if (!GuestDesktop.hasDesktopShell(rootfs)) {
                                stage("Installing desktop shell (jwm/pcmanfm/st)")
                                var add = GuestDesktop.provisionDesktopShell(ctx, rootfs)
                                if (add.exitCode != 0) {
                                    Thread.sleep(5_000)
                                    add = GuestDesktop.provisionDesktopShell(ctx, rootfs)
                                }
                                Log.i(TAG, "desktop shell provision exit=" + add.exitCode)
                                if (add.exitCode != 0) failure =
                                    "Desktop shell install failed (exit " + add.exitCode + ")\n" + (add.stdout + add.stderr).takeLast(1200)
                            }
                            if (failure == null && GuestDesktop.hasDesktopShell(rootfs)) {
                                stage("Launching desktop session")
                                val p = GuestDesktop.launchDesktopSession(ctx, rootfs)
                                // Alpine's session script has no alive
                                // marker; bounded gate = still alive after
                                // an 8s grace (a dead exec/proot surfaces
                                // inside that window).
                                var grace = 0
                                while (grace < 8 && p.isAlive) { Thread.sleep(1_000); grace++ }
                                launched = p.isAlive
                                if (launched) {
                                    synchronized(ownershipLock) {
                                        sessionProcess = p
                                        sessionRootIdent = pidOf(p)?.let { identityOf(it) }
                                        ownershipGeneration++
                                    }
                                }
                                if (!launched) failure = "Desktop session died within " + grace + "s of launch"
                            } else if (failure == null) {
                                failure = "Desktop shell unavailable after install; desktop would be empty"
                            }
                        }
                        PkgMgr.APT -> {
                            // Ubuntu: GNOME (B). Feasibility on our lorie
                            // stack is unproven; guest-gnome.log is the
                            // evidence trail.
                            if (!GuestUbuntu.hasGnome(rootfs)) {
                                Log.i(TAG, "GNOME not provisioned; apt install gnome-shell et al")
                                val add = GuestUbuntu.provisionGnome(ctx, rootfs) { line -> stage(line) }
                                Log.i(TAG, "gnome provision exit=" + add.exitCode)
                                if (add.exitCode != 0) failure =
                                    "GNOME package install failed (exit " + add.exitCode + ")\n" + (add.stdout + add.stderr).takeLast(1200)
                            }
                            if (failure == null && GuestUbuntu.hasGnome(rootfs)) {
                                stage("Launching GNOME session")
                                // Gate on SESSION EVIDENCE, not the spawn
                                // event: the session script logs its alive
                                // marker ~20s in; only log lines written
                                // after THIS launch count (the log is
                                // append-only across retries).
                                val gnomeLog = File(ctx.filesDir, "guest-gnome.log")
                                val logBase = if (gnomeLog.isFile) gnomeLog.length() else 0L
                                val wasAlive = gnomeSessionActive(distro)
                                val p = launchOrReuseGnomeSession(ctx, distro, rootfs)
                                val reused = isReusedSession(distro, p, wasAlive)
                                // Process-alive alone is NOT reuse health:
                                // verify the tracked root is the SAME
                                // process (pid + start time), its session
                                // bus is alive, and the X server is not
                                // younger than the session (a restarted X
                                // orphaned every guest client).
                                val reuseReason = if (reused) healthyReuseOrReason(ctx, distro, p) else null
                                if (!reused) synchronized(ownershipLock) { sessionGateLogBase = logBase }
                                // Gate bypass also requires the gate PASSED before:
                                // identity+bus+X health without a registered shell
                                // would forward to an empty X display.
                                val bypassGate = reused && reuseReason == null && sessionGatePassed
                                gateLog(if (bypassGate) "reusing HEALTHY session (identity+bus+X verified, gate previously passed)"
                                    else if (reused && reuseReason == null) "reusing STILL-STARTING session (identity+bus+X healthy, gate not yet passed) - re-gating the same launch window"
                                    else if (reused) "reuse UNHEALTHY: " + reuseReason + " - fail closed, session NOT touched"
                                    else "fresh session launch, logBase=" + logBase)
                                // Readiness gate (P0/P2): only a shell that
                                // REGISTERED on THIS launch's session bus
                                // passes - "gnome-session alive" and the bare
                                // shell fallback line are not success (run
                                // 40864638283: fallback marker passed, shell
                                // stalled, black screen). Bus address must
                                // match this launch's own bus line so an owner
                                // from a stale session can never satisfy it.
                                // A HEALTHY reused session writes no new log
                                // lines, so the freshness-gated poll below
                                // could only burn the full budget and then
                                // false-fail - and retainFailedSessionOwnership
                                // would then mark a WORKING desktop failed,
                                // letting the next "Try again" kill it
                                // (POCO 2026-10-31 18:20: desktop open and
                                // usable, then "did not start / guest-gnome.log
                                // has no new output" on returning to the app).
                                // A HEALTHY reused session passed its own gate
                                // at launch: bring it forward without re-gating.
                                // An unhealthy one never bypasses.
                                // An unhealthy reuse is diagnosed, never
                                // force-restarted: the poll could never
                                // pass (no new log lines), so fail closed
                                // NOW with the reason, retain ownership for
                                // Stop session, and touch nothing.
                                // A STILL-STARTING reused session is
                                // RE-GATED over its original launch window
                                // (never torn down, never force-restarted):
                                // the own-bus marker written since the
                                // first attempt passes instantly.
                                val marker = when {
                                    bypassGate -> "reused healthy session (identity+bus+X verified)"
                                    reused && reuseReason != null -> null
                                    reused -> awaitShellRegistered(p, gnomeLog, sessionGateLogBase, 210_000)
                                    else -> awaitShellRegistered(p, gnomeLog, logBase, 210_000)
                                }
                                // A registration line with an already-DEAD
                                // session root is never READY: log evidence
                                // alone does not make a live desktop.
                                val gatePassed = marker != null && (bypassGate || p.isAlive)
                                gateLog(if (gatePassed) "gate PASS: " + (marker ?: "").take(120) else "gate TIMEOUT/fail after budget, process " + (if (p.isAlive) "alive" else "exited"))
                                if (gatePassed && marker != null) {
                                    Log.i(TAG, "GNOME session evidence: " + marker)
                                    ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
                                        .edit().putBoolean("ubuntu_desktop_active", true).apply()
                                    launched = true
                                    // A fallback-shell marker is DEGRADED success, never full
                                    // GNOME: recorded distinctly, never silently reported as a
                                    // healthy session (product readiness keeps the two apart).
                                    val degraded = marker.contains("fallback shell registered")
                                    ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
                                        .edit().putBoolean("ubuntu_desktop_degraded", degraded).apply()
                                    if (degraded) Log.w(TAG, "DEGRADED desktop: bare-shell fallback registered - full GNOME session unavailable this launch")
                                    synchronized(ownershipLock) {
                                        retainedSessionFailed = false
                                        retainedSessionStarting = false
                                        sessionGatePassed = true
                                        lateWatchFor = null
                                        sessionProcess = p
                                        ownershipGeneration++
                                        sessionRootIdent = pidOf(p)?.let { identityOf(it) }
                                        // Bind THIS world's bus from the ORIGINATING launch window:
                                        // a reused session launched nothing in this call, so its
                                        // window is sessionGateLogBase (the base the gate, retain
                                        // and watch paths already use); a fresh launch uses its
                                        // own logBase. A null capture on reuse is UNCERTAIN,
                                        // never contradictory - the merge keeps a still-valid
                                        // retained identity instead of clobbering it.
                                        mergeLaunchConfirmBusIdent(ctx, if (reused) sessionGateLogBase else logBase, reused)
                                    }
                                } else {
                                    val gateBase = if (reused) sessionGateLogBase else logBase
                                    if (reused && reuseReason != null) {
                                        failure = "The desktop session on this device is not healthy (" + reuseReason +
                                            "). Nothing was killed - Stop session tears it down cleanly."
                                        // Retain ownership so Stop session tears it
                                        // down by identity.
                                        retainFailedSessionOwnership(ctx, p, gateBase)
                                    } else {
                                        val tail = if (gnomeLog.isFile && gnomeLog.length() > gateBase)
                                            gnomeLog.readText().substring(gateBase.toInt().coerceAtMost((gnomeLog.length()).toInt())).takeLast(1500)
                                            else "(guest-gnome.log has no new output)"
                                        when (classifyGateNull(p, gnomeLog, gateBase)) {
                                            GateNullClass.STILL_STARTING -> {
                                                // Readiness UNKNOWN, not failed:
                                                // retain for re-gate + bounded late
                                                // watch; nothing killed, retry never
                                                // relaunches over this session.
                                                retainStartingSessionOwnership(ctx, p, gateBase)
                                                startLateReadinessWatch(ctx, p, gnomeLog, gateBase)
                                                failure = "Ubuntu desktop is STILL STARTING: no shell on the session bus after 210s, " +
                                                    "but the session process is alive and readiness is unknown (NOT a failure). Nothing was killed. " +
                                                    "The app keeps watching for up to 4 more minutes and marks the session ready if the shell registers late. " +
                                                    "'Try again' keeps waiting on THIS session (no restart); 'Stop session' ends it. Log tail:\n" + tail
                                            }
                                            else -> {
                                                failure = "GNOME shell did not register on its session bus within 210s incl. fallback window (process " +
                                                    (if (p.isAlive) "still running" else "exited") + "). Log tail:\n" + tail
                                                // Retain ownership of the still-running session so
                                                // Stop session can tear it down by identity.
                                                retainFailedSessionOwnership(ctx, p, gateBase)
                                            }
                                        }
                                    }
                                }
                            } else if (failure == null) {
                                failure = "GNOME unavailable after package install; desktop would be empty"
                            }
                        }
                    }
                } else {
                    failure = distro.displayName + " is not installed"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "X server start failed", t)
                failure = "X server failed: " + (t.message ?: t.javaClass.simpleName)
            }
            finishAttempt(ctx, launched, failure)
        }
    }

    /** Attempt completion, routed to the LATEST attached caller (the one
     *  the user is actually looking at; a recreated MainActivity re-attaches
     *  through the preparing path above). Extracted for direct regression
     *  testing. launched=true opens the X view on the attached activity, or
     *  from the app context with NEW_TASK when that screen is gone - a ready
     *  desktop is never stranded behind a stale activity reference. */
    internal fun finishAttempt(ctx: Context, launched: Boolean, failure: String?) {
        Handler(Looper.getMainLooper()).post {
            preparing = false
            val a = attemptActivity
            val oe = attemptOnError
            attemptActivity = null
            attemptOnError = null
            attemptOnStage = null
            if (launched) {
                // L1: persistent background notification + manual
                // wakelock toggle while the session lives (accepted
                // requirements File v2). Service watches session end.
                SessionService.start(ctx)
                if (a != null && !a.isFinishing && !a.isDestroyed) {
                    a.startActivity(Intent(a, com.termux.x11.MainActivity::class.java))
                } else {
                    ctx.startActivity(Intent(ctx, com.termux.x11.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } else {
                Log.e(TAG, "desktop open failed-closed: " + failure)
                oe?.invoke(failure ?: "unknown desktop start failure")
            }
        }
    }

    /**
     * End the desktop session (user stop): verified teardown of the guest
     * session tree + X server. Every signal is re-verified against the
     * (starttime, uid) pair captured at snapshot time - a reused PID is
     * never signaled. The session bus (dbus-daemon reparents to init and
     * escapes the parentage walk) is captured by pidfile identity under
     * the same rule. Leftovers are logged, never hidden.
     */
    fun stopDesktopMode(ctx: Context) {
        // Snapshot ownership ONCE at entry: kills AND clears below apply to
        // this snapshot. The epoch guard then keeps a concurrent
        // relaunch/retain that landed mid-teardown from losing its handles
        // (previously the unconditional clears could erase a newly confirmed
        // session's ownership: orphaned session, re-armed double-launch).
        var genAtEntry = 0L
        var rootIdent: PIdent? = null
        var busIdent: PIdent? = null
        var rootProc: Process? = null
        synchronized(ownershipLock) {
            genAtEntry = ownershipGeneration
            // An explicit user Stop cancels the late-readiness watch AT
            // ENTRY: the watch must never promote (bumping the generation
            // and making this Stop bail) while a Stop of its session is in
            // flight. Stop always ends its owned generation; only a NEWER
            // user launch supersedes a Stop (the epoch guard below).
            if (lateWatchFor != null && (sessionProcess === lateWatchFor || gnomeSession?.second === lateWatchFor)) {
                retainedSessionStarting = false
                lateWatchFor = null
            }
            rootIdent = sessionRootIdent
            busIdent = sessionBusIdent
            rootProc = sessionProcess
        }
        // Val snapshots: the hook may swap the fields (a simulated
        // relaunch); this teardown must proceed on the OLD snapshot, and
        // only vals smart-cast here.
        val rootSnap = rootIdent
        val busSnap = busIdent
        val procSnap = rootProc
        val idents = LinkedHashMap<Int, PIdent>()
        teardownHookForTest?.invoke()
        if (rootSnap != null) {
            val cur = identityOf(rootSnap.pid)
            if (cur != null && cur.start == rootSnap.start && cur.uid == rootSnap.uid) {
                // Root identity matches THIS launch: walk its live tree.
                for (pid in treeOf(rootSnap.pid, scanProc())) identityOf(pid)?.let { idents[pid] = it }
            } else {
                Log.i(TAG, "stopDesktopMode: session root already exited or its PID was reused - tree walk skipped " +
                    "(reparented non-bus leftovers are L2 orphan-handling scope, not silently killed)")
            }
        } else {
            Log.i(TAG, "stopDesktopMode: no launch-captured root identity - tree walk skipped")
        }
        if (busSnap != null) idents[busSnap.pid] = busSnap
        val left = killIdentsVerified(idents)
        if (left.isNotEmpty()) Log.w(TAG, "stopDesktopMode: leftover pids after verified teardown: " + left)
        // Destroy the SNAPSHOT process, never the field: the field may
        // already belong to a newer session.
        if (procSnap != null) { try { procSnap.destroy() } catch (t: Throwable) { } }
        // Check-and-clear atomically under the same lock establishers use:
        // no launch/confirm/retain can interleave between the epoch check
        // and the clears. If the epoch moved, ownership belongs to a NEW
        // session - leave its handles, the shared X server and the pref.
        synchronized(ownershipLock) {
            if (ownershipGeneration != genAtEntry) {
                Log.w(TAG, "stopDesktopMode: ownership changed hands during teardown " +
                    "(concurrent launch/retain) - new session handles, X server and active-pref left intact")
                return
            }
            sessionProcess = null
            sessionRootIdent = null
            sessionBusIdent = null
            busIdentGeneration = 0L
            gnomeSession = null
            gnomeSessionBus = null
            retainedSessionFailed = false
            retainedSessionStarting = false
            sessionGatePassed = false
            lateWatchFor = null
            val xs = process
            if (xs != null) { try { xs.destroy() } catch (t: Throwable) { }; process = null }
            ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
                .edit().putBoolean("ubuntu_desktop_active", false)
                .putBoolean("ubuntu_desktop_degraded", false).apply()
        }
        Log.i(TAG, "stopDesktopMode: desktop session ended by user")
    }

    /** Failure-path ownership retention:
     *  a session whose readiness gate failed is still RUNNING; without
     *  these handles it is an orphan no teardown can reach. Identities
     *  are captured NOW (same rule as the launch-confirmed capture): an
     *  identity read at stop time cannot guard a reused PID. */
    /** openDesktop pre-launch step (extracted for direct regression
     *  testing): a retained FAILED session is torn down by identity before
     *  any relaunch - retry implies stop. Without this,
     *  launchOrReuseGnomeSession "reuses" the still-running wedged process
     *  and no new session script ever runs (POCO VD 2026-10-31 14:18
     *  second-attempt failure shape). A HEALTHY retained session (gate
     *  passed) never sets the flag and is still reused/brought forward.
     *  Returns true when a teardown ran. */
    internal fun teardownRetainedFailedSession(ctx: Context): Boolean {
        if (retainedSessionFailed && (sessionProcess != null || gnomeSession != null)) {
            Log.i(TAG, "openDesktop: retained FAILED session present - verified teardown before relaunch (retry implies stop)")
            stopDesktopMode(ctx)
            return true
        }
        return false
    }

    internal fun retainFailedSessionOwnership(ctx: Context, p: Process, logBase: Long) {
        if (p.isAlive) {
            gateLog("retained FAILED session ownership (Stop session can tear it down)")
            synchronized(ownershipLock) {
                retainedSessionFailed = true
                sessionProcess = p
                ownershipGeneration++
                sessionRootIdent = pidOf(p)?.let { identityOf(it) }
                sessionBusIdent = captureOwnLaunchBusIdent(ctx, logBase).also { found ->
                    if (found == null) Log.w(TAG, "retain-failed: session bus NOT captured (no own-launch nonce/record or stale) - preserved, never killed")
                    else busIdentGeneration = ownershipGeneration
                }
            }
        }
    }

    /** Gate-null classification: a null gate result is STILL_STARTING only
     *  while the session process lives AND the session script has not
     *  reported its definitive fallback verdict. Readiness unknown is
     *  never reported as failure. */
    internal enum class GateNullClass { DEFINITIVE_EXITED, DEFINITIVE_SCRIPT, STILL_STARTING }
    internal fun classifyGateNull(p: Process, log: File, logBase: Long): GateNullClass {
        if (!p.isAlive) return GateNullClass.DEFINITIVE_EXITED
        val fresh = if (log.isFile && log.length() > logBase)
            log.readText().substring(logBase.toInt().coerceAtMost(log.length().toInt())) else ""
        if (fresh.contains("gnome-launch: fallback shell missing after 90s")) return GateNullClass.DEFINITIVE_SCRIPT
        return GateNullClass.STILL_STARTING
    }

    /** STILL-STARTING ownership retention: same identity-capture rule as
     *  retainFailedSessionOwnership, but the session is retained for
     *  RE-GATE (retry attaches to it), never for teardown-on-retry. */
    internal fun retainStartingSessionOwnership(ctx: Context, p: Process, logBase: Long) {
        if (p.isAlive) {
            gateLog("retained STILL-STARTING session ownership (retry re-gates it; Stop session tears it down)")
            synchronized(ownershipLock) {
                retainedSessionStarting = true
                sessionProcess = p
                sessionGateLogBase = logBase
                ownershipGeneration++
                sessionRootIdent = pidOf(p)?.let { identityOf(it) }
                sessionBusIdent = captureOwnLaunchBusIdent(ctx, logBase).also { found ->
                    if (found == null) Log.w(TAG, "retain-starting: session bus NOT captured (no own-launch nonce/record or stale) - preserved, never killed")
                    else busIdentGeneration = ownershipGeneration
                }
            }
        }
    }

    /** Bounded late-readiness watch after a STILL-STARTING gate timeout:
     *  keeps polling THIS launch's log window for up to
     *  LATE_READINESS_BUDGET_MS; when the shell registers late the session
     *  is promoted to READY (next open brings it forward instantly).
     *  Deadline-bounded, daemon, exits early when the process dies - never
     *  an unconditional infinite poll. One watch per session process. */
    private fun startLateReadinessWatch(ctx: Context, p: Process, log: File, logBase: Long) {
        synchronized(ownershipLock) {
            if (lateWatchFor === p) return
            lateWatchFor = p
        }
        thread(name = "gnome-late-readiness", isDaemon = true) {
            // Chunked poll: between chunks the watch checks its own
            // cancellation marker so a user Stop / re-gate pass / fresh
            // launch (all clear lateWatchFor under the lock) ends it
            // promptly instead of polling the full 240s.
            var hit: String? = null
            var remaining = LATE_READINESS_BUDGET_MS
            var cancelled = false
            while (remaining > 0 && p.isAlive) {
                val chunk = if (remaining < 10_000L) remaining else 10_000L
                hit = awaitShellRegistered(p, log, logBase, chunk)
                if (hit != null) break
                remaining -= chunk
                if (synchronized(ownershipLock) { lateWatchFor !== p }) { cancelled = true; break }
            }
            synchronized(ownershipLock) { if (lateWatchFor === p) lateWatchFor = null }
            if (cancelled) {
                gateLog("late-readiness watch cancelled (session stopped or superseded)")
            } else if (hit != null && p.isAlive) {
                var promoted = false
                synchronized(ownershipLock) {
                    // Only promote when the watched process is still the
                    // tracked STILL-STARTING session (a Stop or fresh
                    // launch in between moves ownership; generation bumps
                    // make a concurrent stopDesktopMode bail, preserving
                    // the now-healthy session - fail closed).
                    if ((sessionProcess === p || gnomeSession?.second === p) && retainedSessionStarting) {
                        retainedSessionStarting = false
                        sessionGatePassed = true
                        sessionProcess = p
                        ownershipGeneration++
                        sessionRootIdent = pidOf(p)?.let { identityOf(it) }
                        sessionBusIdent = captureOwnLaunchBusIdent(ctx, logBase).also { found ->
                            if (found == null) Log.w(TAG, "late-readiness promotion: session bus NOT captured (no own-launch nonce/record or stale) - preserved, never killed")
                            else busIdentGeneration = ownershipGeneration
                        }
                        promoted = true
                    }
                }
                if (promoted) {
                    val degraded = hit.contains("fallback shell registered")
                    ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
                        .edit().putBoolean("ubuntu_desktop_active", true)
                        .putBoolean("ubuntu_desktop_degraded", degraded).apply()
                    gateLog("late readiness: shell registered after the gate budget - session READY" +
                        (if (degraded) " (DEGRADED fallback shell)" else "") + "; next open brings it forward")
                }
            } else {
                gateLog("late-readiness watch ended without registration (process " +
                    (if (p.isAlive) "alive" else "exited") + ") - nothing killed; Stop session remains available")
            }
        }
    }

    private fun sessionBusPid(ctx: Context): Int? = try {
        val f = File(DistroInstaller(ctx).rootfsDir(Distros.UBUNTU), "root/.gnome-bus.pid")
        if (f.isFile) f.readText().trim().toIntOrNull() else null
    } catch (t: Throwable) { null }

    private fun pidOf(p: Process): Int? = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (t: Throwable) { Log.e(TAG, "pid reflection failed", t); null }

    private class PInfo(val ppid: Int, val start: String)

    private fun scanProc(): Map<Int, PInfo> {
        val out = HashMap<Int, PInfo>()
        val dir = File("/proc").listFiles { f -> f.name.matches(Regex("[0-9]+")) } ?: return out
        for (f in dir) {
            try {
                val stat = File(f, "stat").readText()
                val close = stat.lastIndexOf(')')
                if (close < 0) continue
                val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
                if (rest.size < 20) continue
                out[f.name.toInt()] = PInfo(rest[1].toInt(), rest[19])
            } catch (t: Throwable) { /* vanished mid-scan */ }
        }
        return out
    }

    private fun treeOf(root: Int, all: Map<Int, PInfo>): Set<Int> {
        val kids = HashMap<Int, MutableList<Int>>()
        for ((pid, info) in all) kids.getOrPut(info.ppid) { mutableListOf() }.add(pid)
        val out = LinkedHashSet<Int>()
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            out.addAll(frontier)
            frontier = frontier.flatMap { kids[it] ?: emptyList() }.filter { it !in out }
        }
        return out
    }

    internal class PIdent(val pid: Int, val start: String, val uid: Int)

    /** Identity of one live process, or null if gone or NOT ours (all
     *  guest processes run as the app uid). */
    private fun identityOf(pid: Int): PIdent? {
        val info = scanProc()[pid] ?: return null
        val uid = try { Os.stat("/proc/" + pid).st_uid } catch (t: Throwable) { return null }
        if (uid != android.os.Process.myUid()) return null
        return PIdent(pid, info.start, uid)
    }

    /** TERM the captured identities (launch-confirmed root tree + the
     *  launch-confirmed reparented session bus), verify, KILL survivors,
     *  verify empty. Before EVERY signal the PID's current (starttime,
     *  uid) must match the LAUNCH-captured identity - a stale pidfile or
     *  reused PID is never signaled. Returns surviving pids (empty =
     *  clean). */
    private fun killIdentsVerified(idents: Map<Int, PIdent>): List<Int> {
        fun signal(sig: Int) {
            for (id in idents.values) {
                val cur = identityOf(id.pid)
                if (cur != null && cur.start == id.start && cur.uid == id.uid) {
                    try { android.os.Process.sendSignal(id.pid, sig) } catch (t: Throwable) { }
                }
            }
        }
        fun survivors(): List<Int> = idents.values.filter { id ->
            val cur = identityOf(id.pid)
            cur != null && cur.start == id.start && cur.uid == id.uid
        }.map { it.pid }
        signal(OsConstants.SIGTERM)
        Thread.sleep(3_000)
        if (survivors().isEmpty()) return emptyList()
        signal(OsConstants.SIGKILL)
        Thread.sleep(2_000)
        return survivors()
    }
}

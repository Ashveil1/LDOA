package dev.phonelinux.proto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
    @Volatile var preparing = false
        private set

    /** True when a GNOME desktop session for this distro is already alive:
     *  re-entering the desktop then only brings the X11 activity forward
     *  instead of racing a second gnome-session onto :0. */
    fun gnomeSessionActive(d: Distro): Boolean =
        gnomeSession?.let { it.first == d.id && it.second.isAlive } == true

    /** Single tracked launch path shared by desktop entry and restart tests. */
    @Synchronized
    fun launchOrReuseGnomeSession(ctx: Context, distro: Distro, rootfs: File): Process {
        val current = gnomeSession
        if (current != null && current.first == distro.id && current.second.isAlive) {
            Log.i(TAG, "GNOME session already running - bringing desktop forward")
            return current.second
        }
        Log.i(TAG, "launching GNOME session")
        return GuestUbuntu.launchGnomeSession(ctx, rootfs).also { gnomeSession = distro.id to it }
    }

    fun tmpDir(ctx: Context): File = File(ctx.filesDir, "usr/tmp")
    fun socketDir(ctx: Context): File = File(tmpDir(ctx), ".X11-unix")

    @Synchronized
    fun start(ctx: Context) {
        if (process?.isAlive == true) return
        XkbData.ensure(ctx)
        socketDir(ctx).mkdirs()
        val lib = File(ctx.applicationInfo.nativeLibraryDir, "libXlorie.so")
        check(lib.isFile) { "libXlorie.so missing from nativeLibraryDir" }
        val log = File(ctx.filesDir, "xserver.log")
        val pb = ProcessBuilder("/system/bin/app_process", "/", "com.termux.x11.CmdEntryPoint", ":0")
        val env = pb.environment()
        env["CLASSPATH"] = ctx.applicationInfo.sourceDir
        env["TMPDIR"] = tmpDir(ctx).absolutePath
        env["XKB_CONFIG_ROOT"] = XkbData.root(ctx).absolutePath
        env["XSERVER_LIB"] = lib.absolutePath
        env["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        process = pb.start()
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
    private fun awaitShellRegistered(p: Process, log: File, logBase: Long, budgetMs: Long): String? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return null
            if (log.isFile && log.length() > logBase) {
                val fresh = log.readText().substring(logBase.toInt().coerceAtMost(log.length().toInt()))
                val bus = fresh.lineSequence().firstOrNull { it.contains("gnome-launch: session bus ") }
                    ?.substringAfter("gnome-launch: session bus ")?.trim()
                val hit = fresh.lineSequence().firstOrNull { line ->
                    line.contains("gnome-launch: shell registered") &&
                        (bus == null || line.substringAfter("bus=").trim() == bus)
                }
                if (hit != null && bus != null) return hit.trim()
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
            onStage("desktop start already in progress - tap ignored")
            return
        }
        preparing = true
        thread(name = "xserver-start") {
            val ctx = activity.applicationContext
            var launched = false
            var failure: String? = null
            try {
                onStage("Starting X server")
                start(ctx)
                val installer = DistroInstaller(ctx)
                if (installer.isInstalled(distro)) {
                    val rootfs = installer.rootfsDir(distro)
                    when (distro.pkgMgr) {
                        PkgMgr.APK -> {
                            if (!GuestDesktop.hasDesktopShell(rootfs)) {
                                onStage("Installing desktop shell (jwm/pcmanfm/st)")
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
                                onStage("Launching desktop session")
                                val p = GuestDesktop.launchDesktopSession(ctx, rootfs)
                                // Alpine's session script has no alive
                                // marker; bounded gate = still alive after
                                // an 8s grace (a dead exec/proot surfaces
                                // inside that window).
                                var grace = 0
                                while (grace < 8 && p.isAlive) { Thread.sleep(1_000); grace++ }
                                launched = p.isAlive
                                if (launched) {
                                    sessionProcess = p
                                    sessionRootIdent = pidOf(p)?.let { identityOf(it) }
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
                                val add = GuestUbuntu.provisionGnome(ctx, rootfs) { line -> onStage(line) }
                                Log.i(TAG, "gnome provision exit=" + add.exitCode)
                                if (add.exitCode != 0) failure =
                                    "GNOME package install failed (exit " + add.exitCode + ")\n" + (add.stdout + add.stderr).takeLast(1200)
                            }
                            if (failure == null && GuestUbuntu.hasGnome(rootfs)) {
                                onStage("Launching GNOME session")
                                // Gate on SESSION EVIDENCE, not the spawn
                                // event: the session script logs its alive
                                // marker ~20s in; only log lines written
                                // after THIS launch count (the log is
                                // append-only across retries).
                                val gnomeLog = File(ctx.filesDir, "guest-gnome.log")
                                val logBase = if (gnomeLog.isFile) gnomeLog.length() else 0L
                                val p = launchOrReuseGnomeSession(ctx, distro, rootfs)
                                // Readiness gate (P0/P2): only a shell that
                                // REGISTERED on THIS launch's session bus
                                // passes - "gnome-session alive" and the bare
                                // shell fallback line are not success (run
                                // 40864638283: fallback marker passed, shell
                                // stalled, black screen). Bus address must
                                // match this launch's own bus line so an owner
                                // from a stale session can never satisfy it.
                                val marker = awaitShellRegistered(p, gnomeLog, logBase, 60_000)
                                if (marker != null) {
                                    Log.i(TAG, "GNOME session evidence: " + marker)
                                    ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
                                        .edit().putBoolean("ubuntu_desktop_active", true).apply()
                                    launched = true
                                    sessionProcess = p
                                    sessionRootIdent = pidOf(p)?.let { identityOf(it) }
                                    sessionBusIdent = sessionBusPid(ctx)?.let { bp ->
                                        identityOf(bp).also { found ->
                                            if (found == null) Log.w(TAG, "bus pid " + bp + " unreadable at launch-confirm; stop will skip the bus")
                                        }
                                    }
                                } else {
                                    val tail = if (gnomeLog.isFile && gnomeLog.length() > logBase)
                                        gnomeLog.readText().substring(logBase.toInt().coerceAtMost((gnomeLog.length()).toInt())).takeLast(1500)
                                        else "(guest-gnome.log has no new output)"
                                    failure = "GNOME shell did not register on its session bus in 60s (process " +
                                        (if (p.isAlive) "still running" else "exited") + "). Log tail:\n" + tail
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
            val f = failure
            Handler(Looper.getMainLooper()).post {
                preparing = false
                if (launched) {
                    // L1: persistent background notification + manual
                    // wakelock toggle while the session lives (accepted
                    // requirements File v2). Service watches session end.
                    SessionService.start(ctx)
                    activity.startActivity(Intent(activity, com.termux.x11.MainActivity::class.java))
                } else {
                    Log.e(TAG, "desktop open failed-closed: " + f)
                    onError(f ?: "unknown desktop start failure")
                }
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
        val rootIdent = sessionRootIdent
        val busIdent = sessionBusIdent
        val idents = LinkedHashMap<Int, PIdent>()
        if (rootIdent != null) {
            val cur = identityOf(rootIdent.pid)
            if (cur != null && cur.start == rootIdent.start && cur.uid == rootIdent.uid) {
                // Root identity matches THIS launch: walk its live tree.
                for (pid in treeOf(rootIdent.pid, scanProc())) identityOf(pid)?.let { idents[pid] = it }
            } else {
                Log.i(TAG, "stopDesktopMode: session root already exited or its PID was reused - tree walk skipped " +
                    "(reparented non-bus leftovers are L2 orphan-handling scope, not silently killed)")
            }
        } else {
            Log.i(TAG, "stopDesktopMode: no launch-captured root identity - tree walk skipped")
        }
        if (busIdent != null) idents[busIdent.pid] = busIdent
        val left = killIdentsVerified(idents)
        if (left.isNotEmpty()) Log.w(TAG, "stopDesktopMode: leftover pids after verified teardown: " + left)
        val root = sessionProcess
        if (root != null) { try { root.destroy() } catch (t: Throwable) { } }
        sessionProcess = null
        sessionRootIdent = null
        sessionBusIdent = null
        val xs = process
        if (xs != null) { try { xs.destroy() } catch (t: Throwable) { }; process = null }
        ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
            .edit().putBoolean("ubuntu_desktop_active", false).apply()
        Log.i(TAG, "stopDesktopMode: desktop session ended by user")
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

    private class PIdent(val pid: Int, val start: String, val uid: Int)

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

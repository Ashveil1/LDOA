package dev.phonelinux.proto

import android.content.Context
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * E1 (bounded): PRESENTATION-BRIDGE SMOKE on the EXISTING product lorie :0
 * ============================================================
 * Test-only scope: three client arms on the exact vendored server ALREADY
 * SHIPPING, NO server changes (no second lorie instance, no -disable-dri3):
 *   V = vulkaninfo + vkcube  (lavapipe via MESA_VK_WSI_DEBUG=sw)
 *   G = glxinfo -B + glxgears (zink-on-lavapipe via
 *       MESA_LOADER_DRIVER_OVERRIDE=zink over the GLX DRISWRast stub)
 *   E = eglinfo + es2gears_x11 (zink via platform_surfaceless + X11 WSI)
 * plus a no-override glxinfo baseline for context.
 *
 * POSITIVE PROOF PER ARM (narrowed per review):
 *   (a) an ENUMERATED device/renderer string (recorded verbatim, never
 *       predeclared as expected), AND
 *   (b) ANIMATED PIXELS OF THE CLIENT'S OWN WINDOW: the client's window id
 *       is read from the server's own window tree (xwininfo -root -tree),
 *       then that WINDOW (not the full root) is dumped twice 3s apart
 *       (xwd -id reads the X server's framebuffer). A full-root checksum can
 *       move for unrelated reasons (clock, cursor, other damage) and is
 *       kept only as context; the window-scoped cksum pair is the liveness
 *       signal. Shot.save frame pairs give human-checkable visual frames.
 * The window-scoped pair measures the presentation bridge independent of
 * whether the Android UI is visible.
 *
 * OUTCOMES (never silently skipped): per arm one of
 *   tool-missing / probe-fail / not-mapped / static / animated
 * emitted as grep-friendly markers plus an E1-BRIDGE-RESULT summary.
 * Hard failure only on harness-integrity gaps (fixture could not install,
 * server :0 unreachable after a test-owned start).
 *
 * OWNERSHIP (review-hardened):
 *   - Clients: proot direct exec via Proot.runGuest, no guest shell. The
 *     Android-side LAUNCH-ROOT identity (pid + starttime + uid) is captured
 *     at start; teardown signals the whole descendant TREE, and EVERY
 *     signal is preceded by an identity re-read (a reused or foreign pid is
 *     skipped, never signalled). Process.destroy() alone is NOT trusted to
 *     reap proot descendants. Same identity model as the product's private
 *     stopDesktopMode helpers, duplicated here (owner: helpers private).
 *   - Server: a live :0 owned by a session this test did not start is used
 *     READ-ONLY and never signalled. A :0 this test starts is a TEST-OWNED
 *     mirror of the product launch (same argv/env, own Process handle) torn
 *     down via destroyOwnedTree on that handle ONLY - never the product
 *     stopDesktopMode, which would also walk any lane-session tree held in
 *     the product singleton that this test did not start. No raw signals
 *     of any kind touch a foreign server.
 *   - No repository/CI/product changes; fixture via runtime apt with
 *     --no-install-recommends (matches existing fixture pattern).
 *
 * Non-goals: device verdicts (x86 CI software stack only), mutter's own
 * context (a client context is never mutter's), fps claims.
 * Scope note (review): E1 exercises ONLY the software-WSI SHM subset
 * (lavapipe sw WSI + zink on the GLX/EGL swrast platform). It says nothing
 * about the zero-copy pixmap-import path; no claim that it plumbs later
 * GPU routes beyond this tested subset.
 */
@RunWith(AndroidJUnit4::class)
class E1PresentationBridgeTest {

    private val tag = "E1PresentationBridge"

    // ---------------------------------------------------------------
    // owned-process identity (mirrors the product's private helpers)
    // ---------------------------------------------------------------

    private data class PIdent(val pid: Int, val start: String, val uid: Int)

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

    /** Identity of one live process, or null if gone or NOT ours (all
     *  processes this test launches run as the app uid). */
    private fun identityOf(pid: Int): PIdent? {
        val info = scanProc()[pid] ?: return null
        val uid = try { Os.stat("/proc/" + pid).st_uid } catch (t: Throwable) { return null }
        if (uid != android.os.Process.myUid()) return null
        return PIdent(pid, info.start, uid)
    }

    private fun pidOf(p: Process): Int? = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (t: Throwable) { Log.e(tag, "pid reflection failed", t); null }
    /** Whole live tree under [rootPid] (inclusive) as pid -> start-time
     *  captured at discovery; EVERY signal re-compares this start. */
    private fun treePids(rootPid: Int): Map<Int, String> {
        val procs = scanProc()
        val out = LinkedHashMap<Int, String>()
        var frontier = listOf(rootPid)
        while (frontier.isNotEmpty()) {
            val next = ArrayList<Int>()
            for (p in frontier) {
                val info = procs[p] ?: continue
                out[p] = info.start
                for ((pid, pi) in procs) {
                    if (pi.ppid == p && !out.containsKey(pid) && !next.contains(pid)) next.add(pid)
                }
            }
            frontier = next
        }
        return out
    }

    /** Tear down the OWNED tree launched as [root]: identity of the launch
     *  root must still match its launch-captured identity; every member is
     *  re-identified immediately before EVERY signal and skipped when the
     *  identity changed (reused pid) or is not ours. TERM pass, short
     *  settle, KILL pass on survivors, final verification logged as a
     *  marker. Never touches anything outside the captured tree. */
    private fun destroyOwnedTree(root: Process, launchRootIdent: PIdent?, name: String) {
        if (launchRootIdent == null) {
            Log.w(tag, "E1-TEARDOWN name=" + name + " mode=root-handle-only (no launch identity)")
            try { root.destroy() } catch (t: Throwable) { /* already gone */ }
            return
        }
        val current = identityOf(launchRootIdent.pid)
        if (current != launchRootIdent) {
            Log.w(tag, "E1-TEARDOWN name=" + name + " root-identity-changed - tree left alone")
            try { root.destroy() } catch (t: Throwable) { /* already gone */ }
            return
        }
        // Children BEFORE root (explicit teardown order): treePids returns
        // BFS from the root, so reverse it - deepest descendants first, the
        // launch root last - in BOTH passes.
        val tree = treePids(launchRootIdent.pid).entries.toList().asReversed()
        // TERM pass: per-member start-time re-read immediately before EACH
        // signal; a reused pid (start mismatch) is skipped, never signalled.
        for ((pid, start) in tree) {
            val ident = identityOf(pid) ?: continue
            if (ident.start != start) continue
            if (pid == launchRootIdent.pid && ident != launchRootIdent) continue
            try { Os.kill(pid, 15) } catch (t: Throwable) { /* gone */ }
        }
        Thread.sleep(700)
        // KILL pass on survivors, same per-member re-identification, still
        // children-before-root. Survivor pids are captured for the marker.
        var survivors = 0
        val survivorList = mutableListOf<Int>()
        for ((pid, start) in tree) {
            val ident = identityOf(pid) ?: continue
            if (ident.start != start) continue
            survivors++
            survivorList += pid
            try { Os.kill(pid, 9) } catch (t: Throwable) { /* gone */ }
        }
        Thread.sleep(300)
        val remainingIdents = treePids(launchRootIdent.pid).keys.mapNotNull { identityOf(it) }
        Log.i(tag, "E1-TEARDOWN name=" + name + " tree=" + tree.size +
            " kill-pass-targets=" + survivors + " survivors=" + survivorList +
            " remaining=" + remainingIdents.size +
            (if (remainingIdents.isNotEmpty()) " REMAINING-IDENTS=" + remainingIdents else ""))
    }

    // ---------------------------------------------------------------
    // proot direct exec (no guest shell), from the exact shipped pattern
    // ---------------------------------------------------------------

    private data class GuestRun(val exitCode: Int, val stdout: String, val stderr: String)

    /** Mirrors the product Proot.kt env contract: without PROOT_LOADER /
     *  PROOT_TMP_DIR the Termux-built proot cannot exec or dies mid-syscall
     *  (documented in Proot.kt). LD_LIBRARY_PATH resolves libtalloc etc. */
    private fun prootEnv(app: Context, pb: ProcessBuilder) {
        val nd = app.applicationInfo.nativeLibraryDir
        val env = pb.environment()
        env["PROOT_LOADER"] = File(nd, "libproot_loader.so").absolutePath
        env["PROOT_LOADER_32"] = File(nd, "libproot_loader32.so").absolutePath
        env["LD_LIBRARY_PATH"] = nd
        env["PROOT_TMP_DIR"] = Proot.prootTmpDir(app).absolutePath
    }

    private fun guest(
        ctx: Context,
        rootfs: File,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long = 120
    ): GuestRun {
        val pb = ProcessBuilder(argv)
        prootEnv(ctx, pb)
        for ((k, v) in env) pb.environment()[k] = v
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val tOut = Thread { proc.inputStream.copyTo(out) }
        val tErr = Thread { proc.errorStream.copyTo(err) }
        tOut.start(); tErr.start()
        val rootIdent = pidOf(proc)?.let { identityOf(it) }
        val done = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) destroyOwnedTree(proc, rootIdent, "guest:" + argv.last())
        tOut.join(3000); tErr.join(3000)
        return GuestRun(if (done) proc.exitValue() else -1, out.toString(), err.toString())
    }

    private fun runGuest(
        ctx: Context,
        rootfs: File,
        guestCmd: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long = 120
    ): GuestRun {
        val proot = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
        val argv = mutableListOf(
            proot, "-0", "--link2symlink", "--sysvipc", "--kill-on-exit",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", ctx.applicationInfo.nativeLibraryDir,
            "-b", rootfs.absolutePath + "/tmp:/dev/shm",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "DISPLAY=:0"
        )
        for ((k, v) in env) argv.add(k + "=" + v)
        argv.addAll(guestCmd)
        return guest(ctx, rootfs, argv, emptyMap(), timeoutSec)
    }

    // ---------------------------------------------------------------
    // E1 arms
    // ---------------------------------------------------------------

    private data class Arm(
        val name: String,
        val env: Map<String, String>,
        val probeCmd: List<String>,
        val animCmd: List<String>,
        val animNeedle: String
    )

    private val arms = listOf(
        Arm(
            name = "V",
            env = mapOf("MESA_VK_WSI_DEBUG" to "sw"),
            probeCmd = listOf("vulkaninfo", "--summary"),
            animCmd = listOf("vkcube"),
            animNeedle = "vkcube"
        ),
        Arm(
            name = "G",
            env = mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "zink"),
            probeCmd = listOf("glxinfo", "-B"),
            animCmd = listOf("glxgears"),
            animNeedle = "glxgears"
        ),
        Arm(
            name = "E",
            env = mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "zink"),
            probeCmd = listOf("eglinfo"),
            animCmd = listOf("es2gears_x11"),
            animNeedle = "es2gears"
        )
    )

    // JUnit timeout: any unbounded guest/X-server wait becomes an
    // attributed failure with the stuck stack, never a silent stall.
    @Test(timeout = 1_200_000)
    fun e1PresentationBridge() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("presentationBridge") != "1") {
            Log.i(tag, "E1: skipped - run with -e presentationBridge 1")
            return
        }

        val installer = DistroInstaller(app)
        if (!installer.isInstalled(Distros.UBUNTU)) {
            // Independent fixture: E1 needs only the BASE rootfs (proot guest
            // probes: xdpyinfo/glxinfo/vulkaninfo/xwd + the test-owned lorie
            // mirror) - never the GNOME package set. Install base here rather
            // than depending on earlier lane steps (run 41052060565 declared
            // E1-invalid on this precondition).
            Log.i(tag, "E1: base rootfs absent - self-installing via DistroInstaller (base only, no GNOME)")
            var last: InstallState = InstallState.NotInstalled
            val ok = kotlinx.coroutines.runBlocking { installer.install(Distros.UBUNTU) { last = it } }
            assertTrue("E1 invalid: base ubuntu rootfs install failed at state=" + last +
                " - environment/fixture gap, not bridge evidence", ok)
        }
        val rootfs = installer.rootfsDir(Distros.UBUNTU)

        // Fixture (runtime-only, never in the manifest): evidence tools.
        // --no-install-recommends per repo convention.
        val apt = runGuest(app, rootfs, listOf(
            "apt-get", "install", "-y", "--no-install-recommends",
            "mesa-utils", "vulkan-tools", "x11-apps",
            "mesa-vulkan-drivers", "libgl1-mesa-dri"
        ), timeoutSec = 600)
        Log.i(tag, "E1: fixture apt exit=" + apt.exitCode)
        assertEquals("fixture apt failed (see E1 logcat)", 0, apt.exitCode)
        // Prerequisite provision check: apt exit 0 is not proof the probe
        // tools resolve; verify each by name WITH its outcome logged.
        val tools = listOf("xdpyinfo", "glxinfo", "vulkaninfo", "xwd", "xwininfo")
        val missing = mutableListOf<String>()
        for (tool in tools) {
            val w = runGuest(app, rootfs, listOf("which", tool), timeoutSec = 20)
            Log.i(tag, "E1: provision tool=" + tool + " exit=" + w.exitCode + " path=" + w.stdout.trim())
            if (w.exitCode != 0) missing += tool
        }
        assertTrue("E1 invalid: probe tools missing after fixture apt: " + missing +
            " - environment/fixture gap, not bridge evidence", missing.isEmpty())
        // Driver candidate check: E1's scope is the software-WSI subset
        // (lavapipe sw WSI + zink on swrast GLX). If neither candidate
        // driver is present, no arm can produce bridge evidence - declare
        // the fixture gap up front instead of failing mid-arm.
        val vk = runGuest(app, rootfs, listOf("/bin/sh", "-c",
            "vulkaninfo --summary 2>/dev/null | grep -iE 'llvmpipe|lavapipe|deviceName' | head -5"), timeoutSec = 60)
        val gl = runGuest(app, rootfs, listOf("/bin/sh", "-c",
            "glxinfo -B 2>/dev/null | grep -iE 'renderer string|Device:' | head -3"), timeoutSec = 60)
        Log.i(tag, "E1: driver candidates vulkan=[" + vk.stdout.trim().replace("\n", " | ") +
            "] glx=[" + gl.stdout.trim().replace("\n", " | ") + "]")
        assertTrue("E1 invalid: no software driver candidate (lavapipe/llvmpipe absent from " +
            "vulkaninfo) - environment/fixture gap, not bridge evidence",
            vk.stdout.contains("llvmpipe") || vk.stdout.lowercase().contains("lavapipe"))

        // Server :0: reuse a live one READ-ONLY, else start a test-owned one.
        var serverProc: Process? = null
        var serverIdent: PIdent? = null
        var xready = false
        for (attempt in 1..2) {
            val probe = runGuest(app, rootfs, listOf("xdpyinfo"), timeoutSec = 20)
            xready = probe.stdout.contains("name of display")
            if (xready) break
            if (attempt == 1) {
                // Pre-spawn free/socket checks (never delete unowned files):
                // a stale :0 socket or lock means a server WAS here - its
                // owner is unprovable, so abort comparison-invalid instead
                // of racing or cleaning it.
                val sockDir = XServer.socketDir(app)
                val stale = sockDir.listFiles()?.filter {
                    it.name == "X0" || it.name == "X0-lock" || it.name.startsWith(".X0") } ?: emptyList()
                assertTrue("E1 invalid: :0 unreachable yet stale socket/lock entries exist " +
                    "(left untouched, owner unprovable): " + stale.map { it.name },
                    stale.isEmpty())
                // TEST-OWNED mirror of XServer.start's exact argv/env, own
                // Process handle + launch identity. Teardown is
                // destroyOwnedTree on THIS handle only; the product
                // stopDesktopMode is deliberately NOT used (it would also
                // walk any lane-session tree captured in the product
                // singleton that this test did not start).
                XkbData.ensure(app)
                XServer.socketDir(app).mkdirs()
                val lib = File(app.applicationInfo.nativeLibraryDir, "libXlorie.so")
                check(lib.isFile) { "libXlorie.so missing from nativeLibraryDir" }
                val spb = ProcessBuilder("/system/bin/app_process", "/",
                    "com.termux.x11.CmdEntryPoint", ":0")
                val senv = spb.environment()
                senv["CLASSPATH"] = app.applicationInfo.sourceDir
                senv["TMPDIR"] = File(app.filesDir, "usr/tmp").absolutePath
                senv["XKB_CONFIG_ROOT"] = XkbData.root(app).absolutePath
                senv["XSERVER_LIB"] = lib.absolutePath
                senv["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir
                senv["LORIE_HIDE_CURSOR"] = "1"
                val slog = File(app.filesDir, "e1-xserver.log")
                if (slog.isFile) slog.delete()
                spb.redirectErrorStream(true)
                spb.redirectOutput(ProcessBuilder.Redirect.appendTo(slog))
                serverProc = spb.start()
                serverIdent = pidOf(serverProc)?.let { identityOf(it) }
                Log.i(tag, "E1: test-owned lorie launch-root=" + serverIdent)
                Thread.sleep(3000)
            }
        }
        assertTrue("lorie :0 unreachable after test-owned start", xready)

        try {
            // Baseline context: stock GLX probe, no overrides.
            val base = runGuest(app, rootfs, listOf("glxinfo", "-B"), timeoutSec = 60)
            Log.i(tag, "E1-BASELINE glxinfo-B exit=" + base.exitCode)
            for (line in base.stdout.lines()) {
                if (line.contains("renderer", true) || line.contains("vendor", true)) {
                    Log.i(tag, "E1-BASELINE " + line.trim())
                }
            }

            val results = LinkedHashMap<String, String>()
            for (arm in arms) {
                results[arm.name] = runArm(app, rootfs, arm)
            }

            val summary = results.entries.joinToString(" ") { it.key + "=" + it.value }
            Log.i(tag, "E1-BRIDGE-RESULT " + summary)
        } finally {
            val sp = serverProc
            if (sp != null) destroyOwnedTree(sp, serverIdent, "test-owned-lorie")
        }
    }

    /** One arm. Returns the outcome word; logs full evidence lines. */
    private fun runArm(app: Context, rootfs: File, arm: Arm): String {
        val e = "E1-ARM " + arm.name + " "

        // (0) tool availability - a missing package is an informative
        // outcome, never a crash and never a guessed binary.
        for (tool in arm.probeCmd + arm.animCmd + listOf("xwd", "xwininfo")) {
            val w = runGuest(app, rootfs, listOf("which", tool), arm.env, timeoutSec = 20)
            if (w.exitCode != 0 || w.stdout.isBlank()) {
                Log.i(tag, e + "outcome=tool-missing tool=" + tool)
                return "tool-missing"
            }
        }

        // (1) enumeration probe: record renderer/device verbatim.
        val probe = runGuest(app, rootfs, arm.probeCmd, arm.env, timeoutSec = 90)
        val renderer = probe.stdout.lines()
            .filter {
                it.contains("deviceName", true) ||
                    it.contains("renderer", true) ||
                    it.contains("OpenGL version", true)
            }
            .joinToString(" | ") { it.trim() }
            .take(300)
        Log.i(tag, e + "probe-exit=" + probe.exitCode + " renderer=[" + renderer + "]")
        if (probe.exitCode != 0 || renderer.isBlank()) {
            Log.i(tag, e + "outcome=probe-fail stderr=" + probe.stderr.take(200))
            return "probe-fail"
        }

        // (2) animated client: direct exec, launch-root identity captured.
        val pb = ProcessBuilder(
            File(app.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath,
            "-0", "--link2symlink", "--sysvipc", "--kill-on-exit",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", app.applicationInfo.nativeLibraryDir,
            "-b", rootfs.absolutePath + "/tmp:/dev/shm",
            "-b", XServer.socketDir(app).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "DISPLAY=:0"
        )
        for ((k, v) in arm.env) pb.command().add(k + "=" + v)
        pb.command().addAll(arm.animCmd)
        prootEnv(app, pb)
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val launchRootIdent = pidOf(proc)?.let { identityOf(it) }
        Log.i(tag, e + "launch-root=" + (launchRootIdent?.toString() ?: "unresolved"))

        try {
            // up to 10s for the client's OWN window to appear in the
            // server's window tree (xwininfo -root -tree), id captured.
            var winId: String? = null
            var mapped = false
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10000) {
                val line = runGuest(
                    app, rootfs,
                    listOf("sh", "-c", "xwininfo -root -tree | grep -i " + arm.animNeedle + " | head -1"),
                    timeoutSec = 20
                ).stdout
                winId = Regex("0x[0-9a-fA-F]+").find(line)?.value
                if (winId != null) { mapped = true; break }
                if (!isProcAlive(proc)) break
                Thread.sleep(500)
            }
            if (!mapped || winId == null) {
                val alive = isProcAlive(proc)
                Log.i(tag, e + "outcome=not-mapped client-alive=" + alive)
                return "not-mapped"
            }
            Log.i(tag, e + "window-id=" + winId)

            // (3) liveness of THE CLIENT'S WINDOW: two xwd dumps of that
            // window id, 3s apart, straight from the server's framebuffer.
            // A visual frame pair is saved for human/CI inspection.
            val dumpDir = File(app.filesDir, "e1")
            dumpDir.mkdirs()
            // Shot.save captures the ANDROID UI, which shows the guest
            // client only if the X11 activity is foreground - context
            // frames only. The decisive visual evidence is the RETAINED
            // window-scoped xwd pair below (rendered+inspected at harvest).
            val shot0 = "e1-" + arm.name.lowercase() + "-t0"
            val shot1 = "e1-" + arm.name.lowercase() + "-t1"
            try { Shot.save(shot0) } catch (t: Throwable) {
                Log.w(tag, e + "shot-t0-failed " + t.message)
            }
            val w0 = dumpWindow(app, rootfs, winId, File(dumpDir, arm.name + "-w0.xwd"))
            Thread.sleep(3000)
            val w1 = dumpWindow(app, rootfs, winId, File(dumpDir, arm.name + "-w1.xwd"))
            try { Shot.save(shot1) } catch (t: Throwable) {
                Log.w(tag, e + "shot-t1-failed " + t.message)
            }

            // full-root checksum pair kept as CONTEXT ONLY (clock/cursor/
            // unrelated damage can move it; it is not the liveness signal).
            val rootCk = rootContextCksums(app, rootfs)

            val aliveAfter = isProcAlive(proc)
            val stillMapped = runGuest(
                app, rootfs,
                listOf("sh", "-c", "xwininfo -root -tree | grep -i " + arm.animNeedle),
                timeoutSec = 20
            ).stdout.contains(arm.animNeedle, true)
            val animated = w0 != null && w1 != null && w0.cksum != w1.cksum && aliveAfter
            Log.i(
                tag,
                e + "win-cksum0=" + (w0?.cksum ?: "n/a") +
                    " win-cksum1=" + (w1?.cksum ?: "n/a") +
                    " win-gz0=" + (w0?.gzSize ?: -1) +
                    " win-gz1=" + (w1?.gzSize ?: -1) +
                    " root-ck0=" + rootCk.first + " root-ck1=" + rootCk.second +
                    " alive=" + aliveAfter + " mapped=" + stillMapped +
                    " shots=" + shot0 + "," + shot1 + "(android-ui-context)"
            )
            return when {
                !aliveAfter -> {
                    Log.i(tag, e + "outcome=probe-fail client-died")
                    "probe-fail"
                }
                !stillMapped -> {
                    Log.i(tag, e + "outcome=not-mapped window-lost")
                    "not-mapped"
                }
                animated -> {
                    Log.i(tag, e + "outcome=animated (window-cksum pair differs; REQUIRES " +
                        "rendered pixel inspection of the retained " + arm.name +
                        "-w0/w1.xwd frames at harvest before any animated claim)")
                    "animated"
                }
                else -> {
                    Log.i(tag, e + "outcome=static client-alive=true window-still-mapped=" + stillMapped)
                    "static"
                }
            }
        } finally {
            destroyOwnedTree(proc, launchRootIdent, "arm-" + arm.name + "-client")
        }
    }

    private data class WinDump(val cksum: String, val gzSize: Int)

    /** xwd dump of ONE window id to a host file; returns cksum and a
     *  blank-screen heuristic (gzipped size). */
    private fun dumpWindow(app: Context, rootfs: File, winId: String, outFile: File): WinDump? {
        val tmp = "/tmp/e1-" + winId + ".xwd"
        val r = runGuest(app, rootfs, listOf("xwd", "-id", winId, "-silent", "-out", tmp), timeoutSec = 60)
        if (r.exitCode != 0) {
            Log.w(tag, "E1: xwd -id " + winId + " failed: " + r.stderr.take(150))
            return null
        }
        val pulled = runGuest(app, rootfs, listOf("sh", "-c", "cksum " + tmp + "; wc -c < " + tmp), timeoutSec = 30)
        val src = File(rootfs, "tmp/e1-" + winId + ".xwd")
        val bytes = src.readBytes()
        outFile.writeBytes(bytes) // RETAINED for rendered pixel inspection at harvest
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(bytes) }
        src.delete()
        return WinDump(pulled.stdout.lines().firstOrNull() ?: "n/a", gz.size())
    }

    /** Context-only root checksum pair (NOT the liveness signal). */
    private fun rootContextCksums(app: Context, rootfs: File): Pair<String, String> {
        fun once(): String {
            val r = runGuest(
                app, rootfs,
                listOf("sh", "-c", "xwd -root -silent | cksum"),
                timeoutSec = 60
            )
            return r.stdout.trim().ifBlank { "n/a" }
        }
        val a = once(); Thread.sleep(1000); val b = once()
        return a to b
    }

    private fun isProcAlive(proc: Process): Boolean = try {
        proc.exitValue(); false
    } catch (t: IllegalThreadStateException) { true }
}

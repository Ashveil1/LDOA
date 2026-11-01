package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** VirGL spike, stage 2: GUEST renderer proof. No X, no GNOME - the
 *  eglprobe binary (ci/virgl/eglprobe.c, built by the lane for BOTH
 *  guest arches) creates a GLES2 context on EGL_PLATFORM_SURFACELESS_MESA
 *  inside the provisioned rootfs and prints GL-RENDERER. This is the
 *  mechanism mesa's own CI uses for virpipe (EGL_PLATFORM=surfaceless +
 *  GALLIUM_DRIVER=virpipe vs virgl_test_server: mesa
 *  .gitlab-ci/piglit/piglit-traces.sh + src/gallium/drivers/virgl/ci/
 *  gitlab-ci-inc.yml). eglprobe capability-checks
 *  EGL_MESA_platform_surfaceless in the client extension string BEFORE
 *  relying on it (stage=10 error otherwise).
 *
 *  ABI contract: the guest rootfs arch follows the device ABI
 *  (Distros.UBUNTU: amd64 on the x86_64 emulator, arm64 on phone), so
 *  the probe variant is selected from the PROVISIONED ROOTFS' ELF header
 *  (rootfs /bin/sh e_machine), never assumed; the chosen binary's own
 *  header and the guest libc interpreter are verified BEFORE exec.
 *
 *  Arms: (A) default guest mesa (no opt-in): renderer asserted non-empty
 *  and NOT virgl, classification logged from the actual string; (B)
 *  opt-in: host vtest server up, GALLIUM_DRIVER=virpipe, renderer
 *  asserted "virgl (...)" - transport proof only; a software host
 *  backend inside the parentheses is NOT a GPU-offload claim. Arm B also
 *  exercises the owned stop-with-client path: eglprobe --hold keeps the
 *  EGL connection open so the server has a forked worker at Stop. If arm
 *  B is never reached (arm A failure), a STUB virpipe report records the
 *  reason - a missing report is always a failure, never masked.
 *  Emulator results are EMULATOR results, not Adreno results. */
@RunWith(AndroidJUnit4::class)
class VirglProbeTest {
    private val TAG = "VirglProbeTest"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val d = Distros.UBUNTU
    private val rootfs get() = DistroInstaller(ctx).rootfsDir(d)
    private val flag get() = File(ctx.filesDir, ".ldoa-virgl-optin")
    private val probeLocal get() = File(rootfs, "tmp/eglprobe")
    private val probeReportDefault get() = File(ctx.filesDir, "virgl-probe-default.txt")
    private val probeReportVirgl get() = File(ctx.filesDir, "virgl-probe-virpipe.txt")
    private val probeReportNofork get() = File(ctx.filesDir, "virgl-probe-nofork.txt")

    @After fun cleanup() {
        try { XServer.stopVirglServerForTest("probe-cleanup") } catch (t: Throwable) { }
    }

    /** ELF e_machine of a file's header: 0x3E = x86-64, 0xB7 = AArch64.
     *  Returns the raw machine int, or -1 when unreadable/not ELF. */
    private fun elfMachine(f: File): Int {
        val b = try { f.inputStream().use { it.readNBytes(20) } } catch (t: Throwable) { return -1 }
        if (b.size < 20 || b[0] != 0x7f.toByte() || b[1] != 'E'.code.toByte()) return -1
        return (b[18].toInt() and 0xff) or ((b[19].toInt() and 0xff) shl 8)
    }

    /** Stage the lane-built probe binary, selected by the PROVISIONED
     *  ROOTFS' ELF machine (never by assumption), with the chosen
     *  binary's own header and the guest libc interpreter verified
     *  before any exec. Fails LOUDLY on any mismatch. */
    private fun stageProbe(): String {
        val extDir = ctx.getExternalFilesDir(null)
        // Resolve guest symlink chains WITHOUT host-following (usrmerge:
        // bin -> usr/bin is relative, but a guest-ABSOLUTE link would
        // resolve against the Android host - rebase under the rootfs).
        val rootSh = VirglTestCommon.resolveGuest(rootfs, File(rootfs, "bin/sh"))
        val guestMach = elfMachine(rootSh)
        val guestArch = when (guestMach) {
            0x3E -> "x86_64"
            0xB7 -> "aarch64"
            else -> throw AssertionError("rootfs /bin/sh ELF machine unreadable/unsupported: " + guestMach +
                " (" + rootSh.absolutePath + ") - cannot select a probe binary")
        }
        val interp = if (guestArch == "x86_64") "lib64/ld-linux-x86-64.so.2" else "lib/ld-linux-aarch64.so.1"
        // ubuntu-base 24.04.5 amd64: lib64 -> usr/lib64 and
        // usr/lib64/ld-linux-x86-64.so.2 -> ../lib/x86_64-linux-gnu/
        // ld-linux-x86-64.so.2 are ALL relative, but resolve explicitly
        // so a future absolute link cannot falsely resolve on the host.
        val interpResolved = VirglTestCommon.resolveGuest(rootfs, File(rootfs, interp))
        assertTrue("guest libc interpreter missing in rootfs: " + interp +
            " (resolved: " + interpResolved.absolutePath + ")",
            VirglTestCommon.guestPresent(interpResolved))
        val ext = File(extDir, "eglprobe-" + guestArch)
        assertTrue("eglprobe-" + guestArch + " not pushed by the lane to " + ext.absolutePath +
            " - build/push step missing or failed", ext.isFile)
        val probeMach = elfMachine(ext)
        assertEquals("staged probe ELF machine does not match the rootfs (probe=" +
            probeMach + " rootfs=" + guestMach + ")", guestMach, probeMach)
        // Stage INSIDE the rootfs: proot executes guest ELFs through its
        // loader (PROOT_LOADER -> libproot_loader.so from nativeLibraryDir)
        // which READS and maps the file - no kernel exec of app-writable
        // files is ever requested (same class as every provisioned rootfs
        // binary that CI already runs). filesDir ProcessBuilder exec, by
        // contrast, is SELinux-denied on targetSdk 34 - that route is used
        // ONLY for jniLibs binaries (hostprobe).
        probeLocal.writeBytes(ext.readBytes())
        android.system.Os.chmod(probeLocal.absolutePath, 493) // 0755
        return guestArch
    }

    private fun runProbeArm(title: String, reportFile: File, virgl: Boolean,
                            holdSecs: Int, sock: File?, bootBudgetMs: Long = 120_000L): Proot.Result {
        val binds = mutableListOf<Pair<String, String>>()
        if (virgl) {
            assertNotNull("virgl socket missing for probe bind (server not up?)", sock)
            assertTrue("virgl socket file missing: " + sock!!.absolutePath, sock.exists())
            binds.add(sock.absolutePath to "/tmp/.virgl_test")
        }
        val env = mutableMapOf("EGL_PLATFORM" to "surfaceless")
        if (virgl) env["GALLIUM_DRIVER"] = "virpipe"
        val cmd = mutableListOf("/tmp/eglprobe")
        if (holdSecs > 0) cmd.addAll(listOf("--hold", holdSecs.toString()))
        val result = Proot.runGuest(ctx, rootfs, cmd, extraBinds = binds, extraEnv = env,
            fakeRoot = false, timeoutMs = (holdSecs * 1000L) + bootBudgetMs)
        VirglTestCommon.writeProbeReport(reportFile, title, cmd, env, binds, result)
        return result
    }

    @Test fun guestRendererProofBaselineThenVirpipe() {
        val savedFlag = if (flag.isFile) flag.readText() else null
        try {
            val guestArch = stageProbe()

            // Arm A: default guest mesa (opt-in flag OFF).
            flag.delete()
            val baseResult = runProbeArm("baseline (default guest mesa, no opt-in, arch=" + guestArch + ")",
                probeReportDefault, virgl = false, holdSecs = 0, sock = null)
            assertEquals("baseline eglprobe failed (see " + probeReportDefault.name + ")",
                0, baseResult.exitCode)
            val baseline = VirglTestCommon.rendererOf(baseResult)
            assertNotNull("baseline probe printed no GL-RENDERER (see " + probeReportDefault.name + ")", baseline)
            assertTrue("baseline renderer must NOT route through virgl (actual: " + baseline + ")",
                !baseline!!.contains("virgl", ignoreCase = true))
            val soft = Regex("llvmpipe|softpipe|swiftshader", RegexOption.IGNORE_CASE)
            val baseClass = if (soft.containsMatchIn(baseline)) "software"
                else "NOT-virgl, NOT a known software string (unknown/other)"

            // Arm B: opt-in virgl. The probe RESULT is the primary
            // evidence: a missing forked worker is an observation recorded
            // in the report, never a reason to discard the actual renderer
            // outcome (review 2026-11-01). The renderer init runs LAZILY in
            // the forked worker after accept (vtest_server.c:648-662), so
            // "server up" says nothing about renderer init - only the
            // guest probe outcome does.
            var armBReason = "arm B did not complete"
            val diag = StringBuilder()
            try {
                flag.writeText("1")
                val sock = XServer.startVirglServerForTest(ctx,
                    extraEnv = mapOf("VIRGL_LOG_LEVEL" to "warning"))
                    ?: throw AssertionError("virgl server failed to start (see files/virgl-server.log)")
                val ident = XServer.virglServerIdentForTest()
                assertNotNull("virgl server identity not captured (fail-closed stop only)", ident)
                // Bind the server generation's OWN socket File (returned by
                // start), never a reconstructed path.
                val curSock = XServer.virglCurrentSocketForTest()
                assertEquals("current socket state differs from the started generation",
                    sock.absolutePath, curSock?.absolutePath)
                diag.append("server: pid=").append(ident!!.pid).append(" start=").append(ident.start)
                    .append(" sock=").append(sock.absolutePath).append('\n')

                var virglResult: Proot.Result? = null
                var virglErr: Throwable? = null
                val t = kotlin.concurrent.thread {
                    try {
                        virglResult = runProbeArm("virpipe (opt-in, vtest socket bound, GALLIUM_DRIVER=virpipe)",
                            probeReportVirgl, virgl = true, holdSecs = 25, sock = sock,
                            bootBudgetMs = 20_000L)
                    } catch (e: Throwable) { virglErr = e }
                }
                // Bounded observation window: 15s. Arm A's whole guest boot
                // + probe takes ~0.3s on this emulator, so a connected
                // client's worker would be visible within ~1s. Absence is
                // RECORDED here and asserted only against the collected
                // probe outcome below - never asserted before it.
                var scan: VirglTestCommon.ChildScan? = null
                var liveWorkers: List<VirglTestCommon.ProcObs> = emptyList()
                val wEnd = System.nanoTime() + 15_000_000_000L
                while (System.nanoTime() < wEnd) {
                    scan = VirglTestCommon.scanChildren(ident.pid)
                    liveWorkers = (scan.viaChildrenFile + scan.viaPpidScan)
                        .filter { it.state != "Z" }.distinctBy { it.pid }
                    if (liveWorkers.isNotEmpty()) break
                    Thread.sleep(500)
                }
                val unix = VirglTestCommon.procNetUnixRows(sock.name)
                diag.append("worker-window: 15s; live-workers: ")
                    .append(if (liveWorkers.isEmpty()) "NONE-OBSERVED"
                            else liveWorkers.joinToString(",") { it.pid.toString() + "/" + it.state })
                    .append('\n')
                diag.append("children-file-raw: [").append(scan?.childrenFileRaw ?: "<unread>")
                    .append("] children-file-error: ").append(scan?.childrenFileError ?: "none")
                    .append(" ppid-scan-error: ").append(scan?.scanError ?: "none").append('\n')
                diag.append("children-file-entries(incl-zombies): ")
                    .append(scan?.viaChildrenFile?.joinToString(",") { it.pid.toString() + "/" + it.state } ?: "<none>")
                    .append(" ppid-scan-entries(incl-zombies): ")
                    .append(scan?.viaPpidScan?.joinToString(",") { it.pid.toString() + "/" + it.state } ?: "<none>")
                    .append('\n')
                diag.append("proc-net-unix(circumstantial-labeled) rows-with-sockname: [")
                    .append(unix.first ?: "<none>").append("] read-error: ")
                    .append(unix.second ?: "none").append(" total-table-rows: ")
                    .append(unix.third).append('\n')
                diag.append("probe-thread-alive-after-window: ").append(t.isAlive).append('\n')

                if (liveWorkers.isNotEmpty()) {
                    // Stop WITH an observed worker: the owned
                    // stop-with-client path is genuinely exercised.
                    XServer.stopVirglServerForTest("probe-stop-with-client")
                    assertTrue("virgl server still alive as captured identity after stop",
                        !VirglTestCommon.aliveAs(ident))
                    for (w in liveWorkers) {
                        val obs = VirglTestCommon.obsOf(w.pid)
                        assertTrue("virgl worker " + w.pid + " still running after stop (state=" +
                            (obs?.state ?: "gone") + ")", obs == null || obs.state == "Z")
                    }
                    diag.append("stop-path: WITH observed worker(s) ")
                        .append(liveWorkers.map { it.pid }).append('\n')
                } else {
                    diag.append("stop-path: WITHOUT observed worker (deferred until after probe join)\n")
                }
                // Bounded join EITHER WAY: the probe's own runGuest timeout
                // (25s hold + 20s boot budget) bounds the guest; on timeout
                // the Result still carries partial stdout/stderr with the
                // stage breadcrumbs localizing where the guest hung.
                t.join(70_000)
                diag.append("probe-thread-alive-after-join: ").append(t.isAlive)
                    .append(" thread-err: ").append(virglErr?.toString() ?: "none").append('\n')
                if (liveWorkers.isEmpty()) {
                    XServer.stopVirglServerForTest("probe-stop-no-observed-worker")
                    assertTrue("virgl server still alive as captured identity after stop",
                        !VirglTestCommon.aliveAs(ident))
                }
                assertTrue("virpipe probe thread did not finish within bound: " + virglErr,
                    virglErr == null && virglResult != null)
                val res = virglResult!!
                val candidate = VirglTestCommon.rendererOf(res)
                val stringsDone = res.stdout.contains("EGLPROBE-STRINGS-DONE")
                // A stop with a held client RESETS the connection: mesa
                // prints "lost connection" and eglprobe aborts AFTER the
                // strings were printed - that exit code is expected
                // non-zero; classify, do not fail. Without a held client
                // the exit code contract is strict.
                if (liveWorkers.isEmpty() || !stringsDone) {
                    assertEquals("virpipe eglprobe failed (see " + probeReportVirgl.name + ")",
                        0, res.exitCode)
                }
                assertNotNull("virpipe probe printed no GL-RENDERER (see " + probeReportVirgl.name + ")", candidate)
                assertTrue("opt-in probe must render through virpipe; any other string means the virgl" +
                    " route FAILED (actual: " + candidate + ")", candidate!!.contains("virgl ("))
                // A provably HELD connection (hold-entered breadcrumb) with
                // NO worker observed by EITHER /proc mechanism means the
                // observation harness is defective on this device - fail
                // loudly WITH the raw captures, never silently.
                if (res.stderr.contains("EGLPROBE-STAGE: hold-entered") && liveWorkers.isEmpty()) {
                    throw AssertionError("guest provably HELD the vtest connection (hold-entered) but no" +
                        " forked worker was observed by either /proc mechanism - worker observation is" +
                        " defective on this device (see diagnostics in " + probeReportVirgl.name + ")")
                }
                val host = candidate.substringAfter("virgl (").substringBefore(")")
                val hostClass = if (soft.containsMatchIn(host))
                    "virgl transport PROVEN; host backend SOFTWARE (" + host + ") - NO GPU offload claim"
                    else "virgl transport proven; host backend candidate GPU/unknown (" + host + ")"
                armBReason = "completed"
                VirglTestCommon.writeProbeReport(probeReportVirgl,
                    "virpipe (opt-in, vtest socket bound, GALLIUM_DRIVER=virpipe)",
                    listOf("/tmp/eglprobe", "--hold", "25"),
                    mapOf("EGL_PLATFORM" to "surfaceless", "GALLIUM_DRIVER" to "virpipe"),
                    listOf(sock.absolutePath to "/tmp/.virgl_test"),
                    res,
                    extra = "baseline-classification: " + baseClass + " renderer=[" + baseline + "]\n" +
                        "host-classification: " + hostClass + "\n" +
                        "workers-at-stop: " + liveWorkers.map { it.pid } + "\n" +
                        "--- diagnostics ---\n" + diag.toString() +
                        "emulator result - NOT Adreno; transport proof only, no performance claim\n")
            } finally {
                // Append-only evidence: a stub NEVER replaces an existing
                // report, and the stub carries the diagnostics too.
                if (armBReason != "completed" || !probeReportVirgl.isFile) {
                    val stub = "== virpipe arm NOT COMPLETED ==\nreason: " + armBReason + "\n" +
                        "--- diagnostics ---\n" + diag.toString()
                    if (probeReportVirgl.isFile) probeReportVirgl.appendText("\n" + stub)
                    else probeReportVirgl.writeText(stub)
                }
            }

        } finally {
            // Unmasked contract: a missing virpipe report is classified,
            // never silently absent - covers failure BEFORE arm B starts
            // (staging or baseline arm) as well as mid-arm-B failures.
            if (!probeReportVirgl.isFile) {
                probeReportVirgl.writeText("== virpipe arm NOT REACHED ==\n" +
                    "reason: staging or baseline arm failed first - see virgl-probe-default.txt" +
                    " (if present) and the instrument log\n")
            }
            if (savedFlag != null) flag.writeText(savedFlag) else flag.delete()
        }
    }

    /** Diagnostic compare (review 2026-11-01): upstream vtest supports
     *  --no-fork (vtest_server.c:185 {"no-fork", no_argument, NULL,
     *  OPT_NO_FORK}; do_fork=false at lines 210/213/271), which moves the
     *  renderer init into the MAIN server process at the first client
     *  (vtest_server.c:648-662). If the forked arm shows no worker but
     *  this arm proves the virgl renderer, renderer init is healthy and
     *  the fork/observation path is the defect; if both fail, renderer
     *  init on the SwiftShader translator is the defect. Test-only flag:
     *  the product launch never passes --no-fork. */
    @Test fun virpipeNoForkRendererCompare() {
        val savedFlag = if (flag.isFile) flag.readText() else null
        try {
            stageProbe()
            flag.writeText("1")
            val sock = XServer.startVirglServerForTest(ctx,
                extraEnv = mapOf("VIRGL_LOG_LEVEL" to "warning"),
                extraArgs = listOf("--no-fork"))
                ?: throw AssertionError("virgl server (--no-fork) failed to start (see files/virgl-server.log)")
            val res = runProbeArm("virpipe no-fork compare (renderer proof only)",
                probeReportNofork, virgl = true, holdSecs = 0, sock = sock, bootBudgetMs = 30_000L)
            XServer.stopVirglServerForTest("nofork-compare-stop")
            val candidate = VirglTestCommon.rendererOf(res)
            assertNotNull("no-fork arm printed no GL-RENDERER (see virgl-probe-nofork.txt)", candidate)
            assertTrue("no-fork arm must render through virpipe; any other string means renderer" +
                " init FAILED in the main server process too (actual: " + candidate + ")",
                candidate!!.contains("virgl ("))
        } finally {
            XServer.stopVirglServerForTest("nofork-compare-cleanup")
            // Stub-classified like the virpipe arm: absence is a capture
            // break, never a silent skip. Separate JUnit case with its own
            // staging/server, so a forked-arm failure cannot skip it.
            if (!probeReportNofork.isFile) {
                probeReportNofork.writeText("== no-fork arm NOT COMPLETED ==\n" +
                    "reason: staging or server start failed before the probe ran -" +
                    " see the instrument log and files/virgl-server.log\n")
            }
            if (savedFlag != null) flag.writeText(savedFlag) else flag.delete()
        }
    }
}

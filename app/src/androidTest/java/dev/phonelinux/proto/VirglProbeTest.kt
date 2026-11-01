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
        val rootSh = File(rootfs, "bin/sh")
        val guestMach = elfMachine(rootSh)
        val guestArch = when (guestMach) {
            0x3E -> "x86_64"
            0xB7 -> "aarch64"
            else -> throw AssertionError("rootfs /bin/sh ELF machine unreadable/unsupported: " + guestMach +
                " (" + rootSh.absolutePath + ") - cannot select a probe binary")
        }
        val interp = if (guestArch == "x86_64") "lib64/ld-linux-x86-64.so.2" else "lib/ld-linux-aarch64.so.1"
        assertTrue("guest libc interpreter missing in rootfs: " + interp,
            File(rootfs, interp).exists())
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
                            holdSecs: Int, sock: File?): Proot.Result {
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
            fakeRoot = false, timeoutMs = (holdSecs * 1000L) + 120_000L)
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

            // Arm B: opt-in virgl. Server up, then the holding probe keeps
            // a client connected so Stop runs with an owned worker. Any
            // early exit still writes the virpipe report (never masked).
            var armBReason = "arm B did not complete"
            try {
                flag.writeText("1")
                val sock = XServer.startVirglServerForTest(ctx)
                    ?: throw AssertionError("virgl server failed to start (see files/virgl-server.log)")
                val ident = XServer.virglServerIdentForTest()
                assertNotNull("virgl server identity not captured (fail-closed stop only)", ident)
                // Bind the server generation's OWN socket File (returned by
                // start), never a reconstructed path.
                val curSock = XServer.virglCurrentSocketForTest()
                assertEquals("current socket state differs from the started generation",
                    sock.absolutePath, curSock?.absolutePath)

                var virglResult: Proot.Result? = null
                var virglErr: Throwable? = null
                val t = kotlin.concurrent.thread {
                    try {
                        virglResult = runProbeArm("virpipe (opt-in, vtest socket bound, GALLIUM_DRIVER=virpipe)",
                            probeReportVirgl, virgl = true, holdSecs = 25, sock = sock)
                    } catch (e: Throwable) { virglErr = e }
                }
                // While the client holds the connection, the vtest server
                // has a forked worker (vtest_server.c:548, one per accept).
                var workers = VirglTestCommon.childrenOf(ident!!.pid)
                val wEnd = System.nanoTime() + 15_000_000_000L
                while (workers.isEmpty() && System.nanoTime() < wEnd) {
                    Thread.sleep(500); workers = VirglTestCommon.childrenOf(ident.pid)
                }
                assertTrue("no owned vtest worker observed while eglprobe held the connection" +
                    " - the stop-with-worker path was NOT exercised", workers.isNotEmpty())
                XServer.stopVirglServerForTest("probe-stop-with-client")
                assertTrue("virgl server still alive as captured identity after stop",
                    !VirglTestCommon.aliveAs(ident))
                for (w in workers) {
                    assertTrue("virgl worker " + w.pid + " still alive after stop", !VirglTestCommon.aliveAs(w))
                }
                t.join(150_000)
                assertTrue("virpipe probe thread did not finish: " + virglErr, virglErr == null && virglResult != null)

                assertEquals("virpipe eglprobe failed (see " + probeReportVirgl.name + ")",
                    0, virglResult!!.exitCode)
                val candidate = VirglTestCommon.rendererOf(virglResult!!)
                assertNotNull("virpipe probe printed no GL-RENDERER (see " + probeReportVirgl.name + ")", candidate)
                assertTrue("opt-in probe must render through virpipe; any other string means the virgl" +
                    " route FAILED (actual: " + candidate + ")", candidate!!.contains("virgl ("))
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
                    virglResult,
                    extra = "baseline-classification: " + baseClass + " renderer=[" + baseline + "]\n" +
                        "host-classification: " + hostClass + "\n" +
                        "workers-at-stop: " + workers.map { it.pid } + "\n" +
                        "emulator result - NOT Adreno; transport proof only, no performance claim\n")
            } finally {
                if (!probeReportVirgl.isFile) {
                    probeReportVirgl.writeText("== virpipe arm NOT COMPLETED ==\nreason: " + armBReason + "\n")
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
}

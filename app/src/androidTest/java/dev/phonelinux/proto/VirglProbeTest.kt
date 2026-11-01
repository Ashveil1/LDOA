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
 *  eglprobe binary (ci/virgl/eglprobe.c, cross-compiled by the lane)
 *  creates a GLES2 context on EGL_PLATFORM_SURFACELESS_MESA inside the
 *  provisioned rootfs and prints GL-RENDERER. This is the ONLY probe
 *  whose renderer string can prove the guest mesa driver: lorie's GLX
 *  is indirect-only on the emulator (no usable DRI3 device), so glxinfo
 *  reports the X SERVER's renderer, never the guest driver's (run
 *  41307671476: the glxinfo probe produced no renderer line at all, and
 *  its output was lost to tail -10 + a discarded Proot.Result - this
 *  redesign writes full reports to files the lane archives).
 *
 *  Arms: (A) default guest mesa (no opt-in): renderer asserted non-empty
 *  and NOT virgl, classification logged from the actual string; (B)
 *  opt-in: host vtest server up, GALLIUM_DRIVER=virpipe, renderer
 *  asserted "virgl (...)" - transport proof only; a software host
 *  backend inside the parentheses is NOT a GPU-offload claim. Arm B also
 *  exercises the owned stop-with-client path: eglprobe --hold keeps the
 *  EGL connection open so the server has a forked worker at Stop.
 *  Emulator results are EMULATOR results, not Adreno results. */
@RunWith(AndroidJUnit4::class)
class VirglProbeTest {
    private val TAG = "VirglProbeTest"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val d = Distros.UBUNTU
    private val rootfs get() = DistroInstaller(ctx).rootfsDir(d)
    private val flag get() = File(ctx.filesDir, ".ldoa-virgl-optin")
    private val probeLocal get() = File(ctx.filesDir, "eglprobe")
    private val probeReportDefault get() = File(ctx.filesDir, "virgl-probe-default.txt")
    private val probeReportVirgl get() = File(ctx.filesDir, "virgl-probe-virpipe.txt")

    @After fun cleanup() {
        try { XServer.stopVirglServerForTest("probe-cleanup") } catch (t: Throwable) { }
    }

    /** Stage the lane-built probe binary: the workflow pushes it to the
     *  app's external files dir; copy into internal files (exec-able)
     *  with an explicit exec bit. Fails LOUDLY when the push is missing. */
    private fun stageProbe() {
        val ext = File(ctx.getExternalFilesDir(null), "eglprobe")
        assertTrue("eglprobe not pushed by the lane to " + ext.absolutePath +
            " - build/push step missing or failed", ext.isFile)
        probeLocal.writeBytes(ext.readBytes())
        android.system.Os.chmod(probeLocal.absolutePath, 493) // 0755
        assertTrue("eglprobe not executable at " + probeLocal.absolutePath, probeLocal.canExecute())
    }

    private fun runProbeArm(title: String, reportFile: File, virgl: Boolean,
                            holdSecs: Int): Proot.Result {
        val binds = mutableListOf(ctx.filesDir.absolutePath to "/ldoa-probe")
        if (virgl) {
            val sock = XServer.virglSocketPathForTest(ctx)
            assertTrue("virgl socket missing for probe bind: " + sock.absolutePath, sock.exists())
            binds.add(sock.absolutePath to "/tmp/.virgl_test")
        }
        val env = mutableMapOf("EGL_PLATFORM" to "surfaceless")
        if (virgl) env["GALLIUM_DRIVER"] = "virpipe"
        val cmd = mutableListOf("/ldoa-probe/eglprobe")
        if (holdSecs > 0) cmd.addAll(listOf("--hold", holdSecs.toString()))
        val result = Proot.runGuest(ctx, rootfs, cmd, extraBinds = binds, extraEnv = env,
            fakeRoot = false, timeoutMs = (holdSecs * 1000L) + 120_000L)
        VirglTestCommon.writeProbeReport(reportFile, title, cmd, env, binds, result)
        return result
    }

    @Test fun guestRendererProofBaselineThenVirpipe() {
        val savedFlag = if (flag.isFile) flag.readText() else null
        try {
            stageProbe()

            // Arm A: default guest mesa (opt-in flag OFF).
            flag.delete()
            val baseResult = runProbeArm("baseline (default guest mesa, no opt-in)",
                probeReportDefault, virgl = false, holdSecs = 0)
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
            // a client connected so Stop runs with an owned worker.
            flag.writeText("1")
            val sock = XServer.startVirglServerForTest(ctx)
            assertNotNull("virgl server failed to start (see files/virgl-server.log)", sock)
            val ident = XServer.virglServerIdentForTest()
            assertNotNull("virgl server identity not captured (fail-closed stop only)", ident)

            var virglResult: Proot.Result? = null
            var virglErr: Throwable? = null
            val t = kotlin.concurrent.thread {
                try {
                    virglResult = runProbeArm("virpipe (opt-in, vtest socket bound, GALLIUM_DRIVER=virpipe)",
                        probeReportVirgl, virgl = true, holdSecs = 25)
                } catch (e: Throwable) { virglErr = e }
            }
            // While the client holds the connection, the vtest server has
            // a forked worker (vtest_server.c:548, one per accept).
            var workers = VirglTestCommon.childrenOf(ident!!.pid)
            val wEnd = System.currentTimeMillis() + 15_000
            while (workers.isEmpty() && System.currentTimeMillis() < wEnd) {
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
            VirglTestCommon.writeProbeReport(probeReportVirgl,
                "virpipe (opt-in, vtest socket bound, GALLIUM_DRIVER=virpipe)",
                listOf("/ldoa-probe/eglprobe", "--hold", "25"),
                mapOf("EGL_PLATFORM" to "surfaceless", "GALLIUM_DRIVER" to "virpipe"),
                listOf(ctx.filesDir.absolutePath to "/ldoa-probe",
                    XServer.virglSocketPathForTest(ctx).absolutePath to "/tmp/.virgl_test"),
                virglResult,
                extra = "baseline-classification: " + baseClass + " renderer=[" + baseline + "]\n" +
                    "host-classification: " + hostClass + "\n" +
                    "workers-at-stop: " + workers.map { it.pid } + "\n" +
                    "emulator result - NOT Adreno; transport proof only, no performance claim\n")
        } finally {
            if (savedFlag != null) flag.writeText(savedFlag) else flag.delete()
        }
    }
}

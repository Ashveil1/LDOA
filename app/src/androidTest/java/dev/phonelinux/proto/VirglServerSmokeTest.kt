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

/** VirGL spike, stage 1: HOST render-server smoke. No X, no GNOME, no
 *  rootfs - starts the opt-in vtest server alone and proves the owned
 *  lifecycle: socket created (generation-unique name), identity
 *  captured, identity-verified stop removes exactly that server. Also
 *  runs hostprobe (ci/virgl/hostprobe.c): an independent HOST EGL/GLES
 *  renderer measurement in the app's own process environment - the same
 *  host EGL the server renders through - so the host backend is measured
 *  directly, not inferred from the guest string alone. SCOPE: on the CI
 *  emulator this measures the emulator's GPU transport (swiftshader or
 *  host-GPU passthrough), NOT Adreno acceleration - phone numbers need a
 *  phone run, which this lane does not claim. Full evidence
 *  lands in files/virgl-smoke.txt (cat'ed by the lane); nothing lives
 *  only in an assertion message. */
@RunWith(AndroidJUnit4::class)
class VirglServerSmokeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val flag get() = File(ctx.filesDir, ".ldoa-virgl-optin")
    private val serverLog get() = File(ctx.filesDir, "virgl-server.log")
    private val reportFile get() = File(ctx.filesDir, "virgl-smoke.txt")

    @After fun cleanup() {
        try { XServer.stopVirglServerForTest("smoke-cleanup") } catch (t: Throwable) { }
    }

    /** Run the hostprobe PACKAGED IN THE APK's jniLibs
     *  (app/src/main/jniLibs/<abi>/libhostprobe.so, built by the lane
     *  before assembleDebug). Kernel exec from nativeLibraryDir is the
     *  repo's proven route (libproot.so, libvirgl_test_server.so); an
     *  app-writable filesDir copy would be SELinux-denied on targetSdk
     *  34 (W^X) - never exec from there. */
    private fun runHostProbe(rb: StringBuilder) {
        val local = File(ctx.applicationInfo.nativeLibraryDir, "libhostprobe.so")
        assertTrue("libhostprobe.so not packaged in nativeLibraryDir " +
            "(lane build step must write app/src/main/jniLibs/<abi>/ before assembleDebug): " +
            local.absolutePath, local.isFile)
        rb.append("hostprobe: ").append(local.absolutePath).append(" (primary ABI ")
            .append(android.os.Build.SUPPORTED_ABIS.firstOrNull()).append(")\n")
        try {
            val proc = ProcessBuilder(local.absolutePath).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (proc.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
            if (proc.isAlive) { proc.destroyForcibly(); rb.append("hostprobe TIMED OUT (30s, killed)\n"); return }
            rb.append("hostprobe rc=").append(proc.exitValue()).append('\n')
            rb.append(out)
            assertEquals("hostprobe failed (rc=" + proc.exitValue() + ")", 0, proc.exitValue())
            assertTrue("hostprobe printed no GL-RENDERER", out.contains("GL-RENDERER:"))
        } catch (t: Throwable) {
            rb.append("hostprobe exec failed: ").append(t).append('\n')
            throw AssertionError("hostprobe could not exec: " + t.message)
        }
    }

    @Test fun serverStartsStopsCleanly() {
        val savedFlag = if (flag.isFile) flag.readText() else null
        val rb = StringBuilder()
        try {
            try { serverLog.delete() } catch (t: Throwable) { }
            runHostProbe(rb)
            flag.writeText("1")
            val sock = XServer.startVirglServerForTest(ctx)
            rb.append("socket: ").append(sock?.absolutePath ?: "null").append('\n')
            assertNotNull("virgl server failed to start (see files/virgl-server.log and files/virgl-smoke.txt)", sock)
            assertTrue("socket file missing after start: " + sock, sock!!.exists())
            val ident = XServer.virglServerIdentForTest()
            rb.append("ident: ").append(ident?.let { "pid=" + it.pid + " start=" + it.start + " uid=" + it.uid } ?: "null").append('\n')
            assertNotNull("server identity not captured - stop would be destroy-only (fail-closed)", ident)
            assertTrue("server not alive as its captured identity", VirglTestCommon.aliveAs(ident!!))

            XServer.stopVirglServerForTest("smoke")
            val gone = !VirglTestCommon.aliveAs(ident)
            rb.append("aliveAs-after-stop: ").append(!gone).append('\n')
            val leftoverWorkers = VirglTestCommon.childrenOf(ident.pid)
            rb.append("leftover-workers-after-stop: ").append(leftoverWorkers.map { it.pid }).append('\n')
            assertTrue("virgl server pid " + ident.pid + " still alive as captured identity after stop", gone)
            for (w in leftoverWorkers) {
                assertTrue("virgl worker " + w.pid + " still alive after stop", !VirglTestCommon.aliveAs(w))
            }
            rb.append("SMOKE-OK\n")
        } finally {
            if (savedFlag != null) flag.writeText(savedFlag) else flag.delete()
            rb.append("--- virgl-server.log ---\n")
            rb.append(if (serverLog.isFile) serverLog.readText() else "(missing)\n")
            // Append: both tests in this class share the pulled report.
            reportFile.appendText("== serverStartsStopsCleanly ==\n" + rb.toString())
        }
    }

    /** Overlap race coverage: a stop clears shared state BEFORE its
     *  signals finish, so a new start can begin while the old stop is
     *  mid-flight. With generation-unique socket names the old stop's
     *  trailing delete must never touch the NEW generation's socket. */
    @Test fun stopStartOverlapKeepsNewGeneration() {
        val savedFlag = if (flag.isFile) flag.readText() else null
        val rb = StringBuilder()
        try {
            try { serverLog.delete() } catch (t: Throwable) { }
            flag.writeText("1")
            val sockA = XServer.startVirglServerForTest(ctx)
            assertNotNull("server A failed to start", sockA)
            val identA = XServer.virglServerIdentForTest()
            assertNotNull("server A identity missing", identA)
            rb.append("A: sock=").append(sockA!!.absolutePath).append(" pid=").append(identA!!.pid).append('\n')

            val stopDone = java.util.concurrent.CountDownLatch(1)
            kotlin.concurrent.thread {
                XServer.stopVirglServerForTest("overlap-test")
                stopDone.countDown()
            }
            // Wait for A's stop to CLEAR shared state (its first step), so
            // B starts deterministically INSIDE A's signal/cleanup window
            // (SIGSTOP confirm + TERM/KILL + socket delete) - a genuine
            // overlap, not a race against the state check.
            val clearEnd = System.nanoTime() + 10_000_000_000L
            while (XServer.virglServerIdentForTest() != null && System.nanoTime() < clearEnd) Thread.sleep(25)
            assertTrue("A's stop never cleared shared state (10s) - overlap not exercised",
                XServer.virglServerIdentForTest() == null)
            val sockB = XServer.startVirglServerForTest(ctx)
            assertTrue("stop latch did not release within 30s", stopDone.await(30, java.util.concurrent.TimeUnit.SECONDS))
            rb.append("B: sock=").append(sockB?.absolutePath ?: "null").append('\n')
            assertNotNull("server B failed to start during/after A's stop", sockB)
            assertTrue("generation-unique socket names required (A and B share a path)",
                sockA.absolutePath != sockB!!.absolutePath)
            assertTrue("B's socket file missing (A's stop unlinked it?)", sockB.exists())
            val identB = XServer.virglServerIdentForTest()
            assertNotNull("server B identity missing", identB)
            assertTrue("B not alive as its captured identity", VirglTestCommon.aliveAs(identB!!))
            assertTrue("A still alive as its captured identity after the overlapped stop",
                !VirglTestCommon.aliveAs(identA))
            XServer.stopVirglServerForTest("overlap-test-B")
            assertTrue("B still alive after its own stop", !VirglTestCommon.aliveAs(identB))
            rb.append("OVERLAP-OK\n")
        } finally {
            if (savedFlag != null) flag.writeText(savedFlag) else flag.delete()
            rb.append("--- virgl-server.log ---\n")
            rb.append(if (serverLog.isFile) serverLog.readText() else "(missing)\n")
            reportFile.appendText("== stopStartOverlapKeepsNewGeneration ==\n" + rb.toString())
        }
    }
}

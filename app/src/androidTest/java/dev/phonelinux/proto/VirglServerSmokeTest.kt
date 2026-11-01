package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** VirGL spike, stage 1: HOST render-server smoke. No X, no GNOME, no
 *  rootfs - starts the opt-in vtest server alone and proves the owned
 *  lifecycle: socket created, identity captured, identity-verified stop
 *  removes exactly that server. Full evidence lands in
 *  files/virgl-smoke.txt (cat'ed by the lane); nothing lives only in an
 *  assertion message. */
@RunWith(AndroidJUnit4::class)
class VirglServerSmokeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val flag get() = File(ctx.filesDir, ".ldoa-virgl-optin")
    private val serverLog get() = File(ctx.filesDir, "virgl-server.log")
    private val reportFile get() = File(ctx.filesDir, "virgl-smoke.txt")

    @After fun cleanup() {
        try { XServer.stopVirglServerForTest("smoke-cleanup") } catch (t: Throwable) { }
    }

    @Test fun serverStartsStopsCleanly() {
        val savedFlag = if (flag.isFile) flag.readText() else null
        val rb = StringBuilder()
        try {
            try { serverLog.delete() } catch (t: Throwable) { }
            flag.writeText("1")
            val sock = XServer.startVirglServerForTest(ctx)
            rb.append("socket: ").append(sock?.absolutePath ?: "null").append('\n')
            assertNotNull("virgl server failed to start (see files/virgl-server.log and files/virgl-smoke.txt)", sock)
            assertTrue("socket file missing after start: " + sock, sock!!.exists())
            val ident = XServer.virglServerIdentForTest()
            rb.append("ident: ").append(ident?.let { "pid=" + it.pid + " start=" + it.start + " uid=" + it.uid } ?: "null").append('\n')
            assertNotNull("server identity not captured - stop would be destroy-only (fail-closed)", ident)
            assertTrue("server not alive as its captured identity", VirglTestCommon.aliveAs(ident!!))
            rb.append("aliveAs-before-stop: true\n")

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
            reportFile.writeText(rb.toString())
        }
    }
}

package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression coverage for reuse-session health verification
 * (XServer.healthyReuseOrReason, 2026-10-31). Process liveness alone used
 * to be enough to bypass the readiness gate on a reused session; these
 * tests pin the stronger checks: same-process identity (pid + start time,
 * so a recycled pid is never "our" session), live session bus, and an X
 * server that is not younger than the session (a restarted X orphaned
 * every guest client). No GNOME/rootfs needed: /system/bin/sh sleep
 * processes stand in for the session, its bus, and the X server.
 * Bounded: a few seconds per case. Nothing here signals a process it did
 * not spawn.
 */
@RunWith(AndroidJUnit4::class)
class ReuseGateHealthTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val distro = Distros.UBUNTU
    private val procs = ArrayList<Process>()
    private val busFile: File
        get() = File(DistroInstaller(ctx).rootfsDir(distro), "root/.gnome-bus.pid")

    private fun spawn(seconds: Int): Process =
        ProcessBuilder("/system/bin/sh", "-c", "sleep " + seconds).start().also { procs.add(it) }

    /** A fake session whose tree contains a process named gnome-shell
     *  (argv0 via exec -a), the way the real guest tree does. */
    private fun spawnSessionWithShell(): Process =
        ProcessBuilder("/system/bin/sh", "-c", "exec -a gnome-shell sleep 120 & exec sleep 120")
            .start().also { procs.add(it) }

    private fun pidOf(p: Process): Int {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; return f.getInt(p)
    }

    private fun install(session: Process?, xs: Process?, bus: Process? = null) {
        if (bus != null) {
            busFile.parentFile?.mkdirs()
            busFile.writeText(pidOf(bus).toString())
        } else {
            busFile.delete()
        }
        XServer.installSessionStateForTest(distro, session, xs)
    }

    @After
    fun cleanup() {
        procs.forEach { it.destroyForcibly() }
        procs.forEach { try { it.waitFor() } catch (t: Throwable) { } }
        busFile.delete()
        XServer.installSessionStateForTest(distro, null, null)
    }

    @Test
    fun healthyReusePassesIdentityBusAndXChecks() {
        val xs = spawn(120)                 // X server older than the session
        Thread.sleep(400)
        val session = spawnSessionWithShell()
        Thread.sleep(300)                   // let the shell child fork
        val bus = spawn(120)
        install(session, xs, bus)
        assertNull("live session+shell + live bus + older X must be healthy",
            XServer.healthyReuseOrReason(ctx, distro, session))
    }

    @Test
    fun busAliveButShellGoneIsRejected() {
        // The review scenario: the session bus daemon is alive and its
        // identity matches, but gnome-shell is gone from the session
        // tree. Bus liveness must never mask a missing desktop.
        val xs = spawn(120)
        Thread.sleep(400)
        val session = spawn(120)            // NO gnome-shell child in this tree
        val bus = spawn(120)
        install(session, xs, bus)
        val reason = XServer.healthyReuseOrReason(ctx, distro, session)
        assertNotNull("bus alive but shell gone must be rejected", reason)
        assertTrue(reason!!, reason.contains("gnome-shell gone"))
    }

    @Test
    fun differentProcessIdentityIsRejected() {
        val xs = spawn(120)
        Thread.sleep(400)
        val tracked = spawn(120)
        val other = spawn(120)
        val bus = spawn(120)
        install(tracked, xs, bus)
        val reason = XServer.healthyReuseOrReason(ctx, distro, other)
        assertNotNull("a different process than the tracked one must be rejected", reason)
        assertTrue(reason!!, reason.contains("identity changed"))
    }

    @Test
    fun deadSessionBusIsRejected() {
        val xs = spawn(120)
        Thread.sleep(400)
        val session = spawn(120)
        val bus = spawn(1)
        bus.waitFor()                       // reaped: /proc entry is gone
        install(session, xs, bus)
        val reason = XServer.healthyReuseOrReason(ctx, distro, session)
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("bus process gone"))
    }

    @Test
    fun missingBusPidFileIsRejected() {
        val xs = spawn(120)
        Thread.sleep(400)
        val session = spawn(120)
        install(session, xs, null)
        val reason = XServer.healthyReuseOrReason(ctx, distro, session)
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("pid file missing"))
    }

    @Test
    fun youngerXServerIsRejected() {
        val session = spawn(120)            // session first...
        Thread.sleep(600)
        val xs = spawn(120)                 // ...X server younger = restarted
        val bus = spawn(120)
        install(session, xs, bus)
        val reason = XServer.healthyReuseOrReason(ctx, distro, session)
        assertNotNull("an X server newer than the session must be rejected", reason)
        assertTrue(reason!!, reason.contains("X server restarted"))
    }

    @Test
    fun deadXServerIsRejected() {
        val xs = spawn(1)
        Thread.sleep(400)
        val session = spawn(120)
        val bus = spawn(120)
        xs.waitFor()
        install(session, xs, bus)
        val reason = XServer.healthyReuseOrReason(ctx, distro, session)
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("X server not running"))
    }
}

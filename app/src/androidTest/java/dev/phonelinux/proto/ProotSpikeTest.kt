package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spike: proves the risk-#1 packaging question -
 * proot shipped via assets (extracted to private dir) can launch and exec guest binaries
 * under modern targetSdk (34) with no root.
 */
@RunWith(AndroidJUnit4::class)
class ProotSpikeTest {
    @Test
    fun prootSelfTestAndGuestExec() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("native binaries extracted", Proot.binariesPresent(ctx))

        val v = Proot.selfTest(ctx)
        Log.i("PHONELINUX_SPIKE", "selftest exit=${v.exitCode} out=${v.stdout.trim()} err=${v.stderr.trim()}")
        assertEquals("proot --version must exit 0. stderr=${v.stderr}", 0, v.exitCode)

        val d = Distros.ALPINE
        val installer = DistroInstaller(ctx)
        if (!installer.isInstalled(d)) {
            var last: InstallState = InstallState.NotInstalled
            val ok = installer.install(d) { last = it }
            assertTrue("install failed at state=$last", ok)
        }
        assertTrue(installer.isInstalled(d))

        val g = Proot.runGuest(ctx, installer.rootfsDir(d),
            listOf("/bin/sh", "-c", "uname -m; cat /etc/alpine-release; echo guest-exec-OK"))
        Log.i("PHONELINUX_SPIKE", "guest exit=${g.exitCode} out=${g.stdout.trim()} err=${g.stderr.trim()}")
        assertEquals("guest exec must exit 0. stderr=${g.stderr}", 0, g.exitCode)
        val expectedArch = if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64" else "aarch64"
        assertTrue("guest must report $expectedArch, got: ${g.stdout}", g.stdout.contains(expectedArch))
        assertTrue(g.stdout.contains("guest-exec-OK"))
    }
}

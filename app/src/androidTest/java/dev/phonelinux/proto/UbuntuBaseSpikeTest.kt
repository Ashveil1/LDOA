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
 * Bounded Ubuntu preview qualification (fast, every-push lane): Ubuntu Base
 * installs through the real installer, the guest executes, and apt's
 * network+lock path works under proot -0. This is the scope a preview build
 * may honestly offer: Ubuntu userland + terminal. GNOME is NOT covered here
 * (ubuntu-gnome-spike lane owns that, UbuntuGnomeSpikeTest is excluded from
 * the every-push lane via notClass in spike.yml).
 */
@RunWith(AndroidJUnit4::class)
class UbuntuBaseSpikeTest {
    private val TAG = "PHONELINUX_UBUNTU_BASE"

    @Test
    fun ubuntuInstallsAndAptWorks() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = Distros.UBUNTU
        val installer = DistroInstaller(ctx)
        if (!installer.isInstalled(d)) {
            var last: InstallState = InstallState.NotInstalled
            val ok = installer.install(d) { last = it }
            assertTrue("ubuntu install failed at state=$last", ok)
        }
        assertTrue(installer.isInstalled(d))
        val rootfs = installer.rootfsDir(d)

        val g = Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c",
            "uname -m; . /etc/os-release; echo \"os=\${NAME}-\${VERSION_ID}\"; id -u; echo base-ok"))
        Log.i(TAG, "guest exit=${g.exitCode} out=${g.stdout.trim()} err=${g.stderr.trim()}")
        assertEquals("guest exec must exit 0. stderr=${g.stderr}", 0, g.exitCode)
        val expectedArch = if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64" else "aarch64"
        assertTrue("guest arch $expectedArch, got: ${g.stdout}", g.stdout.contains(expectedArch))
        assertTrue("guest must be Ubuntu, got: ${g.stdout}", g.stdout.contains("Ubuntu"))
        assertTrue(g.stdout.contains("base-ok"))

        // apt's riskiest new ground vs Alpine: package lists over network
        // plus dpkg lock dirs, as fake root.
        val upd = Proot.runGuest(ctx, rootfs, listOf("apt-get", "update"),
            extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"), fakeRoot = true)
        Log.i(TAG, "apt-get update exit=${upd.exitCode} tail=${upd.stdout.takeLast(400)} errtail=${upd.stderr.takeLast(300)}")
        assertEquals("apt-get update must exit 0. tail=${upd.stdout.takeLast(300)} err=${upd.stderr.takeLast(300)}", 0, upd.exitCode)
    }
}

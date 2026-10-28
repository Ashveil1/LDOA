package dev.phonelinux.proto

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Extraction fidelity: archive directory modes must survive install.
 * Empirical check (stat the real installed rootfs), not a umask inference:
 * before the DistroInstaller mode fix, mkdirs() left dirs at the app-process
 * umask (Android apps run umask 0077, so /tmp was 0700, not the archive's
 * 1777). Guest software that hard-checks /tmp for world-writable+sticky
 * (X helpers, dbus, pulse) can refuse a 0700 /tmp.
 */
@RunWith(AndroidJUnit4::class)
class DirModeFidelityTest {

    @Test
    fun ubuntuTmpKeepsArchiveMode1777() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = Distros.UBUNTU
        val installer = DistroInstaller(ctx)
        assertTrue("test needs an installed Ubuntu rootfs", installer.isInstalled(d))
        val rootfs = installer.rootfsDir(d)
        val tmpMode = Os.stat(File(rootfs, "tmp").absolutePath).st_mode and 0xFFF
        android.util.Log.i("PHONELINUX_DIRMODE", "guest /tmp mode=${tmpMode.toString(8)} (archive=1777)")
        assertEquals("guest /tmp mode must match the archive (sticky+world-writable)",
            0x3FF, tmpMode) // 0o1777
    }
}

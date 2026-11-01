package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** VirGL lane SETUP (replaces the full GNOME autoenter for this lane):
 *  minimal rootfs provision only - ubuntu-base via the product installer
 *  (DistroInstaller.install, which also writes guest resolv.conf) plus
 *  the mesa runtime libs eglprobe needs (libegl1, libgles2,
 *  libgl1-mesa-dri for the virpipe gallium driver). NO GNOME packages,
 *  NO desktop launch. The apt step is bounded, runs ONCE, and its rc +
 *  complete output are recorded; library presence is asserted from the
 *  extracted rootfs afterwards. Full evidence: files/virgl-provision.txt. */
@RunWith(AndroidJUnit4::class)
class VirglRootfsProvisionTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val d = Distros.UBUNTU
    private val rootfs get() = DistroInstaller(ctx).rootfsDir(d)
    private val report get() = File(ctx.filesDir, "virgl-provision.txt")

    @Test fun minimalRootfsPlusMesaRuntime(): Unit = runBlocking {
        val rb = StringBuilder()
        try {
            val installer = DistroInstaller(ctx)
            if (!installer.isInstalled(d)) {
                rb.append("rootfs: installing ubuntu-base (").append(d.url).append(")\n")
                val ok = installer.install(d) { }
                rb.append("rootfs install result: ").append(ok).append('\n')
                assertTrue("DistroInstaller.install failed (see virgl-provision.txt)", ok)
            } else {
                rb.append("rootfs: already installed\n")
            }
            assertTrue("rootfs install marker missing", installer.isInstalled(d))

            // Mesa runtime ONLY (not the GNOME stack): bounded, one-shot,
            // checked rc, complete output captured. --no-install-recommends
            // keeps the lane small.
            val cmd = listOf("sh", "-c",
                "apt-get update && apt-get install -y --no-install-recommends libegl1 libgles2 libgl1-mesa-dri")
            val result = Proot.runGuest(ctx, rootfs, cmd, fakeRoot = true, timeoutMs = 600_000)
            rb.append("mesa apt exitCode: ").append(result.exitCode)
                .append(" wallMs: ").append(result.wallMs).append('\n')
            rb.append("--- stdout ---\n").append(result.stdout)
            rb.append("--- stderr ---\n").append(result.stderr)
            assertTrue("mesa runtime apt failed rc=" + result.exitCode + " (see virgl-provision.txt)",
                result.exitCode == 0)

            // Presence assertion from the extracted rootfs (arch-agnostic
            // glob over the multiarch dir), before any probe exec.
            val libDir = File(rootfs, "usr/lib")
            val eglLibs = (libDir.listFiles()?.toList() ?: emptyList())
                .flatMap { sub -> (sub.listFiles()?.toList() ?: emptyList()).filter { it.name == "libEGL.so.1" } }
            rb.append("libEGL.so.1 found: ").append(eglLibs.map { it.absolutePath }).append('\n')
            assertTrue("libEGL.so.1 not found under " + libDir.absolutePath + " after mesa install",
                eglLibs.isNotEmpty())
            rb.append("PROVISION-OK\n")
        } finally {
            report.writeText(rb.toString())
        }
    }
}

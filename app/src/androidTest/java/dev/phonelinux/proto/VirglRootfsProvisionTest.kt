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
 *  the mesa runtime libs eglprobe needs (libegl1, libegl-mesa0,
 *  mesa-libgallium, libgles2, libgl1-mesa-dri). NO GNOME packages,
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
            // keeps the lane small. Chain verified against the CURRENT
            // noble debs (mesa 25.2.8-0ubuntu0.24.04.2, the 25.2 noble
            // backport): libegl1 = GLVND dispatch (Depends: libegl-mesa0
            // DIRECTLY), libegl-mesa0 = vendor libEGL_mesa.so.0 +
            // 50_mesa.json (Depends: mesa-libgallium DIRECTLY, =version),
            // mesa-libgallium = the 43MB gallium megadriver
            // (libgallium-<ver>.so, hard-linked from libEGL_mesa via
            // DT_NEEDED; the virpipe entry lives HERE in 25.2 packaging,
            // verified by byte-scanning the actual amd64 deb: it contains
            // "virpipe", "/tmp/.virgl_test", "GALLIUM_DRIVER"),
            // libgl1-mesa-dri = dri/*.so symlinks -> libdril_dri.so (the
            // new 25.x loader; there is NO virgl_dri.so because virgl's
            // DRM-facing name is virtio_gpu - absence from file lists is
            // NOT evidence of absence). mesa-libgallium is listed
            // EXPLICITLY anyway (defense in depth). Mechanism: mesa tag
            // mesa-25.2.8 src/gallium/auxiliary/target-helpers/
            // sw_helper.h - sw_screen_create_vk reads GALLIUM_DRIVER and
            // maps "virpipe" -> virgl_vtest_winsys_wrap ->
            // virgl_create_screen (#if GALLIUM_VIRGL).
            val cmd = listOf("sh", "-c",
                "apt-get update && apt-get install -y --no-install-recommends libegl1 libegl-mesa0 mesa-libgallium libgles2 libgl1-mesa-dri")
            val result = Proot.runGuest(ctx, rootfs, cmd, fakeRoot = true, timeoutMs = 600_000)
            rb.append("mesa apt exitCode: ").append(result.exitCode)
                .append(" wallMs: ").append(result.wallMs).append('\n')
            rb.append("--- stdout ---\n").append(result.stdout)
            rb.append("--- stderr ---\n").append(result.stderr)
            assertTrue("mesa runtime apt failed rc=" + result.exitCode + " (see virgl-provision.txt)",
                result.exitCode == 0)

            // Presence assertions from the extracted rootfs (arch-agnostic
            // glob over the multiarch dir), before any probe exec: GLVND
            // dispatch + Mesa vendor runtime + vendor json + the dri
            // driver entry used by the surfaceless sw path.
            // Recursive (bounded depth): dri drivers live at
            // usr/lib/<multiarch>/dri/*.so, two levels under usr/lib -
            // a one-level glob misses them (run 41314667337).
            fun glob1(dir: File, name: String): List<File> =
                dir.walkTopDown().maxDepth(4).filter { it.isFile && it.name == name }.toList()
            val libDir = File(rootfs, "usr/lib")
            val eglLibs = glob1(libDir, "libEGL.so.1")
            val eglMesa = glob1(libDir, "libEGL_mesa.so.0")
            val driDrivers = glob1(File(rootfs, "usr/lib"), "swrast_dri.so")
            val vendorJson = File(rootfs, "usr/share/glvnd/egl_vendor.d/50_mesa.json")
            rb.append("libEGL.so.1: ").append(eglLibs.map { it.absolutePath }).append('\n')
            rb.append("libEGL_mesa.so.0: ").append(eglMesa.map { it.absolutePath }).append('\n')
            rb.append("50_mesa.json: ").append(vendorJson.exists()).append('\n')
            rb.append("swrast_dri.so: ").append(driDrivers.map { it.absolutePath }).append('\n')
            assertTrue("libEGL.so.1 (GLVND) missing after mesa install", eglLibs.isNotEmpty())
            assertTrue("libEGL_mesa.so.0 (Mesa EGL vendor runtime) missing after mesa install", eglMesa.isNotEmpty())
            assertTrue("glvnd vendor json missing: " + vendorJson.absolutePath, vendorJson.isFile)
            if (driDrivers.isEmpty()) {
                // Diagnostics for the next packaging move: dump any dri dirs.
                File(rootfs, "usr/lib").walkTopDown().maxDepth(3)
                    .filter { it.isDirectory && it.name == "dri" }
                    .forEach { dd -> rb.append("dri dir ").append(dd.absolutePath)
                        .append(": ").append(dd.list()?.toList()).append('\n') }
            }
            assertTrue("swrast_dri.so (mesa dri driver entry) missing after mesa install (see virgl-provision.txt)", driDrivers.isNotEmpty())
            // Record the ACTUAL installed versions (never hardcode one:
            // noble moved 24.2.8 -> 25.2.8 backport mid-series).
            val status = File(rootfs, "var/lib/dpkg/status")
            if (status.isFile) {
                val pkgs = setOf("libegl1", "libegl-mesa0", "mesa-libgallium",
                    "libgles2", "libgl1-mesa-dri", "libgbm1", "libc6")
                var cur: String? = null
                status.readLines().forEach { l ->
                    when {
                        l.startsWith("Package: ") -> cur = l.removePrefix("Package: ")
                        l.startsWith("Version: ") -> cur?.let { c ->
                            if (c in pkgs) rb.append("dpkg ").append(c).append(" = ")
                                .append(l.removePrefix("Version: ")).append('\n')
                        }
                    }
                }
            } else rb.append("dpkg status missing: ").append(status.absolutePath).append('\n')

            // CAPABILITY evidence (DIAGNOSTIC, not the gate): the vtest
            // socket path is compiled into the virgl vtest winsys, which
            // lives in the gallium megadriver - whose FILE LOCATION moved
            // between mesa packagings (24.2: linked from *_dri.so; 25.2:
            // libgallium-<ver>.so pulled by libEGL_mesa via DT_NEEDED).
            // So resolve the ACTUAL dependency chain: readlink targets of
            // the EGL vendor lib and swrast_dri.so, plus any libgallium-*
            // in the multiarch dirs. Presence of ".virgl_test" in ANY
            // candidate proves vtest code is present; ABSENCE only means
            // location moved again (VIRPIPE-CAPABILITY-UNKNOWN) - the
            // runtime eglprobe (stage 10/20) is the real virpipe gate.
            fun resolved(f: File): File {
                var curf = f; var depth = 0
                while (depth++ < 8) {
                    val target = try {
                        java.nio.file.Files.readSymbolicLink(curf.toPath())
                    } catch (e: Exception) { null } ?: break
                    curf = if (target.isAbsolute) File(rootfs, target.toString().removePrefix("/"))
                          else File(curf.parentFile, target.toString())
                }
                return curf
            }
            fun globPrefix(dir: File, prefix: String): List<File> =
                dir.walkTopDown().maxDepth(4)
                    .filter { it.isFile && it.name.startsWith(prefix) && it.name.contains(".so") }.toList()
            val needle = ".virgl_test".toByteArray()
            val candidates = (listOfNotNull(eglMesa.firstOrNull(), driDrivers.firstOrNull())
                .map { resolved(it) } + globPrefix(libDir, "libgallium-")).distinct()
            var hasVtest = false
            candidates.forEach { c ->
                val found = if (c.isFile) {
                    val bytes = c.readBytes()
                    var hit = false
                    outer@ for (i in 0..bytes.size - needle.size) {
                        for (j in needle.indices) {
                            if (bytes[i + j] != needle[j]) continue@outer
                        }
                        hit = true; break
                    }
                    hit
                } else false
                if (found) hasVtest = true
                rb.append("capability-scan: ").append(c.absolutePath)
                    .append(" size=").append(if (c.isFile) c.length() else -1)
                    .append(" vtest-string=").append(found).append('\n')
            }
            rb.append(if (hasVtest)
                "VIRPIPE-VTEST-CODE-PRESENT (vtest socket string found in the resolved mesa binaries)\n"
                else "VIRPIPE-CAPABILITY-UNKNOWN: vtest socket string not found in resolved candidates " +
                    "(packaging may have moved it); runtime eglprobe stages are the virpipe gate\n")
            rb.append("PROVISION-OK\n")
        } finally {
            report.writeText(rb.toString())
        }
    }
}

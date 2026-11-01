package dev.phonelinux.proto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** VirGL-first GPU spike (bounded experiment, branch gnome/virgl-spike).
 *
 *  Two launches on the SAME emulator: (A) default baseline, (B) opt-in
 *  virgl candidate. The ONLY accepted proof of the virgl TRANSPORT route
 *  is the guest's own glxinfo "OpenGL renderer string" reading
 *  "virgl (<host backend>)"; any other string fails with the actual
 *  renderer - a software fallback must NEVER be labeled a virgl success.
 *  The route is classified from the strings themselves, never assumed:
 *  the baseline is asserted only to be NOT-virgl (it could be software
 *  OR another GPU path; logged either way), and a virgl string with a
 *  SOFTWARE host inside the parentheses (llvmpipe/SwiftShader on the
 *  emulator) is transport proof ONLY - explicitly NOT a GPU-offload
 *  claim. Emulator results are EMULATOR results, not Adreno results.
 *
 *  Lane setup (autoenter) must have provisioned the rootfs; the test
 *  restores the opt-in flag state and stops the session afterwards. */
@RunWith(AndroidJUnit4::class)
class VirglSpikeTest {
    private val TAG = "VirglSpikeTest"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val d = Distros.UBUNTU
    private val rootfs get() = DistroInstaller(ctx).rootfsDir(d)
    private val logFile get() = File(ctx.filesDir, "guest-gnome.log")
    private val flag get() = File(ctx.filesDir, ".ldoa-virgl-optin")

    @After fun cleanup() {
        try { XServer.stopDesktopMode(ctx) } catch (t: Throwable) { }
    }

    private fun window(base: Long): String {
        if (!logFile.isFile) return ""
        val content = logFile.readText()
        val b = if (content.length >= base) base.toInt() else 0
        return content.substring(b.coerceAtMost(content.length))
    }

    /** Launch one session and return its guest glxinfo renderer string.
     *  [virgl] toggles the opt-in flag; pre-existing flag state is
     *  preserved by the caller. Tears the session down before returning. */
    private fun rendererOfLaunch(virgl: Boolean): String {
        if (virgl) flag.writeText("1") else flag.delete()
        SessionReset.resetForFreshLaunch(ctx, d) { Log.i(TAG, it) }
        XServer.start(ctx)
        // CHAR base from one content snapshot (never File.length() bytes).
        val base = (if (logFile.isFile) logFile.readText() else "").length.toLong()
        XServer.launchOrReuseGnomeSession(ctx, d, rootfs)
        val launchId = XServer.currentLaunchIdForTest()
        assertTrue("launch identity unavailable from app - cannot bind readiness", launchId != null)
        var alive = false
        val end = System.currentTimeMillis() + 6 * 60_000
        while (!alive && System.currentTimeMillis() < end) {
            val w = window(base)
            val idIdx = w.lastIndexOf("gnome-launch: launch id=" + launchId)
            val aliveIdx = w.lastIndexOf("gnome-launch: gnome-session alive pid=")
            alive = idIdx >= 0 && aliveIdx > idIdx
            if (!alive) Thread.sleep(2_000)
        }
        assertTrue("session did not reach THIS launch's alive marker (virgl=" + virgl + "): " +
            window(base).takeLast(400), alive)

        // Guest-side renderer proof: glxinfo inside the same proot world,
        // with the vtest socket bound for the virgl launch.
        val sb = StringBuilder()
        val sock = File(XServer.tmpDir(ctx), "virgl/virgl_test.sock")
        val binds = if (virgl && sock.exists()) listOf(sock.absolutePath to "/tmp/.virgl_test") else emptyList()
        val env = if (virgl) mapOf("GALLIUM_DRIVER" to "virpipe") else emptyMap()
        Proot.runGuest(ctx, rootfs, listOf("sh", "-c",
            "apt-get install -y mesa-utils >/dev/null 2>&1; DISPLAY=:0 glxinfo -B 2>&1 | grep -E 'OpenGL renderer string|Accelerated'"),
            extraBinds = binds, extraEnv = env, fakeRoot = true,
            onLine = { sb.append(it).append('\n') }, timeoutMs = 300_000)
        XServer.stopDesktopMode(ctx)
        val renderer = sb.lineSequence().firstOrNull { it.contains("OpenGL renderer string") }
        assertNotNull("glxinfo produced no renderer line (virgl=" + virgl + "): " + sb.toString().takeLast(300), renderer)
        Log.i(TAG, "RENDERER virgl=" + virgl + ": " + renderer)
        return renderer!!
    }

    @Test fun virglLaunchRoutesThroughVirpipeNotSoftware() {
        val savedFlag = if (flag.isFile) flag.readText() else null
        try {
            val baseline = rendererOfLaunch(virgl = false)
            // Baseline asserts NOT-virgl only: "not virgl" does NOT imply
            // software (another GPU path would also pass) - classify from
            // the actual string, never assume.
            assertTrue("baseline launch must NOT route through virgl (actual: " + baseline + ")",
                !baseline.contains("virgl", ignoreCase = true))
            val soft = Regex("llvmpipe|softpipe|swiftshader", RegexOption.IGNORE_CASE)
            Log.i(TAG, "BASELINE-CLASSIFICATION: " +
                (if (soft.containsMatchIn(baseline)) "software" else "NOT-virgl, NOT a known software string (unknown/other)") +
                " renderer=[" + baseline + "]")
            val candidate = rendererOfLaunch(virgl = true)
            assertTrue("opt-in launch must render through virpipe; any other string means the virgl route FAILED (actual: " + candidate + ")",
                candidate.contains("virgl ("))
            // "virgl (<host>)" proves the TRANSPORT only. The host string
            // inside the parentheses classifies offload separately: a
            // software host is NOT a GPU success.
            val host = candidate.substringAfter("virgl (").substringBefore(")")
            Log.i(TAG, "VIRGL-HOST-CLASSIFICATION: " +
                (if (soft.containsMatchIn(host))
                    "virgl transport PROVEN; host backend SOFTWARE (" + host + ") - NO GPU offload claim"
                 else "virgl transport proven; host backend candidate GPU/unknown (" + host + ")"))
            Log.i(TAG, "VIRGL-SPIKE-OK baseline=[" + baseline + "] candidate=[" + candidate + "] (emulator, NOT Adreno)")
        } finally {
            if (savedFlag != null) flag.writeText(savedFlag) else flag.delete()
        }
    }
}

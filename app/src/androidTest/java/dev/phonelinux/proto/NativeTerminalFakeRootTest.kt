package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native terminal fake-root alignment (POCO field evidence 2026-10-30):
 * the in-app Terminal ran the guest WITHOUT proot -0, so `id` showed the
 * real app uid and dpkg denied installs with "requested operation requires
 * superuser privilege" while apt itself could still download (rootfs files
 * are owned by the app uid). With -0 the guest must observe uid 0 and
 * dpkg's install-path privilege check must accept it.
 *
 * The privilege probe is NON-mutating: it builds a trivial local .deb and
 * runs `dpkg --no-act -i` (dry run - no package database writes), then
 * removes its temp files.
 */
@RunWith(AndroidJUnit4::class)
class NativeTerminalFakeRootTest {

    @Test
    fun nativeTerminalRunsAsFakeRootAndDpkgAccepts(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = Distros.UBUNTU
        val installer = DistroInstaller(ctx)
        if (!installer.isInstalled(d)) {
            var last: InstallState = InstallState.NotInstalled
            val ok = installer.install(d) { last = it }
            assertTrue("ubuntu install failed at state=$last", ok)
        }
        val rootfs = installer.rootfsDir(d)

        val term = TerminalSession(ctx, rootfs) {}
        try {
            assertTrue("terminal session failed to start", term.start())
            fun await(marker: String, budgetMs: Long): String {
                val deadline = System.currentTimeMillis() + budgetMs
                while (System.currentTimeMillis() < deadline) {
                    val s = term.snapshot()
                    if (s.contains(marker)) return s
                    Thread.sleep(500)
                }
                return term.snapshot()
            }

            term.send("id")
            val idOut = await("uid=", 15_000)
            assertTrue("no id output in terminal scrollback:\n$idOut", idOut.contains("uid="))
            // Non-brittle: accept uid=0(root) or any group list; reject uid=10494 etc.
            assertTrue("guest shell must observe uid 0 under proot -0, got:\n$idOut",
                Regex("uid=0\\D").containsMatchIn(idOut))

            // Every stage's output is echoed into the scrollback so a
            // failure names its own cause: BUILD-RC isolates a probe-deb
            // construction defect from a dpkg environment problem, and the
            // captured outputs make the superuser-denial absence check
            // meaningful (review: redirected-then-deleted stderr proved
            // nothing). Markers bracket the dumps so the await is exact.
            term.send("rm -rf /tmp/probe /tmp/probe.deb /tmp/probe.out /tmp/probe-build.out; mkdir -p /tmp/probe/DEBIAN && " +
                "printf 'Package: probe\\nVersion: 1\\nArchitecture: all\\nMaintainer: t\\nDescription: probe\\n' > /tmp/probe/DEBIAN/control && " +
                "dpkg-deb --build /tmp/probe /tmp/probe.deb >/tmp/probe-build.out 2>&1; echo BUILD-RC=\$?; " +
                "dpkg --no-act -i /tmp/probe.deb >/tmp/probe.out 2>&1; echo PROBE-RC=\$?; " +
                "echo PROBE-DUMP-BEGIN; cat /tmp/probe-build.out; cat /tmp/probe.out; echo PROBE-DUMP-END; " +
                "rm -rf /tmp/probe /tmp/probe.deb /tmp/probe.out /tmp/probe-build.out")
            val probeOut = await("PROBE-DUMP-END", 60_000)
            assertTrue("dpkg probe never completed:\n$probeOut", probeOut.contains("PROBE-RC="))
            assertTrue("probe .deb failed to build (test fixture defect, not a product result):\n$probeOut",
                probeOut.contains("BUILD-RC=0"))
            assertTrue("dpkg install-path privilege check must accept proot fake root (rc 0); scrollback:\n$probeOut",
                probeOut.contains("PROBE-RC=0"))
            assertTrue("superuser denial must be gone:\n$probeOut",
                !probeOut.contains("requires superuser privilege"))
        } finally {
            term.close()
        }
    }
}

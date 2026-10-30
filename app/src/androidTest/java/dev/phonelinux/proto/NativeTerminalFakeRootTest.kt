package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
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

    // JUnit timeout: convert any unbounded wait (install, session start,
    // guest call) into an attributed failure with the stuck stack instead
    // of a silent invocation stall. 20 min covers a fresh rootfs install.
    @Test(timeout = 1_200_000)
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

        Log.i("NativeFakeRoot", "ENTER nativeTerminalRunsAsFakeRootAndDpkgAccepts")
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
            // Environment-vs-fixture discrimination, staged by cost:
            // (1) guest VERSION capture first: the ar.c:79 creat() claim
            // comes from dpkg 1.22.6ubuntu6 source (noble release); fixture
            // apt may have pulled newer, and glibc\'s creat() wrapper maps
            // to the creat syscall on x86_64 (observed in host strace,
            // glibc 2.35) - both need guest-version confirmation before
            // the source claim applies.
            // (2) PRIMARY probe (ptrace-free, no apt): perl syscall()
            // issues raw creat(2)=85, openat(2)=257, close(2)=3 (numbers
            // verified against asm/unistd_64.h on x86_64, not recall) and
            // reports each return/errno. This distinguishes SYSCALL-SPECIFIC rejection
            // only - it cannot separate Android-seccomp from
            // proot-mediation (both sit on the same execution path).
            // (3) strace runs ONLY if perl is absent AND its own
            // capability gate passes (proot 5.1.107 ptrace emulation is
            // partial) - avoiding the apt cost in the common path.
            term.send("dpkg-query -W dpkg 2>/dev/null || dpkg-deb --version | head -1; ldd --version | head -1; uname -m; uname -a")
            await("uname -a", 30_000)
            term.send("if [ \"$(uname -m)\" != x86_64 ]; then echo SYSCALL-PROBE-SKIP-ARCH; elif command -v perl >/dev/null 2>&1; then perl -e \'\$fd=syscall(85,\"/tmp/ct\",0644); \$e=\$!+0; if (\$fd>=0) { syscall(3,\$fd); \$e=0; } print \"CREAT-RC=\$fd CREAT-ERRNO=\$e \"; \$fd=syscall(257,-100,\"/tmp/ct2\",1|64|512,0644); \$e=\$!+0; if (\$fd>=0) { syscall(3,\$fd); \$e=0; } print \"OPENAT-RC=\$fd OPENAT-ERRNO=\$e\n\";\'; rm -f /tmp/ct /tmp/ct2; else echo PERL-ABSENT; fi")
            await("OPENAT-RC=", 30_000)
            // strace fallback: ONLY when perl is absent.
            term.send("if command -v perl >/dev/null 2>&1; then echo STRACE-SKIPPED-PERL-OK; else apt-get install -y strace >/tmp/strace-install.out 2>&1; echo STRACE-INSTALL-RC=\$?; if command -v strace >/dev/null 2>&1; then strace -f true >/dev/null 2>/tmp/strace-cap.out; echo STRACE-CAP-RC=\$?; else echo STRACE-CAP-RC=no-binary; fi; fi")
            await("STRACE-", 120_000)
            term.send("rm -rf /tmp/probe /tmp/probe.deb /tmp/probe.out /tmp/probe-build.out /tmp/probe.strace; mkdir -p /tmp/probe/DEBIAN && chmod 755 /tmp/probe /tmp/probe/DEBIAN && " +
                "printf 'Package: probe\\nVersion: 1\\nArchitecture: all\\nMaintainer: t\\nDescription: probe\\n' > /tmp/probe/DEBIAN/control && " +
                "if [ -s /tmp/probe-build.out ]; then :; fi; " +
                "if command -v strace >/dev/null 2>&1 && ! grep -q 'Operation not permitted\\|ENOSYS\\|error' /tmp/strace-cap.out 2>/dev/null; then strace -f -o /tmp/probe.strace dpkg-deb --build /tmp/probe /tmp/probe.deb >/tmp/probe-build.out 2>&1; else dpkg-deb --build /tmp/probe /tmp/probe.deb >/tmp/probe-build.out 2>&1; fi; echo BUILD-RC=\$?; " +
                "dpkg --no-act -i /tmp/probe.deb >/tmp/probe.out 2>&1; echo PROBE-RC=\$?; " +
                "echo PROBE-DUMP-BEGIN; cat /tmp/probe-build.out; cat /tmp/probe.out; " +
                "echo STRACE-DUMP-BEGIN; grep -E 'ENOSYS|probe.deb' /tmp/probe.strace 2>/dev/null | tail -8 || echo no-strace; echo STRACE-DUMP-END; echo PROBE-DUMP-END; " +
                "rm -rf /tmp/probe /tmp/probe.deb /tmp/probe.out /tmp/probe-build.out /tmp/probe.strace /tmp/strace-install.out")
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

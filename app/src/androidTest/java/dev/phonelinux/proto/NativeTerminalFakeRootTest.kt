package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import java.io.File
import java.security.MessageDigest
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
            // (3) strace runs ONLY if the syscall probe did not COMPLETE
            // (probe.done marker file, not perl presence) AND its own
            // capability gate passes (proot 5.1.107 ptrace emulation is partial).
            term.send("dpkg-query -W dpkg 2>/dev/null || dpkg-deb --version | head -1; ldd --version | head -1; uname -m; uname -a")
            await("uname -a", 30_000)
            term.send("PD=\$(mktemp -d /tmp/native-probe.XXXXXX) && cd \"\$PD\" && echo PROBE-DIR-OK || echo PROBE-DIR-FAILED; if [ \"\$(uname -m)\" != x86_64 ]; then echo SYSCALL-PROBE-SKIP-ARCH; elif command -v perl >/dev/null 2>&1; then perl -e '\$p=\"ct\"; \$fd=syscall(85,\$p,0644); \$e=\$!+0; if (\$fd>=0) { syscall(3,\$fd); \$e=0; } print \"CREAT-RC=\$fd CREAT-ERRNO=\$e \"; \$q=\"ct2\"; \$fd=syscall(257,-100,\$q,1|64|512,0644); \$e=\$!+0; if (\$fd>=0) { syscall(3,\$fd); \$e=0; } print \"OPENAT-RC=\$fd OPENAT-ERRNO=\$e\\n\"; open(F,\">probe.done\") and close(F);' 2>&1; else echo PERL-ABSENT; fi; [ -f probe.done ] && echo SYSCALL-PROBE-DONE || echo SYSCALL-PROBE-INCOMPLETE")
            await("OPENAT-RC=", 30_000)
            // strace fallback: ONLY when the syscall probe did not COMPLETE (marker-file gated).
            term.send("if [ -f probe.done ]; then echo STRACE-SKIPPED-PROBE-COMPLETE; else apt-get install -y strace >strace-install.out 2>&1; echo STRACE-INSTALL-RC=\$?; if command -v strace >/dev/null 2>&1; then strace -f true >/dev/null 2>strace-cap.out; echo STRACE-CAP-RC=\$?; else echo STRACE-CAP-RC=no-binary; fi; fi")
            await("STRACE-", 120_000)
            term.send("mkdir -p probe/DEBIAN && chmod 755 probe probe/DEBIAN && printf 'Package: probe\\nVersion: 1\\nArchitecture: all\\nMaintainer: t\\nDescription: probe\\n' > probe/DEBIAN/control && if [ ! -f probe.done ] && command -v strace >/dev/null 2>&1 && ! grep -q 'Operation not permitted\\|ENOSYS\\|error' strace-cap.out 2>/dev/null; then strace -f -o probe.strace dpkg-deb --build probe probe.deb >probe-build.out 2>&1; else dpkg-deb --build probe probe.deb >probe-build.out 2>&1; fi; echo BUILD-RC=\$?; dpkg --no-act -i probe.deb >probe.out 2>&1; echo PROBE-RC=\$?; echo PROBE-DUMP-BEGIN; cat probe-build.out; cat probe.out; echo STRACE-DUMP-BEGIN; grep -E 'ENOSYS|probe.deb' probe.strace 2>/dev/null | tail -8 || echo no-strace; echo STRACE-DUMP-END; echo PROBE-DUMP-END; cd /; rm -rf \"\$PD\"")
            val probeOut = await("PROBE-DUMP-END", 60_000)
            assertTrue("dpkg probe never completed:\n$probeOut", probeOut.contains("PROBE-RC="))
            // KNOWN x86 CI-ENVIRONMENT DIVERGENCE (measured, run 41074902090):
            // raw creat(2) returns ENOSYS in the guest while openat(2) succeeds;
            // dpkg-deb --build (ar.c:79 creat path) therefore fails on x86 Android.
            // Leading hypothesis: Android app seccomp allowlist (bionic SYSCALLS.TXT
            // has openat, no creat); proot source passes creat through after path
            // translation. arm64 has no raw creat syscall at ABI level; the device
            // path is untested. BUILD-RC/PROBE-RC are captured for drift detection
            // and deliberately NOT gated here - install acceptance is measured by
            // the host-built fixture stage below.
            Log.i("NativeFakeRoot", "creat-divergence capture: BUILD-RC/PROBE-RC in scrollback, non-gating")

            // TRACK 1 - original A intent: dpkg INSTALL acceptance under fake root,
            // via a host-built test-owned fixture .deb (no in-guest archive build,
            // so the creat divergence cannot contaminate the measurement).
            val fixtureSha = "3b09408e18dfd531913287e9de863f00d17bee5158642e2d0390f03aa892e599"
            // test APK assets live on the INSTRUMENTATION context, not targetContext
            val fixtureBytes = InstrumentationRegistry.getInstrumentation().context.assets.open("ldoa_probe_fixture.deb").readBytes()
            val gotSha = MessageDigest.getInstance("SHA-256").digest(fixtureBytes)
                .joinToString("") { "%02x".format(it) }
            assertTrue("fixture .deb SHA256 mismatch (tampered or rebuilt): got $gotSha",
                gotSha == fixtureSha)
            val fixtureDir = File(rootfs, "tmp/ldoa-probe-fixture").apply { mkdirs() }
            File(fixtureDir, "fixture.deb").writeBytes(fixtureBytes)
            term.send("dpkg -i /tmp/ldoa-probe-fixture/fixture.deb >/tmp/ldoa-probe-fixture/install.out 2>&1; echo INSTALL-RC=\$?; " +
                "dpkg -s ldoa-probe-fixture 2>&1 | grep -E '^(Package|Status|Version):'; " +
                "echo PAYLOAD-BEGIN; cat /opt/ldoa-probe-fixture/hello.txt 2>&1; echo PAYLOAD-END; " +
                "echo INSTALL-DUMP-BEGIN; cat /tmp/ldoa-probe-fixture/install.out; echo INSTALL-DUMP-END; " +
                "dpkg -r ldoa-probe-fixture >/dev/null 2>&1; echo REMOVE-RC=\$?; rm -rf /tmp/ldoa-probe-fixture")
            val instOut = await("REMOVE-RC=", 120_000)
            assertTrue("fixture install stage never completed:\n$instOut", instOut.contains("REMOVE-RC="))
            assertTrue("dpkg -i fixture must succeed under proot fake root (original A intent):\n$instOut",
                instOut.contains("INSTALL-RC=0"))
            assertTrue("superuser denial must be gone:\n$instOut",
                !instOut.contains("requires superuser privilege"))
            assertTrue("package status must be installed:\n$instOut",
                instOut.contains("Status: install ok installed"))
            assertTrue("payload file must be readable:\n$instOut",
                instOut.contains("ldoa-probe-fixture payload v1"))
            assertTrue("owned package cleanup must succeed:\n$instOut",
                instOut.contains("REMOVE-RC=0"))
        } finally {
            term.close()
            // Android-side copy of the fixture: test-owned dir, safe to drop
            // even when an assertion above failed (leaves guest-side cleanup
            // to the emulator's ephemeral fs).
            try { File(rootfs, "tmp/ldoa-probe-fixture").deleteRecursively() } catch (_: Throwable) {}
        }
    }
}

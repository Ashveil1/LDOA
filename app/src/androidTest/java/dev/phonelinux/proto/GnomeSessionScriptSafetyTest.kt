package dev.phonelinux.proto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural safety invariants for the GNOME session script's system-bus
 * robustness + watchdog managed-tree teardown. Phone evidence 2026-10-31
 * (launch 1793454731 and ZIPs 3/4): the system bus died mid-evening;
 * gnome-session hit gsm-systemd.c:382 g_error (exited=133) and the
 * fallback shell's LoginManagerSystemd constructor threw on the dead
 * bus (main.js exception, shell died 4s after registering). Earlier
 * rounds: the watchdog killed the strace WRAPPER, not gnome-session
 * (tracees detach and continue), so no fallback ever ran and managed
 * components survived as orphans.
 *
 * Process-level behavior (wrapper+child teardown, stale/healthy/failed
 * bus, fail-closed suppression) is fixture-verified with real processes
 * outside CI; these tests pin the shipped script's structure so the
 * invariants cannot silently regress.
 */
class GnomeSessionScriptSafetyTest {

    private val script = GuestUbuntu.gnomeSessionScript()

    @Test fun systemBusProbePrecedesAnySocketRemoval() {
        val probe = script.indexOf("sysbus_live()")
        val unavailable = script.indexOf("system-bus listener UNAVAILABLE")
        val removal = script.indexOf("""rm -f "${'$'}DBUS_RUN_DIR/system_bus_socket"""")
        assertTrue("liveness probe function must exist", probe >= 0)
        assertTrue("unavailable branch must exist", unavailable >= 0)
        assertTrue("stale-removal must exist", removal >= 0)
        assertTrue("probe definition must precede removal", probe < removal)
        assertTrue("removal only inside the probe-proven unavailable branch", unavailable < removal)
    }

    @Test fun healthySystemBusIsPreservedUntouched() {
        assertTrue(script.contains("system-bus LIVE - preserving existing daemon (socket/pidfile untouched)"))
    }

    @Test fun busStartOutputIsCapturedNotSwallowed() {
        assertFalse("dbus-daemon --system stderr must not be swallowed",
            script.contains("dbus-daemon --system --fork 2>/dev/null"))
        assertTrue(script.contains("system-bus start rc="))
    }

    @Test fun pidfileIdentityIsValidatedBeforeRemoval() {
        assertTrue(script.contains("system-bus pidfile pid="))
        assertTrue("identity validation needs starttime and uid",
            script.contains("starttime=") && script.contains("uid="))
    }

    @Test fun noNameBasedKillsAnywhere() {
        assertFalse("pkill is a name-based kill - forbidden outside the owned tree",
            Regex("""(^|\s|/)pkill(\s|${'$'})""").containsMatchIn(script))
        assertFalse("killall is a name-based kill - forbidden outside the owned tree",
            Regex("""(^|\s|/)killall(\s|${'$'})""").containsMatchIn(script))
    }

    @Test fun watchdogSnapshotsTreeBeforeKilling() {
        val snap = script.indexOf("""SNAP=${'$'}(snapshot_tree""")
        val kill = script.indexOf("""kill_tree_verified "${'$'}SNAP" TERM""")
        assertTrue("tree snapshot must exist", snap >= 0)
        assertTrue("verified tree kill must exist", kill >= 0)
        assertTrue("snapshot must precede any kill", snap < kill)
    }

    @Test fun killsReverifyPidAndStarttime() {
        assertTrue(script.contains("(identity verified)"))
        assertTrue("member liveness must compare pid AND starttime",
            script.contains("""[ "${'$'}(pid_starttime "${'$'}1")" = "${'$'}2" ]"""))
    }

    @Test fun zombiesCountAsDeadInSurvivorChecks() {
        assertTrue(script.contains("""[ "${'$'}(pid_state "${'$'}1")" = "Z" ] && return 1"""))
    }

    @Test fun fallbackIsSuppressedWhenTeardownIncomplete() {
        val verdict = script.indexOf("fallback shell missing after 90s (not attempted: managed teardown incomplete")
        val check = script.indexOf("[ -f /tmp/xdg/ldoa-teardown-failed ]")
        val exec = script.indexOf("exec gnome-shell --x11")
        assertTrue("gate-matched fail-closed verdict must exist", verdict >= 0)
        assertTrue("suppression check must exist", check >= 0)
        assertTrue("fallback exec must exist", exec >= 0)
        assertTrue("suppression check must precede the fallback exec", check < exec)
    }

    @Test fun staleFailClosedFlagClearedAtLaunchStart() {
        val clear = script.indexOf("rm -f /tmp/xdg/ldoa-teardown-failed")
        val launch = script.indexOf("gnome-session --session=ubuntu")
        assertTrue("flag clear must exist", clear >= 0)
        assertTrue("flag clear must precede the session launch", launch >= 0 && clear < launch)
    }

    @Test fun mainFlowWaitsForWatchdogInsteadOfKillingIt() {
        assertFalse("killing the watchdog subshell can interrupt a mid-flight teardown before the fail-closed marker lands",
            script.contains("""kill ${'$'}POLL_A_PID ${'$'}WATCHDOG_PID"""))
        assertTrue("main flow must wait for the watchdog before the suppression check",
            script.contains("""wait ${'$'}WATCHDOG_PID"""))
    }

    @Test fun watchdogLoopExitsWhenSessionIsGone() {
        assertTrue("ping loop must exit when the wrapper is gone (bounds the main flow's wait)",
            script.contains("""kill -0 ${'$'}GSPID 2>/dev/null || exit 0"""))
    }

    @Test fun diagnosticProbesPresent() {
        assertTrue("installed-version probe must exist", script.contains("dpkg-query -W -f="))
        assertTrue("sampler system-bus liveness probe must exist", script.contains("sysbus_live="))
    }
}

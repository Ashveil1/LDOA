package dev.phonelinux.proto

import org.junit.Assert.assertEquals
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
 * Process-level behavior (wrapper+child teardown, stale/healthy/failed/
 * busy bus, fail-closed suppression) is fixture-verified with real
 * processes outside CI; these tests pin the shipped script's structure
 * so the invariants cannot silently regress. They do NOT replace the
 * executed fixtures.
 */
class GnomeSessionScriptSafetyTest {

    private val script = GuestUbuntu.gnomeSessionScript()

    @Test fun systemBusProbePrecedesAnySocketRemoval() {
        val probe = script.indexOf("sysbus_probe() {")
        val removal = script.indexOf("rm -f \"\$DBUS_RUN_DIR/system_bus_socket\"")
        assertTrue("endpoint-classifying probe function must exist", probe >= 0)
        assertTrue("stale-removal must exist (inside start_system_bus)", removal >= 0)
        assertTrue("probe definition must precede removal", probe < removal)
        assertEquals("exactly one socket/pidfile removal site", 1,
            script.split("system_bus_socket\" \"\$DBUS_RUN_DIR/pid").size - 1)
    }

    /** The probe must classify the ENDPOINT at connect level, not return a
     *  boolean: a failed/timed-out ListNames is not proof of a dead daemon.
     *  ECONNREFUSED/ENOENT ground endpoint death; timeout/auth/handshake
     *  means UNKNOWN and must preserve. */
    @Test fun probeClassifiesEndpointAtConnectLevel() {
        val fn = script.indexOf("sysbus_probe() {")
        val refused = script.indexOf("*\"Connection refused\"*) echo \"REFUSED|")
        val noent = script.indexOf("*\"No such file or directory\"*) echo \"NOENT|")
        assertTrue("probe function must exist", fn >= 0)
        assertTrue("ECONNREFUSED classification must exist", refused > fn)
        assertTrue("ENOENT classification must exist", noent > fn)
        assertTrue("probe log must carry class and bounded raw detail",
            script.contains("sysbus_probe_log() { echo \"gnome-launch: system-bus probe=\$1 detail=\$2\"; }"))
        assertTrue("a LIVE probe must never log the name list (no detail emitted)",
            script.contains("\"(\") echo \"LIVE|\"") || script.contains("'('*) echo \"LIVE|\""
            ) || script.contains("\"(\"*) echo \"LIVE|\""))
    }

    /** A timeout/auth/handshake probe (OTHER) leaves the state UNKNOWN:
     *  one bounded retry, then FAIL CLOSED with the endpoint preserved.
     *  An unresponsive endpoint may still be listening - never unlink. */
    @Test fun unknownProbeStateFailsClosedWithoutUnlink() {
        val retryLive = script.indexOf("system-bus LIVE after retry (initial probe was unresponsive) - preserved")
        val unknown = script.indexOf("system-bus state UNKNOWN after retry (probe=\$P - an unresponsive endpoint may be listening) - FAIL CLOSED: endpoint preserved (no unlink/restart)")
        assertTrue("unresponsive-recovery verdict must exist", retryLive >= 0)
        assertTrue("UNKNOWN fail-closed verdict must exist", unknown >= 0)
        assertTrue("recovery is attempted before failing closed", retryLive < unknown)
    }

    /** ECONNREFUSED with a LIVE pidfile dbus-daemon: wedged or mid-restart
     *  daemon. One bounded retry, then fail closed - never unlink a live
     *  daemon's endpoint. */
    @Test fun liveDaemonEndpointIsPreservedWhenProbeRefused() {
        val branch = script.indexOf("dbus-daemon*)")
        val retry = script.indexOf("system-bus LIVE after retry - preserved pid=")
        val failClosed = script.indexOf("alive but socket not listening after retry (probe=\$P) - FAIL CLOSED: endpoint preserved (no unlink/restart)")
        assertTrue("live-daemon branch must exist", branch >= 0)
        assertTrue("bounded retry verdict must exist", retry >= 0)
        assertTrue("fail-closed verdict must exist", failClosed >= 0)
        assertTrue("retry and fail-closed belong to the live-daemon branch",
            branch < retry && branch < failClosed)
        assertEquals("start_system_bus: one definition + exactly three call sites (REFUSED+stale, REFUSED+no-daemon, ENOENT+no-daemon)",
            4, script.split("start_system_bus").size - 1)
    }

    /** A stale pidfile (live UNRELATED process) plus a grounded-dead
     *  endpoint (ECONNREFUSED) may be cleared; the referenced process
     *  itself is never killed. */
    @Test fun stalePidfileBranchGroundsEndpointDeath() {
        assertTrue(script.contains("pidfile stale (pid="))
        assertTrue(script.contains("not a daemon) and socket not listening (ECONNREFUSED)"))
    }

    /** ENOENT with a live pidfile daemon: the daemon may be about to bind.
     *  Fail closed rather than race it with a second daemon. */
    @Test fun enoentWithLiveDaemonDoesNotRace() {
        assertTrue(script.contains("system-bus socket missing (ENOENT) but pidfile daemon pid="))
        assertTrue(script.contains("FAIL CLOSED: not racing a live daemon (no new daemon started)"))
        assertTrue("ENOENT with no live daemon starts fresh",
            script.contains("system-bus endpoint absent (ENOENT), no live pidfile daemon - starting a fresh daemon"))
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

package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural safety invariants for the GNOME session script's system-bus
 * robustness + the non-destructive observer gate. Phone evidence 2026-10-31
 * (launch 1793454731 and ZIPs 3/4): the system bus died mid-evening;
 * gnome-session hit gsm-systemd.c:382 g_error (exited=133) and the
 * fallback shell's LoginManagerSystemd constructor threw on the dead
 * bus (main.js exception, shell died 4s after registering). Earlier
 * rounds: the watchdog killed the strace WRAPPER, not gnome-session
 * (tracees detach and continue), so no fallback ever ran and managed
 * components survived as orphans.
 *
 * Process-level behavior (observation without kill, survivor-suppressed fallback,
 * busy bus, fail-closed suppression) is fixture-verified with real
 * processes outside CI; these tests pin the shipped script's structure
 * so the invariants cannot silently regress. They do NOT replace the
 * executed fixtures.
 */
class GnomeSessionScriptSafetyTest {

    private val script = GuestUbuntu.gnomeSessionScript()

    /** E1 diet experiment is OPT-IN: masks gated behind
     *  LDOA_SESSION_DIET_E1 (default 0); flag-off branch rolls back ONLY
     *  manifest-verified ldoa-managed files; the subset is EXACTLY
     *  Wwan + Smartcard. REQUIRED-LIST TRUTH (run 41348620342 census):
     *  Wwan is absent from the candidate-effective RequiredComponents;
     *  Smartcard IS listed there - E1 removes a REQUIRED component
     *  (product impact unevaluated; observed shadowing on the classic
     *  path, not a contract) - NO broad accessibility/power/input/audio
     *  suppression for a green test. */
    @Test fun e1DietIsOptInBoundedSubsetWithVerifiedRollback() {
        val gate = script.indexOf("\${LDOA_SESSION_DIET_E1:-0}")
        assertTrue("E1 gate must read LDOA_SESSION_DIET_E1 with default 0", gate >= 0)
        assertTrue("rollback helper must exist", script.contains("ldoa_remove_managed() {"))
        assertTrue("rollback must verify the manifest sha before removal",
            script.contains("ROLLBACK-BLOCKED") && script.contains("manifest-verified"))
        assertTrue("unknown/unmanaged content must be preserved on rollback",
            script.contains("ROLLBACK-SKIPPED"))
        assertTrue("E1 masks carry the E1 ownership marker",
            script.contains("# ldoa-managed E1:"))
        assertTrue("put must refuse non-E1-marked pre-existing files at E1 paths",
            script.contains("BLOCKED-E1-OWNERSHIP"))
        assertTrue("symlinks must never be followed or removed on rollback",
            script.contains("SYMLINK"))
        assertTrue("rollback failure must be loud and must not claim stock restored",
            script.contains("ROLLBACK INCOMPLETE") && script.contains("stock NOT"))
        assertTrue("flag-off lifetime must be documented (next launch, running session untouched)",
            script.contains("next launch"))
        assertTrue("install-side failures must set rc and report PARTIAL/BLOCKED",
            script.contains("PARTIAL/BLOCKED"))
        assertTrue("put must reject symlinks and foreign manifests before any content check",
            script.contains("never followed or overwritten") && script.contains("never trusted"))
        val e1 = script.substring(gate)
        val loop = e1.substring(0, e1.indexOf("done"))
        assertTrue("E1 mask loop covers exactly Wwan + Smartcard",
            loop.contains("for A in Wwan Smartcard; do"))
        for (banned in listOf("A11y", "Power", "Keyboard", "Sound", "XSettings",
                              "MediaKeys", "ScreensaverProxy", "Housekeeping"))
            assertTrue("E1 must not mask " + banned, !loop.contains(banned))
    }

    /** Every E1 write target lives under LDOA_AUTO_DIR (user autostart
     *  masks); stock /etc/xdg and packaged .session files are never
     *  written by the E1 block. */
    @Test fun e1WritesOnlyManagedAutostartMasks() {
        val gate = script.indexOf("\${LDOA_SESSION_DIET_E1:-0}")
        assertTrue(gate >= 0)
        val end = script.indexOf("gnome-launch: exclusion-state", gate)
        assertTrue("exclusion-state marker must follow the E1 block", end > gate)
        val e1 = script.substring(gate, end)
        assertTrue("E1 block must derive its target and manifest vars from LDOA_AUTO_DIR",
            e1.contains("T=\"\$LDOA_AUTO_DIR/") && e1.contains("M=\"\$LDOA_AUTO_DIR/"))
        for (ln in e1.lineSequence().filter { it.contains("ldoa_put_managed") || it.contains("ldoa_remove_managed") }) {
            assertTrue("E1 managed-file target must live under LDOA_AUTO_DIR: " + ln.trim(),
                ln.contains("\$LDOA_AUTO_DIR/") || ln.contains("\"\$T\"") || ln.contains("\"\$M\""))
        }
        assertTrue("E1 must not write stock /etc/xdg or packaged session files",
            !e1.contains("/etc/xdg/") && !e1.contains("/usr/share/gnome-session") &&
            !e1.contains("/usr/local/share/gnome-session"))
    }

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

    /** The old tree-kill invariants (snapshot-before-kill, identity
     *  re-verification per member) retired WITH the kill machinery - the
     *  observer design contains no in-script kill path at all
     *  (observerNeverKillsTheSession pins that). What must still hold: the
     *  observer's work is bounded by default, so a never-registering
     *  session cannot leave an infinite poll behind. */
    @Test fun observerCapHasBoundedDefault() {
        assertTrue("observer cap must have a bounded default (no unconditional infinite poll)",
            script.contains("OBS_CAP=${'$'}{LDOA_WD_OBSERVER_S:-600}"))
        assertTrue("observer loop must compare against the cap",
            script.contains("-lt ${'$'}OBS_CAP"))
    }

    /** The old in-script tree teardown is gone (observer design): survivor
     *  verification on the actual-exit path is a bounded name-READ with one
     *  re-check, and suppression is the fail-closed outcome - never a kill. */
    @Test fun exitPathSurvivorCheckIsBoundedAndNonDestructive() {
        val i = script.indexOf("S=0; SURV=")
        assertTrue("bounded survivor re-check loop must exist", i >= 0)
        assertTrue("re-check bound must exist", script.contains("[ ${'$'}S -lt 2 ]"))
        assertFalse("survivor handling must not kill by name", script.contains("pkill"))
    }

    @Test fun fallbackIsSuppressedWhenSessionComponentsSurviveExit() {
        val check = script.indexOf("pgrep -f 'gnome-session-binary|gnome-shell'")
        val verdict = script.indexOf("fallback suppressed - possibly surviving session components after the session exit")
        val exec = script.indexOf("exec gnome-shell --x11")
        assertTrue("survivor name-READ (never a name-kill) must exist", check >= 0)
        assertTrue("survivor-suppression verdict must exist", verdict >= 0)
        assertTrue("fallback exec must exist", exec >= 0)
        assertTrue("survivor check must precede the fallback exec", check >= 0 && check < exec && verdict < exec)
        assertTrue("one bounded re-check absorbs the shutdown ordering race",
            script.contains("S=0; SURV=") && script.contains("sleep 3; S="))
    }

    /** The observer (post-deadline) must contain NO kill machinery: a slow
     *  but healthy startup (phone 2026-10-31: registered +75s warm; the
     *  user's late-success session) must never be torn down by a script
     *  heuristic. Stop belongs to the user. */
    @Test fun observerNeverKillsTheSession() {
        assertFalse("no managed teardown machine", script.contains("managed_teardown"))
        assertFalse("no tree-kill helper", script.contains("kill_tree_verified"))
        assertFalse("no tree snapshot helper", script.contains("snapshot_tree"))
        assertFalse("no teardown fail-closed flag", script.contains("ldoa-teardown-failed"))
        assertTrue("still-starting observation must be explicit",
            script.contains("STILL STARTING (readiness unknown, NOT a failure)"))
        assertTrue("observer cap ends work without killing",
            script.contains("observer capped") && script.contains("LDOA_WD_OBSERVER_S") && script.contains("session left running, readiness still unknown, nothing killed"))
        assertTrue("late readiness is logged as slow-not-failed",
            script.contains("late readiness - shell registered on the session bus"))
    }

    @Test fun observerVerifiesSessionDeathByComponentsNotWrapper() {
        assertTrue("wrapper death alone must not end observation (ptrace detach history)",
            script.contains("wrapper pid=${'$'}GSPID gone but OWNED session components alive"))
        assertTrue("conservative death-observation line must exist (name-match, ownership not asserted)",
            script.contains("no owned (snapshot) or name-matched session processes visible") && script.contains("observation ends, nothing killed"))
    }

    @Test fun mainFlowWaitsForWatchdogInsteadOfKillingIt() {
        assertFalse("killing the observer subshell could orphan its marker semantics (fallback registration must never be reported as full success)",
            script.contains("""kill ${'$'}POLL_A_PID ${'$'}WATCHDOG_PID"""))
        assertTrue("main flow must wait for the watchdog before the suppression check",
            script.contains("""wait ${'$'}WATCHDOG_PID"""))
    }

    @Test fun watchdogLoopExitsWhenSessionIsGone() {
        assertTrue("ping loop must exit when the wrapper is gone (bounds the main flow's wait)",
            script.contains("""kill -0 ${'$'}GSPID 2>/dev/null || exit 0"""))
    }


    /** The watchdog budget is a wall-clock DEADLINE, not a loop count:
     *  unbounded pings under bus congestion must not stretch the fire
     *  point by iterations x call latency (phone evidence 2026-10-31:
     *  watchdog never fired within the app gate's 210s). Each ping is
     *  doubly bounded: gdbus --timeout 2 (bus-level clean error) inside
     *  external `timeout 4` (kills a wedged gdbus process) - the two
     *  mechanisms are not interchangeable. SECONDS is dash-empty; the
     *  deadline arithmetic must use date +%s. */
    @Test fun watchdogBudgetIsDeadlineBasedWithDoublyBoundedPings() {
        assertTrue("deadline anchor must exist",
            script.contains("WD_START=") && script.contains("date +%s"))
        assertTrue("deadline comparison must exist",
            script.contains("-lt ${'$'}WD_BUDGET"))
        assertFalse("loop-count budget stretches by call latency - forbidden",
            script.contains("seq 1 55"))
        assertFalse("SECONDS is empty in dash - deadline must not use it",
            script.contains("SECONDS"))
        assertEquals("budget + post-budget + observer + fallback-poll pings + the shell-mode evidence probe must all be doubly bounded (gdbus --timeout 2 inside timeout 4)", 6,
            script.split("timeout 4 gdbus call --session --timeout 2").size - 1)
    }

    /** Sampler X11 calls have no internal timeout: a hung X server wedged
     *  the sampler after sample 1 on the 2026-10-31 phone run. Every X
     *  call in the sampler must be externally bounded, and the client-list
     *  parse must never read error text as window ids. */
    @Test fun samplerXCallsAreBoundedAndParseGuarded() {
        assertTrue("timeout tool must be probed at sampler start",
            script.contains("pgrep pidof xprop timeout; do"))
        assertTrue("client list call must be bounded",
            script.contains("CL=${'$'}(timeout 3 xprop -root _NET_CLIENT_LIST 2>/dev/null)"))
        assertTrue("per-window xwininfo must be bounded",
            script.contains("INFO=${'$'}(timeout 3 xwininfo -id"))
        assertTrue("parse must only run on a real list header (error text is not window ids)",
            script.contains("*\" # \"*)"))
        assertTrue("unavailable marker must exist",
            script.contains("win-list probe unavailable/empty:"))
        assertFalse("unbounded xprop in the sampler window loop is forbidden",
            script.contains("for id in ${'$'}(xprop -root _NET_CLIENT_LIST"))
    }

    @Test fun exitPathSurvivorCheckSeparatesOwnedIdentityFromNameMatch() {
        assertTrue("owned-tree snapshot helper must exist", script.contains("snapshot_owned_tree()"))
        assertTrue("snapshot file must live under the session xdg dir", script.contains("ldoa-owned-tree"))
        assertTrue("owned-identity verdict wording must exist", script.contains("owned-tree identity verified:"))
        assertTrue("name-match-only class must be labeled ownership UNKNOWN",
            script.contains("name-match only, ownership UNKNOWN:"))
        assertTrue("observer detach case must compare against the launch snapshot",
            script.contains("identity verified vs launch snapshot:"))
        assertTrue("member_alive helper must exist", script.contains("member_alive()"))
        assertTrue("member_alive must treat zombies as dead",
            script.contains("\"Z\" ] && return 1"))
    }

    @Test fun diagnosticProbesPresent() {
        assertTrue("installed-version probe must exist", script.contains("dpkg-query -W -f="))
        assertTrue("sampler system-bus liveness probe must exist", script.contains("sysbus_live="))
    }

    /** The launch id comes from the app via env (with a date fallback),
     *  and the parent identity is captured FROZEN at launch for the
     *  evidence log line - never re-read later (reparenting after tracer
     *  death makes any later read name the wrong process). Evidence only:
     *  nothing in the script consumes the identity after the parked
     *  stop-intent mechanism was removed (run 41306297954). */
    @Test fun envBoundLaunchIdAndFrozenRootIdentity() {
        assertTrue("launch id must prefer the app-passed env value",
            script.contains("LAUNCH_ID=\${LDOA_LAUNCH_ID:-"))
        assertTrue("no control nonce may remain in the script",
            !script.contains("STOP_KEY"))
        assertTrue("frozen identity must be captured from the EXPLICIT pid path /proc/$$ (a subprocess /proc/self resolves to the ephemeral subshell - run 41303335159)",
            script.contains("ROOT_PID=" + "\$(sed 's/^.*) //' /proc/\$\$/stat"))
        assertTrue("frozen identity must be logged for runtime validation",
            script.contains("frozen parent identity pid="))
    }

    /** Frozen parent identity must be captured via the EXPLICIT pid path
     *  /proc/$$ (reader-independent), never a subprocess's /proc/self -
     *  that resolved to an ephemeral command-substitution subshell which
     *  exited before its stat could be read, so ROOT_START logged EMPTY
     *  on every launch (run 41303335159). Capture failure must be LOUD
     *  (the UNAVAILABLE line). The negative check targets the CAPTURE
     *  assignment only: comments documenting the bug are not product code. */
    @Test fun frozenIdentityUsesExplicitPidPathAndFailsClosedLoudly() {
        assertTrue("explicit-pid capture must be used",
            script.contains("/proc/\$\$/stat"))
        assertFalse("subprocess /proc/self capture is the empty-start bug (ROOT_PID/ROOT_START assignments only)",
            script.lineSequence().any { it.contains("ROOT_PID=") && it.contains("/proc/self") })
        assertTrue("capture failure must log UNAVAILABLE",
            script.contains("frozen parent identity UNAVAILABLE"))
    }
}

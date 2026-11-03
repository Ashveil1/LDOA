#!/usr/bin/env python3
"""Checks for the mobile-desktop workflow's bounded caller waits, explicit
exit-status propagation, and host evidence preservation.

Provenance: run 41571335711 (2026-11-03). The post-test
`adb logcat -d > /tmp/shotlog.txt || true` was unbounded: the emulator
went offline during UTP teardown, adb blocked on "waiting for device"
for 2h21m, and the step died at the 150-min job backstop with
shotlog.txt empty and /tmp/instr.log + /tmp/build.log outside the
upload paths.

RUNNER SHELL SEMANTICS (source-verified against the pinned action's
ACTUAL executed artifact, not the default run:-step contract:
reactivecircus/android-emulator-runner v2 action.yml runs.main =
lib/main.js; lib/main.js:194,215 = `await exec.exec('sh', ['-c',
script], ...)`): the script is split into lines; EACH line runs as a
separate `sh -c <line>` via @actions/exec WITHOUT -e and NOT via GitHub's
`run:` bash -e default, and a nonzero line exit throws (@actions/exec
default), aborting the remaining lines and failing the step. So within
a line, a failing first command does NOT skip its `rc=$?` capture (no
errexit), and every line whose failure must not abort the step needs
its own guard.

PART 1 - STATIC (parse-only; does NOT execute the workflow, adb, gradle
or an emulator):
  S1. every adb command line is `timeout -k <K> <N>` wrapped (TERM-only
      timeout is defeatable; kill-after required);
  S2. both gradlew invocations are timeout -k wrapped; the
      instrumentation bound covers the test-internal overall deadline
      (1180s, XdiagPolicy/MDET source) with margin;
  S3. each gradlew pipeline runs under `bash -o pipefail -c "..."` with
      explicit `rc=$?` recorded into phase.txt AND rc enforcement
      (`[ $rc -eq 0 ] ||`), AND is followed by its grep "BUILD
      SUCCESSFUL" gate - a plain sh pipe's exit code is tee's, masking
      gradle's failure AND timeout's 124/137 kill statuses;
  S4. caller-boundary wraps on nested helpers: every python3 /
      `sh ci/gate_capture.sh` invocation is timeout -k wrapped;
  S5. coherent envelope: step timeout-minutes covers the worst serial
      sum of ALL parsed per-command bounds N PLUS their kill-after
      tails K PLUS the action's 600s emulator boot wait PLUS local
      slack, and stays below the job backstop (150);
  S6. the always() upload-artifact path list retains pull-status.txt,
      phase.txt, /tmp/shotlog.txt, /tmp/instr.log, /tmp/build.log;
  S7. phase markers at pre-test-adb/gate-boot/assemble/gate-post-build/
      instrumentation/post-test-evidence boundaries;
  S8. ci/gate_capture.sh adb calls are likewise timeout -k wrapped.

PART 2 - RUNTIME fixtures, each under its own bounded controller
(subprocess timeout + process-group SIGKILL: a fixture that breaks a
timeout cannot hang the test):
  R1. hazard exists: a TERM-ignoring child defeats a TERM-only timeout;
  R2. `timeout -k` bounds the same TERM-ignoring child (KILL escalation);
  R3. failing command + `| tee log` + grep-gate marks FAILED, log kept;
  R4. timeout path: grep-gate marks FAILED even though the plain pipe's
      exit code is tee's 0;
  R5. DOCUMENTED RESIDUAL: a setsid escape holds the tee pipe beyond the
      per-command bound - per-command timeout cannot bound it; the
      step-level timeout is the mitigation, and even then orphan death +
      in-flight log flush are not guaranteed (asserted as known behavior);
  R6. pipefail shape: explicit 124/137 kill rc on timeout (not
      masked), log kept;
  R7. pipefail shape: failing command gives its own rc.

PART 3 - RENDERED-SCRIPT DRY RUN: the actual script lines are rendered
for BOTH xdiag_capture states and executed line-by-line via `sh -c`
exactly as the action runs them, against stub adb/gradlew/helper files:
  D1 (xdiag=true, instrumentation fails rc=1): rendered instrumentation
      line carries #mobileEntryXdiagCapture AND ldoa_xdiag=true;
      instrumentation-done rc=1 lands in phase.txt; PHONELINUX_TESTS_FAILED
      created; final guard line exits 1 (step fails); /tmp/instr.log,
      /tmp/build.log, phase.txt, pull-status.txt, /tmp/shotlog.txt all
      exist for the always() upload;
  D2 (xdiag=false, all pass): no xdiag flags in the rendered line;
      no PHONELINUX_TESTS_FAILED; final guard exits 0;
  D3 (instrumentation traps TERM and hangs): bounded by timeout -k, and
      instrumentation-done rc=124/137 is recorded EXPLICITLY in
      phase.txt.

--negative-selftest: re-runs Part 1 against mutated workflows and
REQUIRES each mutation to fail.

Usage: test_mobile_desktop_harness.py <repo-root> [--negative-selftest]
"""
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

WORKFLOW = ".github/workflows/mobile-desktop.yml"
GATE_CAPTURE = "ci/gate_capture.sh"
TEST_INTERNAL_DEADLINE_S = 1180  # xdiag test overall deadline (source)
EMULATOR_BOOT_S = 600            # action emulator-boot-timeout default (v2 source)
LOCAL_MISC_S = 60                # find/grep/local slack outside wraps


def parse_step(lines):
    for i, ln in enumerate(lines):
        if "uses: reactivecircus/android-emulator-runner@" in ln:
            timeout_min = None
            j = i + 1
            indent = len(ln) - len(ln.lstrip())
            while j < len(lines):
                l2 = lines[j]
                if l2.strip() and (len(l2) - len(l2.lstrip()) < indent
                                   or re.match(r"^\s*-\s", l2)):
                    break
                m = re.match(r"^\s*timeout-minutes:\s*(\d+)", l2)
                if m:
                    timeout_min = int(m.group(1))
                if re.match(r"^\s*script:\s*\|", l2):
                    sind = len(l2) - len(l2.lstrip())
                    script = []
                    k = j + 1
                    while k < len(lines):
                        l3 = lines[k]
                        if l3.strip() and len(l3) - len(l3.lstrip()) <= sind:
                            break
                        script.append(l3)
                        k += 1
                    return script, timeout_min
                j += 1
            raise SystemExit("emulator step has no script: | block")
    raise SystemExit("no android-emulator-runner step found")


def parse_upload_paths(lines):
    for i, ln in enumerate(lines):
        if "uses: actions/upload-artifact@" in ln:
            j = i + 1
            while j < len(lines) and not re.match(r"^\s*path:\s*\|", lines[j]):
                if j > i + 40:
                    break
                j += 1
            if j >= len(lines):
                raise SystemExit("upload-artifact step has no path: | block")
            indent = len(lines[j]) - len(lines[j].lstrip())
            paths, k = [], j + 1
            while k < len(lines):
                l2 = lines[k]
                if l2.strip() == "":
                    k += 1
                    continue
                if len(l2) - len(l2.lstrip()) <= indent:
                    break
                paths.append(l2.strip())
                k += 1
            return paths
    raise SystemExit("no upload-artifact step found")


def first_job_timeout(lines):
    for ln in lines:
        m = re.match(r"^\s*timeout-minutes:\s*(\d+)", ln)
        if m:
            return int(m.group(1))
    return None


def code_lines(script):
    return [ln.strip() for ln in script
            if ln.strip() and not ln.strip().startswith("#")]


def check_workflow(script, step_timeout, job_timeout, upload_paths, label):
    failures = []
    cl = code_lines(script)

    for ln in cl:
        if re.search(r"(?<![\w./-])adb\s", ln):
            if not re.search(r"timeout\s+-k\s+\d+\s+\d+\s+adb\s", ln):
                failures.append("[%s] ADB-NOT-KILL-BOUNDED: %s" % (label, ln[:120]))

    gradle_idx = [i for i, ln in enumerate(cl) if "./gradlew" in ln]
    bounds = []
    for i in gradle_idx:
        ln = cl[i]
        if not ln.startswith('bash -o pipefail -c "'):
            failures.append("[%s] GRADLEW-NOT-PIPEFAIL-WRAPPED: %s" % (label, ln[:120]))
        m = re.search(r"timeout\s+-k\s+(\d+)\s+(\d+)\s+\./gradlew", ln)
        if not m:
            failures.append("[%s] GRADLEW-NOT-KILL-BOUNDED: %s" % (label, ln[:120]))
        else:
            bounds.append((int(m.group(2)), ln))
        if "rc=$?" not in ln or ">> phase.txt" not in ln:
            failures.append("[%s] RC-NOT-RECORDED: %s" % (label, ln[:120]))
        if "[ $rc -eq 0 ] ||" not in ln:
            failures.append("[%s] RC-NOT-ENFORCED: %s" % (label, ln[:120]))
        following = cl[i + 1:i + 3]
        if not any('grep -q "BUILD SUCCESSFUL"' in ln2 for ln2 in following):
            failures.append("[%s] GREP-GATE-MISSING after: %s" % (label, ln[:100]))
    for bound, body in bounds:
        if "connectedDebugAndroidTest" in body and bound < TEST_INTERNAL_DEADLINE_S:
            failures.append("[%s] INSTRUMENTATION-BOUND-TOO-SMALL: %ds < %ds"
                            % (label, bound, TEST_INTERNAL_DEADLINE_S))
    if not any("connectedDebugAndroidTest" in b for _, b in bounds):
        failures.append("[%s] INSTRUMENTATION-LINE-NOT-FOUND" % label)

    for ln in cl:
        if re.search(r"(?<![\w./-])python3\s", ln) and not re.search(
                r"timeout\s+-k\s+\d+\s+\d+\s+python3\s", ln):
            failures.append("[%s] PYTHON3-NOT-KILL-BOUNDED: %s" % (label, ln[:120]))
        if "sh ci/gate_capture.sh" in ln and not re.search(
                r"timeout\s+-k\s+\d+\s+\d+\s+sh\s+ci/gate_capture\.sh", ln):
            failures.append("[%s] GATE-CAPTURE-CALL-NOT-BOUNDED: %s" % (label, ln[:120]))

    kills = sum(int(m.group(1)) for ln in cl
                for m in re.finditer(r"timeout\s+-k\s+(\d+)\s+(\d+)", ln))
    per_cmd = sum(int(m.group(2)) for ln in cl
                  for m in re.finditer(r"timeout\s+-k\s+(\d+)\s+(\d+)", ln))
    serial = per_cmd + kills + EMULATOR_BOOT_S + LOCAL_MISC_S
    if step_timeout is None:
        failures.append("[%s] STEP-TIMEOUT-MISSING" % label)
    else:
        if step_timeout * 60 < serial:
            failures.append("[%s] STEP-TIMEOUT-BELOW-SERIAL-BOUNDS: %ds < %ds"
                            % (label, step_timeout * 60, serial))
        if job_timeout is not None and step_timeout >= job_timeout:
            failures.append("[%s] STEP-TIMEOUT-NOT-BELOW-JOB: %d >= %d"
                            % (label, step_timeout, job_timeout))

    for required in ("pull-status.txt", "phase.txt", "/tmp/shotlog.txt",
                     "/tmp/instr.log", "/tmp/build.log"):
        if required not in upload_paths:
            failures.append("[%s] HOST-EVIDENCE-NOT-RETAINED: %s" % (label, required))

    phases = [ln for ln in script if re.search(r"phase:\s*\S+", ln)]
    for stage in ("pre-test-adb", "gate-boot", "assemble", "gate-post-build",
                  "instrumentation", "post-test-evidence"):
        if not any(("phase: " + stage) in ln for ln in phases):
            failures.append("[%s] PHASE-MARKER-MISSING: %s" % (label, stage))
    return failures


def check_gate_capture(root, label):
    failures = []
    for ln in (Path(root) / GATE_CAPTURE).read_text().splitlines():
        body = ln.strip()
        if not body or body.startswith("#"):
            continue
        if re.search(r"(?<![\w./-])adb\s", body) and not re.search(
                r"timeout\s+-k\s+\d+\s+\d+\s+adb\s", body):
            failures.append("[%s] GATE-CAPTURE-ADB-NOT-KILL-BOUNDED: %s"
                            % (label, body[:120]))
    return failures


# ---------------- bounded fixture controller ----------------

def cleanup_anomalies(observations):
    """Cleanup outcomes that must FAIL a scenario, as opposed to survival
    itself, which is reported as an observation."""
    return [o for o in observations
            if "IDENTITY MISMATCH" in o or "reap failed" in o
            or "STILL ALIVE" in o]


def kill_owned(pids_file):
    """Signal ONLY PIDs this fixture recorded, each verified against its
    recorded /proc starttime AND cmdline fragment BEFORE SIGKILL. Never
    pattern-kills (no pkill -f) and never signals a process group:
    setsid escapes are an asserted residual (R5), not claimed coverage.
    After signalling, the outcome is confirmed within a bounded wait:
    reaped via waitpid when the PID is our own child, otherwise polled
    until /proc shows it gone or zombie. Labels stay honest:
    "signal sent, confirmed gone/zombie" only after the check; survival
    to cleanup is reported as an observation, not a verdict."""
    observations = []
    if not pids_file.exists():
        return observations
    for entry in pids_file.read_text().splitlines():
        try:
            pid_s, start, frag = entry.split("\t")
            pid = int(pid_s)
        except ValueError:
            continue
        try:
            tail = Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1]
            fields = tail.split()
            state = fields[0]               # /proc field 3 = state
            actual_start = fields[19]       # /proc field 22 = starttime
        except (FileNotFoundError, ProcessLookupError, IndexError):
            observations.append("pid %d (%s): already dead" % (pid, frag))
            continue
        if state == "Z":
            observations.append("pid %d (%s): already zombie (dead, "
                                "awaiting its parent's reap)" % (pid, frag))
            continue
        if actual_start != start:
            observations.append("pid %d (%s): IDENTITY MISMATCH "
                                "(start %s vs %s) - NOT signalled"
                                % (pid, frag, actual_start, start))
            continue
        try:
            raw = Path("/proc/%d/cmdline" % pid).read_bytes()
            cmdline = raw.replace(b"\0", b" ").decode(errors="replace")
        except (FileNotFoundError, ProcessLookupError):
            observations.append("pid %d (%s): already dead" % (pid, frag))
            continue
        if frag not in cmdline:
            observations.append("pid %d (%s): IDENTITY MISMATCH "
                                "(cmdline %r lacks %r) - NOT signalled"
                                % (pid, frag, cmdline[:60], frag))
            continue
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            observations.append("pid %d (%s): already dead" % (pid, frag))
            continue
        except PermissionError as e:
            observations.append("pid %d (%s): reap failed: %s"
                                % (pid, frag, e))
            continue
        # honest confirm: reap if it is our child, else bounded-poll /proc
        try:
            done, _ = os.waitpid(pid, os.WNOHANG)
            if done == pid:
                observations.append("pid %d (%s): survived to cleanup, "
                                    "signal sent, reaped (our child)"
                                    % (pid, frag))
                continue
        except ChildProcessError:
            pass
        confirmed = None
        for _ in range(50):  # bounded: 50 x 20ms = 1s
            try:
                tail = Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1]
                if tail.split()[0] == "Z":
                    confirmed = "confirmed zombie"
                    break
            except (FileNotFoundError, ProcessLookupError, IndexError):
                confirmed = "confirmed gone"
                break
            time.sleep(0.02)
        if confirmed:
            observations.append("pid %d (%s): survived to cleanup, "
                                "signal sent, %s" % (pid, frag, confirmed))
        else:
            observations.append("pid %d (%s): signal sent, STILL ALIVE "
                                "after 1s bounded wait" % (pid, frag))
    return observations


def run_bounded(cmd, cwd, limit_s, env=None):
    """Run `sh -c cmd` with an outer watchdog: on expiry the command's OWN
    process group gets SIGKILL (the Popen is its group leader), so a
    fixture that defeats the timeout under test cannot hang this test.
    This does NOT claim coverage of setsid escapes: R5 asserts that
    residual instead of claiming the kill reaches it.
    Returns (elapsed_s, exit_code_or_None)."""
    p = subprocess.Popen(["sh", "-c", cmd], cwd=cwd, env=env,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
    t0 = time.monotonic()
    try:
        rc = p.wait(timeout=limit_s)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        p.wait()
        raise AssertionError("fixture exceeded its own %ss watchdog: %s"
                             % (limit_s, cmd[:100]))
    return time.monotonic() - t0, rc


# Fixture shell scripts written into each fixture tempdir: keeping the
# PID-recording logic in real files avoids four levels of nested quoting
# through python -> sh -c -> timeout -> sh -c.
TERM_IGNORING_SH = """#!/bin/sh
# defeats a TERM-only timeout: the shell ignores TERM, and the sleep
# INHERITS SIG_IGN across fork; records its exact PID + /proc starttime
# + cmdline fragment for owned cleanup
trap '' TERM
sleep "$1" & sp=$!
printf '%s\t%s\t%s\n' "$sp" "$(cut -d' ' -f22 /proc/$sp/stat)" "sleep" >> "$FIXTURE_PIDS"
wait
"""

SLEEP_REC_SH = """#!/bin/sh
# plain foreground-equivalent sleep with owned-PID recording
sleep "$1" & sp=$!
printf '%s\t%s\t%s\n' "$sp" "$(cut -d' ' -f22 /proc/$sp/stat)" "sleep" >> "$FIXTURE_PIDS"
wait
"""

SETSID_REC_SH = """#!/bin/sh
# runs UNDER setsid (its own session): records its OWN pid + starttime,
# then execs sleep so the recorded pid's cmdline becomes "sleep" (starttime
# and pid survive the exec). Registration lets the finally cleanup reap it
# when the watchdog aborts before its natural exit; the group-kill does
# NOT reach it - that escape is the asserted R5 residual.
printf '%s\t%s\t%s\n' "$$" "$(cut -d' ' -f22 /proc/$$/stat)" "sleep" >> "$FIXTURE_PIDS"
exec sleep "$1"
"""


def runtime_fixtures():
    failures = []
    observations = []
    with tempfile.TemporaryDirectory() as d:
        for name, body in (("term_ignoring.sh", TERM_IGNORING_SH),
                           ("sleep_rec.sh", SLEEP_REC_SH),
                           ("setsid_rec.sh", SETSID_REC_SH)):
            (Path(d) / name).write_text(body)
            (Path(d) / name).chmod(0o755)
        env = dict(os.environ, FIXTURE_PIDS=str(Path(d) / "pids.txt"))
        try:
            e, _ = run_bounded("timeout 1 sh term_ignoring.sh 3", d, 15, env=env)
            if e < 2.5:
                failures.append("R1: TERM-only timeout unexpectedly bounded "
                                "a TERM-ignoring child in %.1fs" % e)

            e, _ = run_bounded("timeout -k 1 1 sh term_ignoring.sh 30",
                               d, 15, env=env)
            if e > 6:
                failures.append("R2: timeout -k failed to bound a "
                                "TERM-ignoring child (%.1fs)" % e)

            run_bounded("timeout -k 1 2 sh -c 'echo partial-output; exit 1' "
                        "| tee %s/instr.log || touch %s/FAILED; "
                        "grep -q \"BUILD SUCCESSFUL\" %s/instr.log || touch %s/FAILED"
                        % (d, d, d, d), d, 15, env=env)
            if not (Path(d) / "FAILED").exists():
                failures.append("R3: grep-gate did not mark failure for a "
                                "failing command")
            if "partial-output" not in (Path(d) / "instr.log").read_text():
                failures.append("R3: tee'd log not preserved")

            os.remove(Path(d) / "FAILED")
            run_bounded("timeout -k 1 1 sh sleep_rec.sh 30 "
                        "| tee %s/instr2.log || touch %s/FAILED2; "
                        "grep -q \"BUILD SUCCESSFUL\" %s/instr2.log || touch %s/FAILED2"
                        % (d, d, d, d), d, 15, env=env)
            if not (Path(d) / "FAILED2").exists():
                failures.append("R4: grep-gate did not mark failure after "
                                "timeout (pipeline exit masked by tee)")

            e, _ = run_bounded("timeout -k 1 1 sh -c 'setsid sh setsid_rec.sh 3; exit 0' "
                               "| tee %s/instr3.log" % d, d, 15, env=env)
            if e < 2.5:
                failures.append("R5: expected the setsid escape to hold the "
                                "tee pipe ~3s (documented residual changed?)")

            rcfile = Path(d) / "rc6"
            run_bounded('bash -o pipefail -c "timeout -k 1 1 sh sleep_rec.sh 30 '
                        '| tee %s/instr6.log"; echo $? > %s' % (d, rcfile),
                        d, 15, env=env)
            rc = rcfile.read_text().strip()
            if rc not in KILL_RCS:
                failures.append("R6: pipefail timeout rc=%s, expected 124/137 "
                                "(explicit status masked?)" % rc)

            rcfile7 = Path(d) / "rc7"
            run_bounded('bash -o pipefail -c "timeout -k 1 2 sh -c \'exit 3\' '
                        '| tee %s/instr7.log"; echo $? > %s' % (d, rcfile7),
                        d, 15, env=env)
            rc7 = rcfile7.read_text().strip()
            if rc7 != "3":
                failures.append("R7: pipefail failing-command rc=%s, "
                                "expected 3" % rc7)
        finally:
            # success, failure, AND watchdog paths all land here; only
            # fixture-recorded, identity-verified PIDs are signalled.
            observations = kill_owned(Path(env["FIXTURE_PIDS"]))
    for o in observations:
        print("  fixture-cleanup:", o)
    for a in cleanup_anomalies(observations):
        failures.append("runtime-fixture cleanup anomaly: " + a)
    return failures


# ---------------- Part 3: rendered-script dry run ----------------

def render_script(script, xdiag):
    """Render the ${{ inputs.xdiag_capture && 'X' || '' }} expressions for
    one input state; the only expressions used in this script."""
    out = []
    for ln in script:
        def sub(m):
            return m.group(1) if xdiag else ""
        out.append(re.sub(r"\$\{\{\s*inputs\.xdiag_capture\s*&&\s*'([^']*)'\s*\|\|\s*''\s*\}\}",
                          sub, ln))
    return out


STUB_GRADLEW = """#!/bin/sh
# record EXACT argv (NUL-separated, one call per line) for assertions
printf '%s\\0' "$@" >> "$FIXTURE_DIR/gradlew_calls"
printf '\\n' >> "$FIXTURE_DIR/gradlew_calls"
case "$STUB_MODE:$*" in
  *connectedDebugAndroidTest*)
    case "$STUB_MODE" in
      hang)
        # TERM-resistant: the shell ignores TERM and the sleep INHERITS
        # SIG_IGN across fork; both PIDs recorded for owned cleanup.
        # Whether timeout's -k KILL reaches the sleep is OBSERVED by the
        # survivor check, not assumed.
        trap '' TERM
        sleep 300 & sp=$!
        printf '%s\\t%s\\t%s\\n' "$sp" "$(cut -d' ' -f22 /proc/$sp/stat)" "sleep" >> "$FIXTURE_PIDS"
        printf '%s\\t%s\\t%s\\n' "$$" "$(cut -d' ' -f22 /proc/$$/stat)" "gradlew" >> "$FIXTURE_PIDS"
        wait
        ;;
      fail) echo "some test output"; echo "BUILD FAILED in 1s" ; exit 1 ;;
      *)    echo "BUILD SUCCESSFUL" ;;
    esac ;;
  *)
    case "$STUB_MODE" in
      assemble-lie) echo "BUILD SUCCESSFUL"; exit 1 ;;
      *) echo "BUILD SUCCESSFUL" ;;
    esac ;;
esac
exit 0
"""

STUB_ADB = """#!/bin/sh
# fast fake adb: dump an empty logcat, succeed otherwise
case "$*" in
  *"logcat -d"*) exit 0 ;;
esac
echo stub-adb
exit 0
"""


# GNU timeout exits 124 when the command times out and dies on TERM,
# but 137 (128+SIGKILL) when the -k KILL escalation had to fire on a
# TERM-resistant command; both prove the bound engaged.
KILL_RCS = ("124", "137")

TMP_TARGETS = ("/tmp/instr.log", "/tmp/build.log", "/tmp/shotlog.txt",
               "/tmp/PHONELINUX_TESTS_FAILED")


def remap_tmp(rendered, d):
    """Rewrite every fixed /tmp target into this fixture's tempdir so the
    dry run never creates, overwrites, or deletes shared /tmp evidence,
    then assert no unremapped absolute /tmp target survives in any
    executable line. Pure token replacement on path strings: argument
    boundaries and quoting semantics are unchanged."""
    out = []
    for ln in rendered:
        for t in TMP_TARGETS:
            ln = ln.replace(t, str(Path(d) / Path(t).name))
        out.append(ln)
    for ln in out:
        body = ln.strip()
        if not body or body.startswith("#"):
            continue
        leftovers = [t for t in re.findall(r"(?<![\w.-])/tmp/[\w.-]+", ln)
                     if t != str(d) and not t.startswith(str(d) + "/")]
        assert not leftovers, ("unremapped /tmp target(s) %r in rendered "
                               "line: %r" % (leftovers, ln[:160]))
    return out


def gradlew_calls(d):
    """Parse the stub's NUL-separated argv record: one line per call."""
    f = Path(d) / "gradlew_calls"
    if not f.exists():
        return []
    calls = []
    for line in f.read_bytes().split(b"\n"):
        if line:
            calls.append([a.decode(errors="replace")
                          for a in line.split(b"\0") if a])
    return calls


def dry_run(script_lines, xdiag, stub_mode, d):
    """Execute the rendered lines EXACTLY as the action does: separate
    `sh -c <line>` per line, nonzero exit aborts the rest. Returns
    (failed_line_index_or_None, rendered_lines, cleanup_observations).
    stub_mode=hang deliberately rescales the instrumentation bound
    30/1500 -> 1/2: D3 tests the kill-after + explicit-rc SHAPE at short
    duration (the production values are S2/S5's static concern; a real
    1500s hang is untestable here)."""
    rendered = render_script(script_lines, xdiag)
    if stub_mode == "hang":
        rendered = [ln.replace("timeout -k 30 1500 ./gradlew",
                               "timeout -k 1 2 ./gradlew") for ln in rendered]
    rendered = remap_tmp(rendered, d)
    bindir = Path(d) / "bin"
    bindir.mkdir(exist_ok=True)
    (bindir / "adb").write_text(STUB_ADB)
    (bindir / "adb").chmod(0o755)
    (Path(d) / "gradlew").write_text(STUB_GRADLEW)
    (Path(d) / "gradlew").chmod(0o755)
    (Path(d) / "ci").mkdir(exist_ok=True)
    (Path(d) / "ci" / "launcher_gate.py").write_text("print('READY')\n")
    (Path(d) / "ci" / "launcher-suppression.conf").write_text("on\n")
    (Path(d) / "ci" / "gate_capture.sh").write_text("#!/bin/sh\nexit 0\n")
    (Path(d) / ".github" / "scripts").mkdir(parents=True, exist_ok=True)
    (Path(d) / ".github" / "scripts" / "extract_shots.py").write_text("")
    env = dict(os.environ)
    env["PATH"] = str(bindir) + ":" + env["PATH"]
    env["STUB_MODE"] = stub_mode
    env["FIXTURE_DIR"] = str(d)
    env["FIXTURE_PIDS"] = str(Path(d) / "pids.txt")
    failed_at = None
    try:
        for idx, ln in enumerate(rendered):
            body = ln.strip()
            if not body or body.startswith("#"):
                continue
            try:
                _, rc = run_bounded(ln, d, 30, env=env)
            except AssertionError:
                failed_at = idx  # watchdog: the line itself hung -> abort
                break
            if rc != 0:
                failed_at = idx
                break
    finally:
        # success, failure, AND watchdog paths all land here; only
        # fixture-recorded, identity-verified PIDs are signalled.
        observations = kill_owned(Path(env["FIXTURE_PIDS"]))
    return failed_at, rendered, observations


def dry_run_checks(script):
    failures = []
    INSTR_CLASS = ("-Pandroid.testInstrumentationRunnerArguments.class="
                   "dev.phonelinux.proto.MobileDesktopEntryTest")
    CI_FLAG = "-Pandroid.testInstrumentationRunnerArguments.ldoa_ci_emulator=true"
    XDIAG_FLAG = "-Pandroid.testInstrumentationRunnerArguments.ldoa_xdiag=true"

    with tempfile.TemporaryDirectory() as d:
        # D1: xdiag=true, instrumentation fails rc=1
        failed_at, rendered, obs = dry_run(script, True, "fail", d)
        for o in obs:
            print("  D1 cleanup:", o)
        for a in cleanup_anomalies(obs):
            failures.append("D1 cleanup anomaly: " + a)
        instr = next(ln for ln in rendered if "connectedDebugAndroidTest" in ln)
        if "#mobileEntryXdiagCapture" not in instr or "ldoa_xdiag=true" not in instr:
            failures.append("D1: rendered xdiag=true line missing capture flags")
        calls = gradlew_calls(d)
        instr_calls = [c for c in calls if "connectedDebugAndroidTest" in c]
        if len(instr_calls) != 1:
            failures.append("D1: expected exactly 1 instrumentation gradlew "
                            "call, got %d (%r)" % (len(instr_calls), calls))
        else:
            argv = instr_calls[0]
            for exact in (INSTR_CLASS + "#mobileEntryXdiagCapture",
                          CI_FLAG, XDIAG_FLAG):
                if exact not in argv:
                    failures.append("D1: gradlew argv missing EXACT element "
                                    "%r (got %r)" % (exact, argv))
        if ["assembleDebug", "assembleDebugAndroidTest"] not in calls:
            failures.append("D1: assemble argv != [assembleDebug, "
                            "assembleDebugAndroidTest] (got %r)" % calls)
        phase = (Path(d) / "phase.txt").read_text()
        if "instrumentation-done rc=1" not in phase:
            failures.append("D1: phase.txt missing 'instrumentation-done rc=1', got: "
                            + phase.replace("\n", " | "))
        for stage in ("pre-test-adb", "gate-boot", "assemble-done rc=0",
                      "gate-post-build", "post-test-evidence"):
            if stage not in phase:
                failures.append("D1: phase.txt missing stage " + stage)
        if not (Path(d) / "PHONELINUX_TESTS_FAILED").exists():
            failures.append("D1: PHONELINUX_TESTS_FAILED not created")
        if failed_at is None or "exit 1" not in rendered[failed_at]:
            failures.append("D1: step did not fail at the final guard line")
        for f in ("instr.log", "build.log", "shotlog.txt",
                  "pull-status.txt", "phase.txt"):
            if not (Path(d) / f).exists():
                failures.append("D1: evidence file missing: " + f)

    with tempfile.TemporaryDirectory() as d:
        # D2: xdiag=false, all pass
        failed_at, rendered, obs = dry_run(script, False, "pass", d)
        for o in obs:
            print("  D2 cleanup:", o)
        for a in cleanup_anomalies(obs):
            failures.append("D2 cleanup anomaly: " + a)
        instr = next(ln for ln in rendered if "connectedDebugAndroidTest" in ln)
        if "mobileEntryXdiagCapture" in instr or "ldoa_xdiag" in instr:
            failures.append("D2: rendered xdiag=false line carries xdiag flags")
        calls = gradlew_calls(d)
        instr_calls = [c for c in calls if "connectedDebugAndroidTest" in c]
        if len(instr_calls) != 1:
            failures.append("D2: expected exactly 1 instrumentation gradlew "
                            "call, got %d" % len(instr_calls))
        else:
            argv = instr_calls[0]
            for exact in (INSTR_CLASS, CI_FLAG):
                if exact not in argv:
                    failures.append("D2: gradlew argv missing EXACT element "
                                    "%r (got %r)" % (exact, argv))
            if any("mobileEntryXdiagCapture" in a or "ldoa_xdiag" in a
                   for a in argv):
                failures.append("D2: xdiag=false argv carries selector/xdiag "
                                "flag: %r" % argv)
        if failed_at is not None:
            failures.append("D2: all-pass run failed at line %d: %s"
                            % (failed_at, rendered[failed_at][:100]))
        if (Path(d) / "PHONELINUX_TESTS_FAILED").exists():
            failures.append("D2: PHONELINUX_TESTS_FAILED created on a pass")

    with tempfile.TemporaryDirectory() as d:
        # D3: instrumentation traps TERM and hangs -> bounded, kill rc explicit.
        # Whether the -k KILL leaves survivors is OBSERVED (printed), not
        # assumed; any survivor is reaped by the owned-PID cleanup.
        t0 = time.monotonic()
        failed_at, rendered, obs = dry_run(script, True, "hang", d)
        elapsed = time.monotonic() - t0
        survivors = [o for o in obs if "survived to cleanup" in o]
        print("  D3 hang-fixture observation: %s"
              % ("; ".join(obs) if obs else "no PIDs recorded"))
        if elapsed > 60:
            failures.append("D3: TERM-trapping hang was not bounded "
                            "(%.1fs)" % elapsed)
        phase = (Path(d) / "phase.txt").read_text()
        if not any(("instrumentation-done rc=%s" % k) in phase for k in KILL_RCS):
            failures.append("D3: phase.txt missing explicit kill rc "
                            "(124/137), got: " + phase.replace("\n", " | "))
        if not (Path(d) / "PHONELINUX_TESTS_FAILED").exists():
            failures.append("D3: PHONELINUX_TESTS_FAILED not created on hang")
        for a in cleanup_anomalies(obs):
            failures.append("D3 cleanup anomaly: " + a)
        if survivors:
            print("  D3 SURVIVOR(S) at cleanup (timeout did NOT reach them; "
                  "signalled + confirmed):", survivors)
        else:
            print("  D3 observed: timeout -k left NO survivor in this fixture")

    with tempfile.TemporaryDirectory() as d:
        # D4: assemble prints BUILD SUCCESSFUL but exits nonzero. The grep
        # gate ALONE would pass this; the explicit rc enforcement must fail
        # the step AT the assemble line, before instrumentation ever runs.
        failed_at, rendered, obs = dry_run(script, False, "assemble-lie", d)
        for o in obs:
            print("  D4 cleanup:", o)
        for a in cleanup_anomalies(obs):
            failures.append("D4 cleanup anomaly: " + a)
        asm_idx = next(i for i, ln in enumerate(rendered)
                       if "assembleDebug assembleDebugAndroidTest" in ln)
        if failed_at != asm_idx:
            failures.append("D4: step did not fail AT the assemble line "
                            "(failed_at=%r, assemble line=%d)"
                            % (failed_at, asm_idx))
        calls = gradlew_calls(d)
        if any("connectedDebugAndroidTest" in c for c in calls):
            failures.append("D4: instrumentation RAN despite assemble rc=1")
        phase = (Path(d) / "phase.txt").read_text()
        if "assemble-done rc=1" not in phase:
            failures.append("D4: phase.txt missing 'assemble-done rc=1', got: "
                            + phase.replace("\n", " | "))
        if (Path(d) / "PHONELINUX_TESTS_FAILED").exists():
            failures.append("D4: FAILED marker created despite "
                            "pre-instrumentation abort")
    return failures


def load(root):
    lines = (Path(root) / WORKFLOW).read_text().splitlines()
    script, step_timeout = parse_step(lines)
    return (lines, script, step_timeout, first_job_timeout(lines),
            parse_upload_paths(lines))


def main():
    root = sys.argv[1]
    lines, script, step_timeout, job_timeout, upload_paths = load(root)
    failures = check_workflow(script, step_timeout, job_timeout,
                              upload_paths, "actual")
    failures += check_gate_capture(root, "actual")
    if failures:
        print("--- STATIC FAILURES (%d) ---" % len(failures))
        for f in failures:
            print("  " + f)
        return 1
    print("STATIC: PASS (kill-bounded adb/gradlew/helpers, pipefail + rc "
          "recorded/enforced + grep gates, coherent step envelope %ds >= "
          "serial bounds incl. kill tails + 600s boot, evidence retained, "
          "phases)" % (step_timeout * 60))

    rf = runtime_fixtures()
    if rf:
        print("--- RUNTIME FIXTURE FAILURES (%d) ---" % len(rf))
        for f in rf:
            print("  " + f)
        return 1
    print("RUNTIME: PASS (R1 TERM-defeat hazard real, R2 kill-after bounds, "
          "R3/R4 grep-gate exit propagation + log preservation, "
          "R5 setsid pipe-hold residual documented, R6 kill rc explicit, "
          "R7 failing rc propagated)")

    df = dry_run_checks(script)
    if df:
        print("--- DRY-RUN FAILURES (%d) ---" % len(df))
        for f in df:
            print("  " + f)
        return 1
    print("DRY-RUN: PASS (D1 xdiag=true fail-path rc=1 recorded + exact "
          "argv asserted + step fails + evidence complete, D2 xdiag=false "
          "pass clean + no xdiag argv, D3 TERM-trapping hang bounded with "
          "explicit kill rc + survivor observation, D4 assemble lies "
          "SUCCESSFUL with rc=1 caught by rc enforcement)")

    if "--negative-selftest" in sys.argv:
        original = (Path(root) / WORKFLOW).read_text()
        mutations = [
            ("unbounded post-test logcat (run 41571335711 shape)",
             lambda t: re.sub(r"timeout\s+-k\s+\d+\s+\d+\s+adb -s emulator-5554 logcat -d",
                              "adb -s emulator-5554 logcat -d", t)),
            ("TERM-only timeout on instrumentation (no kill-after)",
             lambda t: re.sub(r"timeout\s+-k\s+(\d+)\s+(\d+)\s+(\./gradlew connectedDebugAndroidTest)",
                              r"timeout \2 \3", t)),
            ("unbounded instrumentation gradlew",
             lambda t: re.sub(r"timeout\s+-k\s+\d+\s+\d+\s+(\./gradlew connectedDebugAndroidTest)",
                              r"\1", t)),
            ("pipefail wrapper removed from instrumentation",
             lambda t: t.replace('bash -o pipefail -c "timeout -k 30 1500 ./gradlew connectedDebugAndroidTest',
                                 'timeout -k 30 1500 ./gradlew connectedDebugAndroidTest', 1)),
            ("rc enforcement removed from assemble",
             lambda t: t.replace('; [ $rc -eq 0 ] || { echo "assemble pipeline rc=$rc nonzero"; exit 1; }',
                                 '', 1)),
            ("grep gate removed after instrumentation",
             lambda t: t.replace('grep -q "BUILD SUCCESSFUL" /tmp/instr.log || touch /tmp/PHONELINUX_TESTS_FAILED\n', "", 1)),
            ("gate caller boundary unwrapped",
             lambda t: t.replace("timeout -k 10 180 python3 ci/launcher_gate.py boot",
                                 "python3 ci/launcher_gate.py boot", 1)),
            ("no step timeout",
             lambda t: re.sub(r"\n\s*timeout-minutes: 85\n(\s+with:)", r"\n\1", t, count=1)),
            ("step timeout below serial bounds",
             lambda t: t.replace("timeout-minutes: 85", "timeout-minutes: 30", 1)),
            ("host logs dropped from upload list",
             lambda t: t.replace("            /tmp/instr.log\n", "")
                        .replace("            /tmp/build.log\n", "")),
            ("phase marker removed (post-test-evidence)",
             lambda t: t.replace('            echo "phase: post-test-evidence $(date -u +%FT%TZ)" >> phase.txt\n', "", 1)),
        ]
        # S8 mutation: gate_capture.sh internal kill-after removal
        # (workflow untouched; the check must catch the helper file).
        with tempfile.TemporaryDirectory() as td:
            os.makedirs(os.path.join(td, ".github/workflows"))
            os.makedirs(os.path.join(td, "ci"))
            (Path(td) / WORKFLOW).write_text(original)
            gc = (Path(root) / GATE_CAPTURE).read_text()
            mutated_gc = re.sub(r"timeout\s+-k\s+\d+\s+(\d+\s+adb)", r"timeout \1", gc)
            if mutated_gc == gc:
                print("NEGATIVE-SELFTEST: ERROR (gate_capture mutation did not apply)")
                return 1
            (Path(td) / GATE_CAPTURE).write_text(mutated_gc)
            mf = check_gate_capture(td, "mutated")
        if not mf:
            print("NEGATIVE-SELFTEST: FAIL (gate_capture -k removal was NOT caught)")
            return 1
        print("NEGATIVE-SELFTEST: PASS (gate_capture -k removal -> %d expected failure(s))"
              % len(mf))

        for name, mutate in mutations:
            mutated = mutate(original)
            if mutated == original:
                print("NEGATIVE-SELFTEST: ERROR (mutation did not apply: %s)" % name)
                return 1
            with tempfile.TemporaryDirectory() as td:
                os.makedirs(os.path.join(td, ".github/workflows"))
                os.makedirs(os.path.join(td, "ci"))
                (Path(td) / WORKFLOW).write_text(mutated)
                (Path(td) / GATE_CAPTURE).write_text(
                    (Path(root) / GATE_CAPTURE).read_text())
                try:
                    l2, s2, st2, jt2, up2 = load(td)
                    mf = check_workflow(s2, st2, jt2, up2, "mutated")
                except SystemExit as e:
                    mf = ["parse-level rejection: %s" % e]
            if not mf:
                print("NEGATIVE-SELFTEST: FAIL (%s was NOT caught)" % name)
                return 1
            print("NEGATIVE-SELFTEST: PASS (%s -> %d expected failure(s))"
                  % (name, len(mf)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

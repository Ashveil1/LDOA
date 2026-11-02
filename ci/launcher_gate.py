#!/usr/bin/env python3
"""Live-only focus/no-ANR readiness gate for the CI emulator.

What this proves: at observation time, the DEFAULT display's section of a
live "dumpsys window displays" parses cleanly, shows exactly one parseable
non-null mCurrentFocus window and one parseable mFocusedApp record, and
contains no "Application Not Responding" dialog window. READY requires TWO
CONSECUTIVE observations carrying the SAME normalized (focus, app) pair
(focus and app may differ - dialogs can hold focus over another app).

What this does NOT prove: launcher responsiveness, input health, or any
future state. It is a focus/no-ANR precondition only.

Never uses "dumpsys window lastanr": after any ANR that section carries a
FROZEN replay that persists after recovery - not current unhealthiness.

Fail-closed rules: empty / header-only / adb error / timeout / malformed /
null / duplicate-or-conflicting focus or app fields / default-display
missing => UNKNOWN, never READY. ANR dialog present => OBSTRUCTED.
UNKNOWN and OBSTRUCTED both exit 1 with distinct labels.
Bounded: <= OBSERVATIONS observations, INTERVAL_S apart; each adb call
capped at min(DUMP_TIMEOUT_S, remaining budget); the overall monotonic
budget is re-checked after the adb call, including before declaring READY.
"""
import re
import subprocess
import sys
import time

ADB = ["adb", "-s", "emulator-5554"]
OBSERVATIONS = 6
INTERVAL_S = 10
DUMP_TIMEOUT_S = 15
BUDGET_S = 150

HEADER = "WINDOW MANAGER DISPLAY CONTENTS"
ANR_MARK = "Application Not Responding"
DISPLAY_RE = re.compile(r"^\s*Display: mDisplayId=(\d+)\b")
# Every field occurrence is counted first (FOCUS_FIELD/APP_FIELD), then the
# captured value is validated against the exact grammar - a null or
# malformed field can never be silently skipped.
FOCUS_FIELD = re.compile(r"mCurrentFocus=(.+?)\s*$", re.M)
APP_FIELD = re.compile(r"mFocusedApp=(.+?)\s*$", re.M)
WINDOW_VAL = re.compile(r"^Window\{[0-9a-f]+ u0 (.+)\}$")
APP_VAL = re.compile(r"^ActivityRecord\{[0-9a-f]+ u0 (\S+) t\d+\}$")
# Component grammar: package/Class or package/.Class (dots, $, underscores).
COMPONENT = re.compile(r"^[A-Za-z][\w.]*(?:/[\w.$]+)$")


class Unknown(Exception):
    pass


def display0_section(dump):
    """The default display's own section; never another display's fields."""
    lines = dump.splitlines()
    start = None
    for idx, line in enumerate(lines):
        m = DISPLAY_RE.match(line)
        if m and m.group(1) == "0":
            start = idx
            break
    if start is None:
        raise Unknown("default display section missing")
    end = len(lines)
    for j in range(start + 1, len(lines)):
        if DISPLAY_RE.match(lines[j]):
            end = j
            break
    return lines[start:end]


def parse_component(field_vals, val_re, section, name):
    if len(field_vals) != 1:
        raise Unknown("%s count=%d in display 0 section (absent/duplicate/conflicting)"
                      % (name, len(field_vals)))
    raw = field_vals[0]
    if raw == "null":
        raise Unknown("%s is null" % name)
    m = val_re.match(raw)
    if not m:
        raise Unknown("%s malformed: %s" % (name, raw[:80]))
    comp = m.group(1)
    if not COMPONENT.match(comp):
        raise Unknown("%s component not a component: %s" % (name, comp[:80]))
    return comp


def observe(dump):
    """One live dump -> (focus_component, app_component, obstructed)."""
    if not dump or not dump.strip():
        raise Unknown("empty dump")
    if HEADER not in dump:
        raise Unknown("header missing (not a live displays dump)")
    section = display0_section(dump)
    text = "\n".join(section)
    # ANR obstruction is directly observable on its own and must win over
    # focus parsing: the dialog's own focus line contains spaces and is not
    # a parseable component.
    if ANR_MARK in text:
        return None, None, True
    focus = parse_component(FOCUS_FIELD.findall(text), WINDOW_VAL, section, "mCurrentFocus")
    app = parse_component(APP_FIELD.findall(text), APP_VAL, section, "mFocusedApp")
    return focus, app, False


def live_dump(remaining_s):
    timeout = min(DUMP_TIMEOUT_S, max(1.0, remaining_s))
    try:
        r = subprocess.run(ADB + ["shell", "dumpsys", "window", "displays"],
                           capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Unknown("adb/dumpsys timeout >%.0fs" % timeout)
    except OSError as e:
        raise Unknown("adb exec error: %s" % e)
    if r.returncode != 0:
        raise Unknown("adb exit=%d: %s" % (r.returncode, (r.stderr or "").strip()[:120]))
    return r.stdout


def main(label, dump_fn=None):
    if dump_fn is None:
        dump_fn = live_dump
    deadline = time.monotonic() + BUDGET_S
    last_pair = None
    consistent = 0
    obstructed = 0
    unknowns = 0
    i = 0
    while i < OBSERVATIONS and time.monotonic() < deadline:
        i += 1
        try:
            focus, app, is_anr = observe(dump_fn(deadline - time.monotonic()))
        except Unknown as u:
            unknowns += 1
            consistent = 0
            last_pair = None
            print("launcher gate %s: observation %d UNKNOWN (%s)" % (label, i, u))
        else:
            if is_anr:
                obstructed += 1
                consistent = 0
                last_pair = None
                print("launcher gate %s: observation %d live ANR dialog present" % (label, i))
            else:
                pair = (focus, app)
                if pair == last_pair:
                    consistent += 1
                else:
                    consistent = 1
                    last_pair = pair
                print("launcher gate %s: observation %d focus=%s app=%s consistent=%d"
                      % (label, i, focus, app, consistent))
                if consistent >= 2 and time.monotonic() < deadline:
                    print("launcher gate %s: READY - focus/no-ANR precondition met"
                          " (two consecutive consistent focus+app pairs; NOT proof of"
                          " launcher responsiveness)" % label)
                    return 0
        if i < OBSERVATIONS and time.monotonic() < deadline:
            time.sleep(min(INTERVAL_S, max(0.0, deadline - time.monotonic())))
    verdict = "OBSTRUCTED" if obstructed and not unknowns else "UNKNOWN"
    print("launcher gate %s: FAIL - %s (observations=%d obstructed=%d unknown=%d)"
          " - ENVIRONMENTAL; no dismissal, no restart, no suppression change per policy"
          % (label, verdict, i, obstructed, unknowns))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "unlabeled"))

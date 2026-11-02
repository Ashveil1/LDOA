#!/usr/bin/env python3
"""Fixture tests for ci/launcher_gate.py (parser + streak semantics).

Fixtures mirror the CURRENT live 'dumpsys window displays' format, taken
from the real run-41510706613 focus dump (Display: mDisplayId=0 (organized)
section, one-space mCurrentFocus/mFocusedApp lines). Run: python3
ci/test_launcher_gate.py  (plain asserts, no pytest).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import launcher_gate as g

g.INTERVAL_S = 0  # fixtures must not sleep real seconds

SEC0 = """  Display: mDisplayId=0 (organized)
    init=1080x2400 420dpi cur=1080x2400
  mCurrentFocus=Window{c8534c7 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}
  mFocusedApp=ActivityRecord{59953ac u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t7}
"""
HEALTHY = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" + SEC0

SEC0_APP2 = SEC0.replace(
    "mFocusedApp=ActivityRecord{59953ac u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t7}",
    "mFocusedApp=ActivityRecord{1afff3d u0 dev.phonelinux.proto/.MainActivity t8}")
HEALTHY_APP2 = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" + SEC0_APP2

ANR_OBSTRUCTED = HEALTHY.replace(
    "mCurrentFocus=Window{c8534c7 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}",
    "mCurrentFocus=Window{b2b3dda u0 Application Not Responding: com.google.android.apps.nexuslauncher}")

NULL_FOCUS = HEALTHY.replace(
    "mCurrentFocus=Window{c8534c7 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}",
    "mCurrentFocus=null")

# display 0 null focus but display 1 valid: must NOT ready (scoping).
D0_NULL_D1_VALID = ("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"
    "  Display: mDisplayId=0 (organized)\n"
    "  mCurrentFocus=null\n"
    "  mFocusedApp=null\n"
    "  Display: mDisplayId=1 (organized)\n"
    "  mCurrentFocus=Window{abc123 u0 com.a/com.a.Main}\n"
    "  mFocusedApp=ActivityRecord{abc123 u0 com.a/.Main t3}\n")

# display 10 exists, display 0 absent: "mDisplayId=0" substring must not leak.
ONLY_D10 = ("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"
    "  Display: mDisplayId=10 (organized)\n"
    "  mCurrentFocus=Window{abc123 u0 com.a/com.a.Main}\n"
    "  mFocusedApp=ActivityRecord{abc123 u0 com.a/.Main t3}\n")

# one valid + one null focus in the SAME display-0 section: conflict.
VALID_PLUS_NULL = HEALTHY + "  mCurrentFocus=null\n"
# one valid + one malformed focus in the SAME section: conflict.
VALID_PLUS_MALFORMED = HEALTHY + "  mCurrentFocus=Window{zz u0 broken}\n"
HEADER_ONLY = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"


def expect_unknown(dump, why):
    try:
        g.observe(dump)
    except g.Unknown:
        return
    raise AssertionError("expected Unknown for: " + why)


def seq_main(dumps):
    it = iter(dumps)
    return g.main("fixture", dump_fn=lambda remaining: next(it))


def main():
    # --- parser ---
    focus, app, anr = g.observe(HEALTHY)
    assert focus == "com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity", focus
    assert app == "com.google.android.apps.nexuslauncher/.NexusLauncherActivity", app
    assert anr is False

    _, _, anr = g.observe(ANR_OBSTRUCTED)
    assert anr is True, "ANR dialog window must be detected"

    expect_unknown("", "empty dump")
    expect_unknown(HEADER_ONLY, "header-only (no display, no focus)")
    expect_unknown(NULL_FOCUS, "null mCurrentFocus")
    expect_unknown(D0_NULL_D1_VALID, "display0 null + display1 valid must not READY")
    expect_unknown(ONLY_D10, "display10 present, display0 missing")
    expect_unknown(VALID_PLUS_NULL, "valid+null focus conflict in section")
    expect_unknown(VALID_PLUS_MALFORMED, "valid+malformed focus conflict in section")
    expect_unknown("garbage error text\n", "error/no-header output")

    # --- streak semantics (injected dump_fn, no adb) ---
    assert seq_main([HEALTHY, HEALTHY]) == 0, "two consistent pairs => READY"
    assert seq_main([HEALTHY, HEALTHY_APP2, HEALTHY, HEALTHY]) == 0, \
        "pair change resets streak; later consistent pair READIES"
    assert seq_main([ANR_OBSTRUCTED, HEALTHY, HEALTHY]) == 0, \
        "obstruction resets streak; recovery READIES"
    # alternating pairs never reach READY -> UNKNOWN exit
    assert seq_main([HEALTHY, HEALTHY_APP2, HEALTHY, HEALTHY_APP2, HEALTHY, HEALTHY_APP2]) == 1
    print("ALL FIXTURE ASSERTIONS PASS (9 parser + 4 streak)")


if __name__ == "__main__":
    main()

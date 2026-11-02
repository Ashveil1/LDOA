#!/bin/sh
# emulator-lane.sh - ALL emulator-step orchestration for input-harness.yml,
# invoked as ONE runner line: `sh ci/emulator-lane.sh`.
#
# WHY THIS FILE EXISTS: reactivecircus/android-emulator-runner@v2 does NOT
# run its `script:` input as a shell script. parseScript (src/script-parser.ts
# on the v2 tag) splits the input on newlines, trims each line, drops
# comment/blank lines, and executes EACH PHYSICAL LINE as a separate
# `sh -c <line>` (src/main.ts:221-224), awaited in order, first nonzero exit
# aborting the rest. Consequences proven by run 41331931845 (job.log:1090-1091,
# "/usr/bin/sh: 1: Syntax error: end of file unexpected (expecting \"done\")"):
#   - multi-line constructs (for/if/function with body on later lines) are
#     syntax errors;
#   - variables, functions and traps NEVER persist across lines (each line is
#     a fresh process);
#   - cwd DOES persist (single process.chdir); env DOES persist from the
#     step/job env only (src/main.ts:224: process.env + EMULATOR_PORT +
#     ANDROID_SERIAL).
# Keeping orchestration in ONE checked-in file makes it reviewable,
# shellcheckable, and fixture-testable; the runner block stays one line.
#
# Failure model (unchanged from the per-line era): no set -e; every arm is
# individually bounded and guarded; per-arm failures touch marker files under
# /tmp; the marker evaluation at the end exits nonzero. A harvest that fails
# REMOVES its target: a harvested file is intact device content or ABSENT,
# never a masquerading partial/error payload.
#
# Env in: LANE (job env: full|native-only|koe-only|virgl-only|session),
# E1 (job env: off|on - the E1 session-diet EXPERIMENT; on = -e ldoaE1 1
# is passed to the VirglGnomeSessionTest instrument ONLY; default off),
# ANDROID_SERIAL (provided by the runner; default emulator-5554).

SERIAL="${ANDROID_SERIAL:-emulator-5554}"

# pull_or_absent <device-files/name> <host-target> <timeout-s> <LABEL>
# Common harvest contract for EVERY device pull: intact device content or
# ABSENT - never a masquerading partial/error payload. adb exec-out can
# return rc=0 while the remote cat fails (its error text lands in the
# redirect target - observed as the 60-byte phantom-snapshots.log payload
# in run 41340609906), and a nonzero pull can still leave partial bytes.
# ANY nonzero exit, empty result, or "^cat: files/" error body REMOVES the
# target. ABSENT = no record harvested; it is NEVER proof the event or
# file never existed on device.
pull_or_absent() {
  _src="$1"; _dst="$2"; _tmo="$3"; _lbl="$4"
  _rc=0
  timeout "$_tmo" adb -s "$SERIAL" exec-out run-as dev.phonelinux.proto cat "files/$_src" > "$_dst" || _rc=$?
  if [ "$_rc" -ne 0 ]; then
    rm -f "$_dst"
    echo "$_lbl: $_src pull FAILED rc=$_rc - target removed (partial bytes are not evidence); classified ABSENT (no record, not proof the file never existed)"
  elif [ ! -s "$_dst" ] || head -c 40 "$_dst" | grep -q "^cat: files/"; then
    rm -f "$_dst"
    echo "$_lbl: $_src ABSENT on device (or error payload masquerading as content) - removed; classified NO-RECORD (not proof the file never existed)"
  fi
}

# Bounded logcat capture for THIS run's instrument window: started here,
# pid persisted to logcat-run.pid, stopped+flushed by the EXPLICIT
# if:always() cleanup step in the workflow (no traps: the old per-line model
# made them no-ops, and an in-script trap would be invisible to review).
# Uploaded via the if:always() artifact step.
adb -s "$SERIAL" logcat -G 16M || true
adb -s "$SERIAL" logcat -c || true
adb -s "$SERIAL" logcat -b main -b system -b crash -v threadtime > logcat-run.txt 2>&1 &
LP=$!
# Persist pid AND /proc starttime: the cleanup step re-verifies BOTH plus
# the exact command before EVERY signal, so a reused PID is never signaled.
ST=$(sed 's/^.*) //' "/proc/$LP/stat" 2>/dev/null | awk '{print $20}')
echo "$LP $ST" > logcat-run.pid

adb -s "$SERIAL" shell settings put global anr_show_background 0 || true
if [ "$(cat ci/launcher-suppression.conf 2>/dev/null || echo on)" = "on" ]; then
  adb -s "$SERIAL" shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
  echo "launcher suppression: ON"
else
  echo "launcher suppression: OFF"
fi
mkdir -p screenshots
# Gate the REAL build/install outcomes before any instrument: without
# set -e an unguarded failure here would run arms against an invalid or
# STALE install. The redirect (not a tee pipeline) keeps the gradle rc
# unmasked; the same full log is still uploaded via /tmp/gradle-build.log.
# A clear exit here still runs the if:always() cleanup + upload steps.
if ! ./gradlew assembleDebug assembleDebugAndroidTest > /tmp/gradle-build.log 2>&1; then
  tail -40 /tmp/gradle-build.log
  echo "GRADLE-BUILD-FAILED - refusing to instrument against an invalid build"
  exit 1
fi
tail -40 /tmp/gradle-build.log
adb -s "$SERIAL" install -r -g app/build/outputs/apk/debug/app-debug.apk || { echo "APK-INSTALL-FAILED (app-debug.apk) - refusing to instrument against a stale install"; exit 1; }
adb -s "$SERIAL" install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || { echo "APK-INSTALL-FAILED (app-debug-androidTest.apk) - refusing to instrument against a stale install"; exit 1; }

# SETUP: cold-first-launch + auto-enter/consent path coverage.
# The test ends with an OWNED identity teardown (test-owned ordered
# setup/teardown). am instrument force-stops the app per invocation (AOSP 14
# AMS startInstrumentation, logcat reason "start inst"/"finished inst"; run
# 41244200992 21:41:00.885/21:47:53.893), so nothing tracked crosses to the
# harness - the harness does its OWN tracked in-invocation warmup
# (SessionReset.trackedWarmup). The cold-first-launch path stays a separate
# open qualification blocker (run 40840614400: shell maps no UI cold);
# coverage NOT dropped.
date -u +"SETUP auto-enter start %H:%M:%S (lane=$LANE)"
if [ "$LANE" = full ] || [ "$LANE" = session ]; then
  adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.UbuntuDesktopAutoEnterTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr0.log | tail -8
else
  echo "autoenter SKIPPED (lane=$LANE)" > /tmp/instr0.log
fi
if [ "$LANE" = full ] || [ "$LANE" = session ]; then grep -q "OK (" /tmp/instr0.log || touch /tmp/PHONELINUX_SETUP_FAILED; fi
date -u +"SETUP auto-enter end %H:%M:%S"
if [ -f /tmp/PHONELINUX_SETUP_FAILED ]; then echo "AUTOENTER-SETUP-FAILED - arms not run"; exit 1; fi

# EVIDENCE-ONLY harness: asserts bootstrap facts, logs/attaches all
# gesture-attribution outcomes. A green job means "harness executed", never
# "gesture qualified".
# Native-terminal discriminator FIRST, independently bounded: it has never
# completed in any run (blocked behind the harness stall); a later harness
# wedge must not leave it unmeasured again.
date -u +"NATIVE-FAKEROOT start %H:%M:%S"
if [ "$LANE" = full ] || [ "$LANE" = native-only ]; then
  timeout -s KILL 1500 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.NativeTerminalFakeRootTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr-native.log | tail -15 || echo "NATIVE-INSTR-TIMEOUT: outer 1500s bound hit - instrument never returned"
else
  echo "native SKIPPED (lane=$LANE)" > /tmp/instr-native.log
fi
if [ "$LANE" = full ] || [ "$LANE" = native-only ]; then grep -q "OK (" /tmp/instr-native.log || touch /tmp/PHONELINUX_NATIVE_FAILED; fi
if [ -f /tmp/PHONELINUX_NATIVE_FAILED ]; then
  echo "NATIVE-DIAG survivors at bound:"
  adb -s "$SERIAL" shell "ps -A -o PID,PPID,ARGS" | grep -E "phonelinux|proot|bash|Xlorie" || true
  adb -s "$SERIAL" shell am force-stop dev.phonelinux.proto || true
fi

# GNOME session lifecycle regression (retry-implies-stop, clobber window,
# concurrent stops): cheap (sleep-process fakes, no rootfs), always on,
# independently bounded.
date -u +"LIFECYCLE start %H:%M:%S"
# Bound 300->1500 covers the real-GNOME-session fixtures in this arm (lifecycle + reentry); not a product-hang mask.
if [ "$LANE" = full ] || [ "$LANE" = native-only ]; then
  timeout -s KILL 1500 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.GnomeSessionLifecycleTest,dev.phonelinux.proto.OpenDesktopReentryTest,dev.phonelinux.proto.GnomeFallbackRecoveryTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr-lifecycle.log | tail -8 || echo "LIFECYCLE-INSTR-TIMEOUT: outer 300s bound hit - instrument never returned"
else
  echo "lifecycle SKIPPED (lane=$LANE)" > /tmp/instr-lifecycle.log
fi
if [ "$LANE" = full ] || [ "$LANE" = native-only ]; then grep -q "OK (" /tmp/instr-lifecycle.log || touch /tmp/PHONELINUX_LIFECYCLE_FAILED; fi

# Bounded VirGL transport proof (dispatch-only lane). MINIMAL setup:
# ubuntu-base + mesa runtime only - NO GNOME install or launch anywhere in
# this lane. Stage 0 = rootfs provision; stage 1 = host vtest server
# lifecycle + hostprobe host-EGL renderer + stop/start overlap; stage 2 =
# guest eglprobe renderer proof on EGL surfaceless (baseline arm, then
# opt-in virpipe arm with a holding client so Stop runs with an owned forked
# worker). glxinfo was rejected as the probe: on the emulator GLX has no
# usable direct-rendering device, so it reports the X SERVER's renderer,
# never the guest mesa driver - and run 41307671476 lost even that output to
# tail -10 + a discarded Proot.Result. Full reports are pulled and gated
# below: a missing/empty report FAILS the lane, never masks.
if [ "$LANE" = virgl-only ]; then
  adb -s "$SERIAL" shell mkdir -p /sdcard/Android/data/dev.phonelinux.proto/files && adb -s "$SERIAL" push ci/virgl/eglprobe-x86_64 ci/virgl/eglprobe-aarch64 /sdcard/Android/data/dev.phonelinux.proto/files/ || { echo "VIRGL-PROBE-PUSH-FAILED - instruments NOT run"; exit 1; }
fi
if [ "$LANE" = virgl-only ]; then
  timeout -s KILL 900 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.VirglRootfsProvisionTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr-virgl-provision.log | tail -20 || echo "VIRGL-PROVISION-INSTR-TIMEOUT: outer 900s bound hit - instrument never returned"
else
  echo "virgl-provision SKIPPED (lane=$LANE)" > /tmp/instr-virgl-provision.log
fi
if [ "$LANE" = virgl-only ]; then grep -q "OK (" /tmp/instr-virgl-provision.log || touch /tmp/PHONELINUX_VIRGL_FAILED; fi
if [ "$LANE" = virgl-only ] && [ ! -f /tmp/PHONELINUX_VIRGL_FAILED ]; then
  timeout -s KILL 900 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.VirglServerSmokeTest,dev.phonelinux.proto.VirglProbeTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr-virgl.log | tail -40 || echo "VIRGL-INSTR-TIMEOUT: outer 900s bound hit - instrument never returned"
else
  echo "virgl SKIPPED (lane=$LANE)" > /tmp/instr-virgl.log
fi
if [ "$LANE" = virgl-only ]; then grep -q "OK (" /tmp/instr-virgl.log || touch /tmp/PHONELINUX_VIRGL_FAILED; fi
if [ "$LANE" = virgl-only ]; then
  for f in virgl-provision.txt virgl-smoke.txt virgl-probe-default.txt virgl-probe-virpipe.txt virgl-probe-nofork.txt virgl-server.log virgl-visual-bench.txt virgl-visual-scratch.txt virgl-frame-llvmpipe.ppm virgl-frame-virgl.ppm; do
    pull_or_absent "$f" "$f" 60 "VIRGL-HARVEST"
  done
fi
# Evidence contract: every expected report must exist NON-EMPTY (the tests
# write stub reports for unreached arms, so absence means capture broke, not
# that the arm was skipped).
if [ "$LANE" = virgl-only ]; then
  for f in virgl-provision.txt virgl-smoke.txt virgl-probe-default.txt virgl-probe-virpipe.txt virgl-probe-nofork.txt virgl-visual-bench.txt; do
    if [ ! -s "$f" ] || head -c 40 "$f" | grep -q "^cat: files/"; then
      echo "VIRGL-EVIDENCE-MISSING $f (absent, empty, or remote cat error captured)"
      touch /tmp/PHONELINUX_VIRGL_FAILED
    fi
  done
fi

# Diagnostics export + reuse-gate health (cheap, rootfs-free, gates the
# user-facing ZIP export). Full output printed and archived - no tail-only
# retention for gate classes.
date -u +"EXPORT/REUSEGATE start %H:%M:%S"
if [ "$LANE" = full ] || [ "$LANE" = native-only ]; then
  timeout -s KILL 300 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.DiagnosticsExportTest,dev.phonelinux.proto.ReuseGateHealthTest,dev.phonelinux.proto.GnomeSessionScriptSafetyTest,dev.phonelinux.proto.GnomeRetryGateTest,dev.phonelinux.proto.GnomeSessionPerfConfigTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr-export.log || echo "EXPORT-INSTR-TIMEOUT: outer 300s bound hit - instrument never returned"
else
  echo "export SKIPPED (lane=$LANE)" > /tmp/instr-export.log
fi
if [ "$LANE" = full ] || [ "$LANE" = native-only ]; then grep -q "OK (" /tmp/instr-export.log || touch /tmp/PHONELINUX_EXPORT_FAILED; fi

date -u +"HARNESS arms start %H:%M:%S (lane=$LANE)"
if [ "$LANE" = full ]; then
  timeout -s KILL 1500 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.LdoaInputHarnessTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr.log | tail -15 || echo "HARNESS-INSTR-TIMEOUT: outer 1500s bound hit - instrument never returned"
else
  echo "harness SKIPPED (lane=$LANE)" > /tmp/instr.log
fi
if [ "$LANE" = full ]; then grep -q "OK (" /tmp/instr.log || touch /tmp/PHONELINUX_TESTS_FAILED; fi
if [ -f /tmp/PHONELINUX_TESTS_FAILED ]; then
  echo "HARNESS-DIAG survivors at bound:"
  adb -s "$SERIAL" shell "ps -A -o PID,PPID,ARGS" | grep -E "phonelinux|proot|nautilus|Xlorie|soffice" || true
  pull_or_absent harness-owned-ledger.txt harness-owned-ledger.txt 60 "LEDGER-HARVEST (ledger needs the test-side spawn-ledger patch)"
  adb -s "$SERIAL" shell am force-stop dev.phonelinux.proto || true
fi

# P3 real-session virgl qualification (full + session lanes: needs the
# provisioned GNOME from the autoenter setup; session lane = autoenter
# setup + this arm + KOE ONLY - the P3 iteration vehicle. The full lane
# is UNCHANGED and remains the regression gate for native/lifecycle/
# export/harness coverage; session never substitutes for it.
# GNOME from autoenter setup; starts its OWN opted-in session and tears it
# down itself, so KOE still sees a clean world). Placement after the harness
# arms, before KOE. The script-safety string tests ride the SAME instrument
# invocation (comma class list) so they actually execute in this lane.
# E1 experiment arg: EXPLICIT opt-in only (dispatch input e1=on). The
# default is OFF everywhere - regression/full/session runs stay stock.
E1ARG=""
if [ "$E1" = "on" ]; then E1ARG="-e ldoaE1 1"; fi
# VTEST-MC evaluation arg: EXPLICIT opt-in only (dispatch input
# vtestmc=on -> -e ldoaVtestMc 1). Default off = stock fork-per-client
# server everywhere; regression/full runs keep this off.
VTESTMCARG=""
if [ "$VTESTMC" = "on" ]; then VTESTMCARG="-e ldoaVtestMc 1"; fi
# VIRGL DEBUG LOGGING arg: EXPLICIT opt-in only (dispatch input
# virgldebug=on -> -e ldoaVirglDebug 1). Default off = stock launch env on
# both sides; regression/full runs keep this off.
VIRGLDEBUGARG=""
if [ "$VIRGLDEBUG" = "on" ]; then VIRGLDEBUGARG="-e ldoaVirglDebug 1"; fi
# xmap-pixels helper: CI-built on THIS ubuntu-24.04 runner (noble ABI =
# the guest rootfs; ci/xmap-pixels/BUILD.md) and pushed for the session
# arm's bounded root pixel probe. A build/push failure does NOT fail the
# arm (the test then records window-pixels NO-RECORD) but is logged
# loudly; the runner-side sha256 is printed for reviewer comparison with
# the test's recorded device-side sha256 (recorded pair, not a gate).
if [ "$LANE" = full ] || [ "$LANE" = session ]; then
  # Stale delivery can never execute: remove any previous device-side
  # binary FIRST, and after the push VERIFY byte equality (pull back +
  # runner sha256 compare) - a failed verification removes the device
  # binary, so a failed/stale build can never run as the helper.
  timeout 30 adb -s "$SERIAL" shell rm -f /data/local/tmp/ldoa-xmap-pixels || true
  if sudo apt-get update -qq && sudo apt-get install -y --no-install-recommends libx11-dev \
     && gcc -O2 -Wall -Wextra -o /tmp/ldoa-xmap-pixels ci/xmap-pixels/xmap-pixels.c -lX11; then
    BUILD_SHA=$(sha256sum /tmp/ldoa-xmap-pixels | cut -d' ' -f1)
    echo "XMAP-PIXELS-BUILD ok runner-sha256: $BUILD_SHA"
    if timeout 60 adb -s "$SERIAL" push /tmp/ldoa-xmap-pixels /data/local/tmp/ldoa-xmap-pixels \
       && timeout 60 adb -s "$SERIAL" pull /data/local/tmp/ldoa-xmap-pixels /tmp/ldoa-xmap-pixels.readback >/dev/null \
       && [ "$(sha256sum /tmp/ldoa-xmap-pixels.readback | cut -d' ' -f1)" = "$BUILD_SHA" ]; then
      echo "XMAP-PIXELS-PUSH ok (readback sha256 EQUAL to build)"
    else
      timeout 30 adb -s "$SERIAL" shell rm -f /data/local/tmp/ldoa-xmap-pixels || true
      echo "XMAP-PIXELS-BUILD push/verify FAILED - device binary removed; test will record window-pixels NO-RECORD"
    fi
  else
    echo "XMAP-PIXELS-BUILD FAILED - test will record window-pixels NO-RECORD (arm continues)"
  fi
fi
# xmap-overlay helper: SAME delivery discipline as xmap-pixels above
# (ci/xmap-overlay/BUILD.md). Links -lXcomposite -lXext for the gated
# composite-overlay probe; guest runtime lib presence is probed by the
# caller (XO_LDCONFIG lines) and the test records a SKIP (never a
# failure) when absent. A build/push failure never fails the arm.
if [ "$LANE" = full ] || [ "$LANE" = session ]; then
  timeout 30 adb -s "$SERIAL" shell rm -f /data/local/tmp/ldoa-xmap-overlay || true
  if sudo apt-get update -qq && sudo apt-get install -y --no-install-recommends libx11-dev libxcomposite-dev \
     && gcc -O2 -Wall -Wextra -o /tmp/ldoa-xmap-overlay ci/xmap-overlay/xmap-overlay.c -lX11 -lXcomposite; then
    BUILD_SHA=$(sha256sum /tmp/ldoa-xmap-overlay | cut -d' ' -f1)
    echo "XMAP-OVERLAY-BUILD ok runner-sha256: $BUILD_SHA"
    if timeout 60 adb -s "$SERIAL" push /tmp/ldoa-xmap-overlay /data/local/tmp/ldoa-xmap-overlay \
       && timeout 60 adb -s "$SERIAL" pull /data/local/tmp/ldoa-xmap-overlay /tmp/ldoa-xmap-overlay.readback >/dev/null \
       && [ "$(sha256sum /tmp/ldoa-xmap-overlay.readback | cut -d' ' -f1)" = "$BUILD_SHA" ]; then
      echo "XMAP-OVERLAY-PUSH ok (readback sha256 EQUAL to build)"
    else
      timeout 30 adb -s "$SERIAL" shell rm -f /data/local/tmp/ldoa-xmap-overlay || true
      echo "XMAP-OVERLAY-BUILD push/verify FAILED - device binary removed; test will record overlay-pixels NO-RECORD"
    fi
  else
    echo "XMAP-OVERLAY-BUILD FAILED - test will record overlay-pixels NO-RECORD (arm continues)"
  fi
fi
date -u +"VIRGL-SESSION start %H:%M:%S (lane=$LANE e1=$E1 vtestmc=$VTESTMC)"
if [ "$LANE" = full ] || [ "$LANE" = session ]; then
  timeout -s KILL 1500 adb -s "$SERIAL" shell am instrument -w $E1ARG $VTESTMCARG $VIRGLDEBUGARG -e class dev.phonelinux.proto.VirglGnomeSessionTest,dev.phonelinux.proto.GnomeSessionScriptSafetyTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr-virgl-session.log | tail -15 || echo "VIRGL-SESSION-INSTR-TIMEOUT: outer 1500s bound hit - instrument never returned"
else
  echo "virgl-session SKIPPED (lane=$LANE)" > /tmp/instr-virgl-session.log
fi
if [ "$LANE" = full ] || [ "$LANE" = session ]; then grep -q "OK (" /tmp/instr-virgl-session.log || touch /tmp/PHONELINUX_VIRGL_SESSION_FAILED; fi
# Pull + VALIDATE: adb exec-out can return rc=0 while the remote cat fails
# (its error text lands in the redirect target), and a nonzero pull can
# still leave partial bytes behind. ANY nonzero exit, empty result, or error
# payload REMOVES the target: a harvested file is intact device content or
# ABSENT, never a masquerading partial/error payload.
if [ "$LANE" = full ] || [ "$LANE" = session ]; then
  for f in virgl-session.txt virgl-session-xcontent.img virgl-session-xcontent.partial virgl-session-xmap.ppm virgl-session-xov.ppm virgl-server.log; do
    pull_or_absent "$f" "$f" 60 "VIRGL-SESSION-HARVEST"
  done
fi
# Report must exist NON-EMPTY; the guest-content image is required only
# when the test recorded a guest-xcontent CAPTURE (a NOT-CAPTURED verdict
# is a recorded outcome, and the .partial is optional evidence), so both
# are pulled but not gated here.
if [ "$LANE" = full ] || [ "$LANE" = session ]; then
  if [ ! -s virgl-session.txt ] || head -c 40 virgl-session.txt | grep -q "^cat: files/"; then
    echo "VIRGL-SESSION-EVIDENCE-MISSING virgl-session.txt (absent, empty, or remote cat error captured)"
    touch /tmp/PHONELINUX_VIRGL_SESSION_FAILED
  fi
fi
# Capture-artifact validity: HEADER CHECK ONLY, never an image decode
# proof. When the report recorded a guest-xcontent CAPTURE, the artifact
# must exist AND match the recorded tool's format header: XWD requires
# file_version==7 at offset 4 PLUS header_size>=100, width/height within
# 1..16384 and file length > header_size (all big-endian); PNG requires
# the FULL 8-byte signature. Absent/invalid = CAPTURE-UNVERIFIED and
# fails the arm - it never passes as captured guest content. What the
# image SHOWS is reviewer pixel inspection, never this gate.
# Pixel-probe artifact validity: HEADER + BOUND CHECK ONLY, never an
# image decode proof. When the test recorded window-pixels SAMPLED, the
# PPM must exist, carry the P6 magic, and stay within the bounded-output
# cap (66000 bytes test-side; 66560 slack here). Absent/invalid fails the
# arm - it never passes as sampled pixels. What the patches SHOW is
# reviewer pixel inspection, never this gate.
if { [ "$LANE" = full ] || [ "$LANE" = session ]; } && [ -s virgl-session.txt ] && grep -q "VERDICT window-pixels: SAMPLED" virgl-session.txt; then
  if [ -s virgl-session-xmap.ppm ] && [ "$(head -c 2 virgl-session-xmap.ppm)" = "P6" ] && [ "$(stat -c %s virgl-session-xmap.ppm)" -le 66560 ]; then
    echo "xmap-pixels artifact header OK (P6 magic, bounded size)"
  else
    echo "XMAP-PIXELS-HARVEST: SAMPLED recorded but artifact absent or invalid (missing, no P6 magic, or over bounded size)"
    touch /tmp/PHONELINUX_VIRGL_SESSION_FAILED
  fi
fi
if { [ "$LANE" = full ] || [ "$LANE" = session ]; } && [ -s virgl-session.txt ] && grep -q "VERDICT guest-xcontent: CAPTURED" virgl-session.txt; then
  img_ok=0
  if [ -s virgl-session-xcontent.img ]; then
    if grep -q "VERDICT guest-xcontent: CAPTURED tool=xwd" virgl-session.txt; then
      be32() { od -An -tx1 -j "$2" -N 4 "$1" 2>/dev/null | tr -d ' \n'; }
      _f=virgl-session-xcontent.img
      hsz=$(be32 $_f 0); ver=$(be32 $_f 4); wid=$(be32 $_f 16); hei=$(be32 $_f 20)
      hsz=${hsz:-00000000}; wid=${wid:-00000000}; hei=${hei:-00000000}
      sz=$(wc -c < $_f | tr -d ' ')
      if [ "$ver" = "00000007" ] && [ $((0x$hsz)) -ge 100 ] \
         && [ $((0x$wid)) -ge 1 ] && [ $((0x$wid)) -le 16384 ] \
         && [ $((0x$hei)) -ge 1 ] && [ $((0x$hei)) -le 16384 ] \
         && [ "$sz" -gt $((0x$hsz)) ]; then img_ok=1; fi
    else
      [ "$(od -An -tx1 -N 8 virgl-session-xcontent.img 2>/dev/null | tr -d ' \n')" = "89504e470d0a1a0a" ] && img_ok=1
    fi
  fi
  if [ "$img_ok" -ne 1 ]; then
    echo "VIRGL-SESSION-CAPTURE-INVALID: report recorded guest-xcontent CAPTURED but the artifact is absent or fails the format HEADER check (XWD version+sane header_size/dimensions/length, or full 8-byte PNG signature) - CAPTURE UNVERIFIED (header check, not image decode proof)"
    touch /tmp/PHONELINUX_VIRGL_SESSION_FAILED
  fi
fi

# XCONTROL check-drawing arm (lane=xcontrol ONLY): simple server-root
# control - the vendored server draws a known RGB square pattern into
# the root pixmap itself (-check-drawing; no guest clients at all).
# Results constrain hypotheses about THIS simple path ONLY: a visible
# pattern does not prove the GNOME compositor/GL path presents, and an
# absent pattern does not establish a presentation defect (invalid
# control, injection/layout/timing remain candidates). No rootfs or
# provisioning needed; own instrument invocation (force-stops per AOSP,
# so no server reuse across arms) and the arm tears down its own server
# by verified identity.
if [ "$LANE" = xcontrol ]; then
  date -u +"XCONTROL check-drawing arm start %H:%M:%S"
  timeout -s KILL 300 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.XServerCheckDrawingTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr-xcontrol.log | tail -10 || echo "XCONTROL-INSTR-TIMEOUT: outer 300s bound hit - instrument never returned"
  grep -q "OK (1 test)" /tmp/instr-xcontrol.log || touch /tmp/PHONELINUX_XCONTROL_FAILED
  pull_or_absent xcontrol-check-drawing.txt xcontrol-check-drawing.txt 60 "XCONTROL-HARVEST"
  if [ ! -s xcontrol-check-drawing.txt ]; then
    echo "XCONTROL-EVIDENCE-MISSING xcontrol-check-drawing.txt (absent, empty, or remote cat error captured)"
    touch /tmp/PHONELINUX_XCONTROL_FAILED
  fi
else
  echo "xcontrol SKIPPED (lane=$LANE)" > /tmp/instr-xcontrol.log
fi

# kill-on-exit + identity-reset + bus-capture fixtures (cheap:
# toybox-symlink shaped fakes + the REAL vendored libproot.so, no rootfs),
# always on, independently bounded. Exact count pinned: the class has 13
# tests; a drift fails loudly here, never silently passes.
# PLACEMENT: after the harness arms. am instrument force-stops the app per
# invocation (AOSP 14 AMS startInstrumentation), so no state crosses into
# the fixture class either way; running last simply keeps fixture 5's
# untracked-world scan on a world the harness already tore down by owned
# identity.
date -u +"KOE-FIXTURES start %H:%M:%S"
timeout -s KILL 300 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.ProotKillOnExitTest dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee /tmp/instr-koe.log | tail -10 || echo "KOE-INSTR-TIMEOUT: outer 300s bound hit - instrument never returned"
grep -q "OK (13 tests)" /tmp/instr-koe.log || touch /tmp/PHONELINUX_KOE_FAILED

pull_or_absent guest-gnome.log guest-gnome.log 60 "GNOME-LOG-HARVEST"
pull_or_absent gnome-recovery-evidence.txt gnome-recovery-evidence.txt 60 "RECOVERY-EVIDENCE-HARVEST (ABSENT = the test wrote no recovery-failure evidence, or harvest failed; NOT proof recovery never failed)"
pull_or_absent ldoa-session-census.txt ldoa-session-census.txt 60 "SESSION-CENSUS-HARVEST (written pre-launch; ABSENT = the census never ran - arm skipped, write failed, or pre-this-instrumentation build)"
pull_or_absent phantom-snapshots.log phantom-snapshots.log 60 "PHANTOM-SNAPSHOTS-HARVEST"
pull_or_absent phantom-dumpsys-raw.log phantom-dumpsys-raw.log 120 "PHANTOM-RAW-HARVEST"
pull_or_absent virgl-server.log virgl-server.log 60 "VIRGL-SERVER-LOG-HARVEST (ABSENT = no record: file missing, empty (buffered stdout never flushed before the signaled teardown), or harvest failed; NOT proof the server printed nothing)"

timeout 60 adb -s "$SERIAL" logcat -d > /tmp/shotlog.txt || true
python3 .github/scripts/extract_shots.py /tmp/shotlog.txt screenshots || true
python3 .github/scripts/extract_shots.py /tmp/instr.log screenshots || true
find screenshots -type f | sort || true
# Presentation honesty: if the virgl session test used the shot-pipeline
# fallback, the screenshot must ACTUALLY land in the artifact - CAPTURED is
# never taken on the call alone.
if { [ "$LANE" = full ] || [ "$LANE" = session ]; } && [ -s virgl-session.txt ] && grep -q "shot pipeline" virgl-session.txt; then
  ls screenshots/ 2>/dev/null | grep -q "virgl-session-desktop" || { echo "VIRGL-SESSION-SHOT-MISSING: shot-pipeline fallback claimed but no screenshot landed"; touch /tmp/PHONELINUX_VIRGL_SESSION_FAILED; }
fi
# Same honesty for the xcontrol arm: a recorded shot-pipeline capture
# must land the screenshot.
if [ "$LANE" = xcontrol ] && [ -s xcontrol-check-drawing.txt ] && grep -q "shot pipeline" xcontrol-check-drawing.txt; then
  ls screenshots/ 2>/dev/null | grep -q "xcontrol-check-drawing" || { echo "XCONTROL-SHOT-MISSING: shot-pipeline capture claimed but no screenshot landed"; touch /tmp/PHONELINUX_XCONTROL_FAILED; }
fi

if [ -f /tmp/PHONELINUX_LIFECYCLE_FAILED ]; then echo "LIFECYCLE-FAILED-OR-TIMEOUT"; fi
if [ -f /tmp/PHONELINUX_KOE_FAILED ]; then echo "KOE-FIXTURES-FAILED-OR-TIMEOUT"; fi
if [ -f /tmp/PHONELINUX_NATIVE_FAILED ]; then echo "NATIVE-FAKEROOT-FAILED-OR-TIMEOUT"; fi
if [ -f /tmp/PHONELINUX_EXPORT_FAILED ]; then echo "EXPORT-REUSEGATE-FAILED-OR-TIMEOUT"; fi
if [ -f /tmp/PHONELINUX_TESTS_FAILED ]; then exit 1; fi
if [ -f /tmp/PHONELINUX_LIFECYCLE_FAILED ]; then exit 1; fi
if [ -f /tmp/PHONELINUX_KOE_FAILED ]; then exit 1; fi
if [ -f /tmp/PHONELINUX_NATIVE_FAILED ]; then exit 1; fi
if [ -f /tmp/PHONELINUX_EXPORT_FAILED ]; then exit 1; fi
if [ -f /tmp/PHONELINUX_VIRGL_SESSION_FAILED ]; then echo "VIRGL-SESSION-FAILED-OR-TIMEOUT"; fi
if [ -f /tmp/PHONELINUX_VIRGL_FAILED ]; then exit 1; fi
if [ -f /tmp/PHONELINUX_VIRGL_SESSION_FAILED ]; then exit 1; fi
if [ -f /tmp/PHONELINUX_XCONTROL_FAILED ]; then echo "XCONTROL-FAILED-OR-TIMEOUT"; fi
if [ -f /tmp/PHONELINUX_XCONTROL_FAILED ]; then exit 1; fi

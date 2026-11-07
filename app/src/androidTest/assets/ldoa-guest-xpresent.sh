#!/bin/sh
# ldoa-guest-xpresent.sh - bounded guest X11 PRESENTATION discriminator
# for the VirGL black-frame attribution. Runs INSIDE the guest rootfs
# (proot). Three sequential arms, each ending in a bounded xwd -root
# capture, so post-run pixel inspection answers:
#   control  - can THIS capture path see known color at all?
#              (xsetroot paints the root window solid red; no GL)
#   llvmpipe - does an EGL/X11 window surface present under the guest's
#              software driver? (green window)
#   virpipe  - does the SAME mechanism present under virpipe?
#              (blue window) - the mutter-x11 presentation path in
#              miniature; renderer string is recorded per arm.
# A black virpipe capture with a green llvmpipe capture and a red
# control localizes the black frame to the virpipe X11 presentation
# stage; other combinations discriminate capture-path vs generic
# EGL/X11 presentation failure.
#
# usage:
#   ldoa-guest-xpresent.sh <outbase> <probe-path>   capture mode
#   ldoa-guest-xpresent.sh CLEANUP <outbase>        cleanup mode
# outbase MUST be /tmp/ldoa-xpres-<id> with id matching [A-Za-z0-9-]+
# probe-path MUST be /tmp/xpresentprobe-<id> with the same charset.
#
# stdout protocol (parsed by the instrumented test):
#   XP_TOOL    <name>=present|absent
#   XP_ARM     arm=control|llvmpipe|virpipe ... (one per arm, see below)
#   XP_RESULT  error=<bad-outbase|bad-probe-path|stale-removal-failed|
#              tools-unavailable-to-bound>
#   XP_CLEANUP confirmed-removed-all | error=unconfirmed remaining:<paths>
#
# exit codes: 0 arms attempted (per-arm failures are in XP_ARM fields,
#   never in the script exit code); 40 bad outbase; 41 stale removal
#   unconfirmed; 42 bad probe path; 44 required bound unavailable.
#
# Bounds (same contract as ldoa-guest-xcapture.sh: the outer runGuest
# timeout only SIGKILLs proot and ORPHANS guest-side processes, so
# in-guest bounds are the only reliable ones): every tool call AND the
# probe run under `timeout -k`; every arm runs under `ulimit -f`
# covering capture output and probe logs; /tmp free floor 384MiB
# required before any invocation; accepted captures are regular files
# of 1000..67108864 bytes; symlink outputs are refused UNREAD and
# removed.
MODE=capture
if [ "$1" = "CLEANUP" ]; then MODE=cleanup; shift; fi
OUT="$1"
PROBE="$2"
case "$OUT" in
  /tmp/ldoa-xpres-*) ;;
  *) echo "XP_RESULT error=bad-outbase"; exit 40 ;;
esac
ID="${OUT#/tmp/ldoa-xpres-}"
case "$ID" in
  ""|*[!A-Za-z0-9-]*) echo "XP_RESULT error=bad-outbase"; exit 40 ;;
esac
if [ "$MODE" = "capture" ]; then
  case "$PROBE" in
    NONE) ;;
    /tmp/xpresentprobe-*)
      PID2="${PROBE#/tmp/xpresentprobe-}"
      case "$PID2" in
        ""|*[!A-Za-z0-9-]*) echo "XP_RESULT error=bad-probe-path"; exit 42 ;;
      esac ;;
    *) echo "XP_RESULT error=bad-probe-path"; exit 42 ;;
  esac
fi
export DISPLAY="${DISPLAY:-:0}"
CAP_MIN=1000
CAP_MAX=67108864
FILE_BLOCKS=131072
FREE_MIN_KB=393216
ERR="$OUT.err"
PATHS="$OUT-control.xwd $OUT-llvmpipe.xwd $OUT-virpipe.xwd $OUT-llvmpipe.log $OUT-virpipe.log $OUT.err"

if [ "$MODE" = "cleanup" ]; then
  rc=0; left=""
  for f in $PATHS; do
    if [ -e "$f" ] || [ -L "$f" ]; then rm -f "$f" 2>/dev/null || rc=1; fi
    if [ -e "$f" ] || [ -L "$f" ]; then rc=1; left="$left $f"; fi
  done
  if [ "$rc" -eq 0 ]; then echo "XP_CLEANUP confirmed-removed-all"; exit 0; fi
  echo "XP_CLEANUP error=unconfirmed remaining:$left"; exit 45
fi

BOUND_OK=1
command -v timeout >/dev/null 2>&1 || BOUND_OK=0
if [ "$BOUND_OK" -eq 1 ]; then timeout -k 1 1 true >/dev/null 2>&1 || BOUND_OK=0; fi
( ulimit -f 64 ) 2>/dev/null || BOUND_OK=0
line=$(df -Pk /tmp 2>/dev/null | sed -n '$p')
set -- $line
avail=$4
case "$avail" in ''|*[!0-9]*) BOUND_OK=0 ;; *) [ "$avail" -lt "$FREE_MIN_KB" ] && BOUND_OK=0 ;; esac
if [ "$BOUND_OK" -ne 1 ]; then
  echo "XP_RESULT error=tools-unavailable-to-bound (need: GNU timeout with -k, ulimit -f, /tmp free >= ${FREE_MIN_KB}KiB; no arm invoked)"
  exit 44
fi

for f in $PATHS; do
  if [ -e "$f" ] || [ -L "$f" ]; then rm -f "$f" 2>/dev/null; fi
  if [ -e "$f" ] || [ -L "$f" ]; then echo "XP_RESULT error=stale-removal-failed path=$f"; exit 41; fi
done

for t in xwd xsetroot; do
  if command -v "$t" >/dev/null 2>&1; then echo "XP_TOOL $t=present"; else echo "XP_TOOL $t=absent"; fi
done

run_bounded() {
  (
    ulimit -f "$FILE_BLOCKS" 2>/dev/null || exit 126
    exec timeout -k 1 "$1" sh -c "$2"
  )
}

check_capture() {
  # $1=arm $2=file -> echoes verdict fragment; returns 0 accept / 1 reject
  if [ -L "$2" ]; then
    echo "rejected=output-is-symlink (refused unread)"
    rm -f "$2"; return 1
  fi
  if [ ! -f "$2" ]; then
    echo "rejected=output-missing"
    return 1
  fi
  bytes=$(wc -c < "$2" 2>/dev/null || echo 0)
  if [ "$bytes" -ge "$CAP_MIN" ] && [ "$bytes" -le "$CAP_MAX" ]; then
    echo "out=$2 bytes=$bytes"
    return 0
  fi
  rm -f "$2"
  echo "rejected-size bytes=$bytes bounds=$CAP_MIN..$CAP_MAX (removed)"
  return 1
}

# ARM control: paint the ROOT solid red (no GL anywhere), capture, then
# restore black. Proves the capture path can see known color; also
# proves direct root writes are visible to xwd in this server.
if command -v xsetroot >/dev/null 2>&1 && command -v xwd >/dev/null 2>&1; then
  run_bounded 8 "xsetroot -solid '#e02020'" 2>"$ERR"
  rc_set=$?
  sleep 1
  run_bounded 12 "xwd -root -silent -out '$OUT-control.xwd'" 2>"$ERR"
  rc_cap=$?
  verdict=$(check_capture control "$OUT-control.xwd")
  echo "XP_ARM arm=control xsetroot-exit=$rc_set xwd-exit=$rc_cap $verdict"
  run_bounded 8 "xsetroot -solid black" 2>"$ERR"
  sleep 1
else
  echo "XP_ARM arm=control skipped=tool-absent (xsetroot and xwd both required)"
fi

# ARM llvmpipe / virpipe: the EGL-on-X11 present probe, per-driver.
# The probe maps a 320x240 window at +64+64, clears to a solid color,
# swaps 45 frames, holds 8s; capture fires 4s in, while the window is
# mapped with content on its front buffer.
run_probe_arm() {
  # $1=arm-name $2=gallium-driver $3=$4=$5=rgb
  if [ "$PROBE" = "NONE" ] || [ ! -x "$PROBE" ]; then
    echo "XP_ARM arm=$1 skipped=probe-absent-or-not-executable path=$PROBE"
    return
  fi
  if ! command -v xwd >/dev/null 2>&1; then
    echo "XP_ARM arm=$1 skipped=tool-absent (xwd)"
    return
  fi
  LOG="$OUT-$1.log"
  (
    ulimit -f "$FILE_BLOCKS" 2>/dev/null || exit 126
    export GALLIUM_DRIVER="$2"
    exec timeout -k 2 40 "$PROBE" --rgb "$3" "$4" "$5" --frames 45 --hold 8
  ) >"$LOG" 2>&1 &
  bg=$!
  sleep 4
  run_bounded 12 "xwd -root -silent -out '$OUT-$1.xwd'" 2>"$ERR"
  rc_cap=$?
  wait "$bg"
  prc=$?
  rend=$(sed -n 's/^XPRESENT-RENDERER //p' "$LOG" 2>/dev/null | head -1)
  [ -n "$rend" ] || rend="(none)"
  lt=$(tail -c 160 "$LOG" 2>/dev/null | tr '\n' ' ')
  verdict=$(check_capture "$1" "$OUT-$1.xwd")
  echo "XP_ARM arm=$1 probe-exit=$prc renderer=$rend xwd-exit=$rc_cap $verdict log-tail=$lt"
}

run_probe_arm llvmpipe llvmpipe 0 1 0
run_probe_arm virpipe virpipe 0 0 1

rm -f "$ERR"
exit 0

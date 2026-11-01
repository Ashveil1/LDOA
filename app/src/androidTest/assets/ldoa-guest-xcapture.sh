#!/bin/sh
# ldoa-guest-xcapture.sh - bounded guest X-root CONTENT capture probe.
# Runs INSIDE the guest rootfs (proot), independent of Android focus or
# presentation: it answers "did the X server receive drawable content"
# with an actual image, so a black Android screenshot can never be
# conflated with proof of absent guest X content.
#
# usage:
#   ldoa-guest-xcapture.sh <outbase>            capture mode
#   ldoa-guest-xcapture.sh CLEANUP <outbase>    cleanup mode
# outbase MUST be /tmp/ldoa-xcap-<id> with id matching [A-Za-z0-9-]+
# (no slashes, dots, or traversal).
#
# stdout protocol (parsed by the instrumented test):
#   XC_ATTEMPT tool=<name> exit=<rc> [stderr=<head>]
#              [rejected-size|rejected=output-is-symlink|...]
#              [partial=<path> excerpt-max=262144 origbytes=<n> sha256=<h>]
#   XC_RESULT  tool=<name> exit=0 out=<path> bytes=<n>      (success)
#   XC_RESULT  error=<bad-outbase|stale-removal-failed|
#              tools-unavailable-to-bound|no-tool-succeeded>
#   XC_CLEANUP confirmed-removed-all | error=unconfirmed remaining:<paths>
#
# exit codes: 0 captured / cleanup confirmed; 40 bad outbase;
#   41 stale removal unconfirmed; 43 no tool produced a bounded image;
#   44 a required bound is unavailable (timeout with -k, ulimit -f, or
#      the free-space floor) - NO tool is invoked unboundable;
#   45 cleanup unconfirmed (remaining paths listed).
#
# Bounds (verified against Proot.kt: the outer runGuest timeout only
# SIGKILLs proot itself and ORPHANS the guest-side tool, so in-guest
# bounds are the only reliable ones):
#   TIME: every tool call (xwd, import, gnome-screenshot, ffmpeg AND the
#     xdpyinfo probe - FIVE sites) runs under `timeout -k 1 $TOOL_BUDGET`
#     (TERM, then KILL 1s later, process-group scoped so spawned
#     descendants are escalated too). TOOL_BUDGET is clamped 1..12s;
#     worst case 5*(12+1)=65s < 70s, leaving room before the caller's
#     90s outer bound.
#   DISK: every tool runs in a subshell with `ulimit -f 131072` (512-byte
#     blocks = 64MiB) covering BOTH the capture output AND the stderr
#     redirect - a flooding tool dies on SIGXFSZ at the cap. A /tmp free
#     floor of 384MiB is required before ANY invocation.
#   EVIDENCE: accepted images are regular files (never symlinks) of
#     1000..67108864 bytes; rejected/failed output is retained as a
#     BOUNDED 256KiB excerpt with original size recorded; sha256 is
#     computed only under the same time bound; symlink outputs are
#     refused UNREAD and removed.
MODE=capture
if [ "$1" = "CLEANUP" ]; then MODE=cleanup; shift; fi
OUT="$1"
case "$OUT" in
  /tmp/ldoa-xcap-*) ;;
  *) echo "XC_RESULT error=bad-outbase"; exit 40 ;;
esac
ID="${OUT#/tmp/ldoa-xcap-}"
case "$ID" in
  ""|*[!A-Za-z0-9-]*) echo "XC_RESULT error=bad-outbase"; exit 40 ;;
esac
export DISPLAY="${DISPLAY:-:0}"
CAP_MIN=1000
CAP_MAX=67108864
FILE_BLOCKS=131072
FREE_MIN_KB=393216
EXCERPT=262144
ERR="$OUT.stderr"
TOOL_BUDGET="${XCAP_TOOL_TIMEOUT:-12}"
case "$TOOL_BUDGET" in ''|*[!0-9]*) TOOL_BUDGET=12 ;; esac
[ "$TOOL_BUDGET" -lt 1 ] && TOOL_BUDGET=12
[ "$TOOL_BUDGET" -gt 12 ] && TOOL_BUDGET=12
PATHS="$OUT.xwd $OUT.png $OUT.stderr $OUT.partial-xwd $OUT.partial-import $OUT.partial-gnome-screenshot $OUT.partial-ffmpeg"

if [ "$MODE" = "cleanup" ]; then
  rc=0; left=""
  for f in $PATHS; do
    if [ -e "$f" ] || [ -L "$f" ]; then rm -f "$f" 2>/dev/null || rc=1; fi
    if [ -e "$f" ] || [ -L "$f" ]; then rc=1; left="$left $f"; fi
  done
  if [ "$rc" -eq 0 ]; then echo "XC_CLEANUP confirmed-removed-all"; exit 0; fi
  echo "XC_CLEANUP error=unconfirmed remaining:$left"; exit 45
fi

# Bound-availability probe: without ANY of these, no tool can be bounded
# reliably, so NONE is invoked.
BOUND_OK=1
command -v timeout >/dev/null 2>&1 || BOUND_OK=0
if [ "$BOUND_OK" -eq 1 ]; then timeout -k 1 1 true >/dev/null 2>&1 || BOUND_OK=0; fi
( ulimit -f 64 ) 2>/dev/null || BOUND_OK=0
line=$(df -Pk /tmp 2>/dev/null | sed -n '$p')
set -- $line
avail=$4
case "$avail" in ''|*[!0-9]*) BOUND_OK=0 ;; *) [ "$avail" -lt "$FREE_MIN_KB" ] && BOUND_OK=0 ;; esac
if [ "$BOUND_OK" -ne 1 ]; then
  echo "XC_RESULT error=tools-unavailable-to-bound (need: GNU timeout with -k, ulimit -f, /tmp free >= ${FREE_MIN_KB}KiB; no tool invoked)"
  exit 44
fi

# Leftovers from an earlier invocation (including dangling symlinks) must
# be CONFIRMED removed over the exact allowlisted path set: a stale or
# planted path must never be mis-bound or followed as this run's output.
for f in $PATHS; do
  if [ -e "$f" ] || [ -L "$f" ]; then rm -f "$f" 2>/dev/null; fi
  if [ -e "$f" ] || [ -L "$f" ]; then echo "XC_RESULT error=stale-removal-failed path=$f"; exit 41; fi
done

run_tool() {
  # $@ = tool command; file-size cap (covers capture output AND stderr
  # via inheritance) + time bound with TERM-then-KILL group escalation.
  (
    ulimit -f "$FILE_BLOCKS" 2>/dev/null || exit 126
    exec timeout -k 1 "$TOOL_BUDGET" "$@"
  )
}

sha() {
  # time-bounded; never an unbounded read of arbitrarily large output
  command -v sha256sum >/dev/null 2>&1 || { echo unavailable; return; }
  h=$(timeout -k 1 "$TOOL_BUDGET" sha256sum "$1" 2>/dev/null) || { echo unavailable; return; }
  echo "${h%% *}"
}

keep_excerpt() {
  # $1=tool $2=file: bounded excerpt retained, original removed;
  # symlinks are refused UNREAD (never followed) and just removed.
  if [ -L "$2" ]; then rm -f "$2"; return 2; fi
  if [ -f "$2" ]; then
    head -c "$EXCERPT" "$2" > "$OUT.partial-$1" 2>/dev/null
    rm -f "$2"
    return 0
  fi
  return 1
}

report_ok() {
  # $1=tool $2=file; exits 0 on an in-bounds REGULAR image
  if [ -L "$2" ]; then
    echo "XC_ATTEMPT tool=$1 rejected=output-is-symlink (refused unread)"
    rm -f "$2"; return 1
  fi
  if [ ! -f "$2" ]; then
    echo "XC_ATTEMPT tool=$1 rejected=output-not-regular"
    return 1
  fi
  b=$(wc -c < "$2" 2>/dev/null || echo 0)
  if [ "$b" -ge "$CAP_MIN" ] && [ "$b" -le "$CAP_MAX" ]; then
    echo "XC_RESULT tool=$1 exit=0 out=$2 bytes=$b"
    rm -f "$ERR"
    exit 0
  fi
  s=$(sha "$2")
  keep_excerpt "$1" "$2"
  echo "XC_ATTEMPT tool=$1 exit=0 rejected-size bytes=$b bounds=$CAP_MIN..$CAP_MAX partial=$OUT.partial-$1 excerpt-max=$EXCERPT origbytes=$b sha256=$s"
  return 1
}

attempt_fail() {
  # $1=tool $2=rc $3=file
  e=$(head -c 160 "$ERR" 2>/dev/null | tr '\n' ' ')
  extra=""
  if [ -L "$3" ]; then
    rm -f "$3"; extra=" symlink-output-refused-unread"
  elif [ -f "$3" ]; then
    b=$(wc -c < "$3" 2>/dev/null || echo 0); s=$(sha "$3")
    keep_excerpt "$1" "$3"
    extra=" partial=$OUT.partial-$1 excerpt-max=$EXCERPT origbytes=$b sha256=$s"
  fi
  echo "XC_ATTEMPT tool=$1 exit=$2 stderr=$e$extra"
}

if command -v xwd >/dev/null 2>&1; then
  run_tool xwd -root -silent -out "$OUT.xwd" 2>"$ERR"
  rc=$?
  if [ "$rc" -eq 0 ] && [ -e "$OUT.xwd" ]; then
    report_ok xwd "$OUT.xwd"
  else
    attempt_fail xwd "$rc" "$OUT.xwd"
  fi
fi
if command -v import >/dev/null 2>&1; then
  run_tool import -window root "$OUT.png" 2>"$ERR"
  rc=$?
  if [ "$rc" -eq 0 ] && [ -e "$OUT.png" ]; then
    report_ok import "$OUT.png"
  else
    attempt_fail import "$rc" "$OUT.png"
  fi
fi
if command -v gnome-screenshot >/dev/null 2>&1; then
  run_tool gnome-screenshot -f "$OUT.png" 2>"$ERR"
  rc=$?
  if [ "$rc" -eq 0 ] && [ -e "$OUT.png" ]; then
    report_ok gnome-screenshot "$OUT.png"
  else
    attempt_fail gnome-screenshot "$rc" "$OUT.png"
  fi
fi
if command -v ffmpeg >/dev/null 2>&1 && command -v xdpyinfo >/dev/null 2>&1; then
  dims=$(run_tool xdpyinfo 2>/dev/null | sed -n 's/.*dimensions: *\([0-9]*x[0-9]*\).*/\1/p' | head -1)
  if [ -n "$dims" ]; then
    run_tool ffmpeg -y -f x11grab -video_size "$dims" -i "$DISPLAY" -frames:v 1 "$OUT.png" 2>"$ERR"
    rc=$?
    if [ "$rc" -eq 0 ] && [ -e "$OUT.png" ]; then
      report_ok ffmpeg "$OUT.png"
    else
      attempt_fail ffmpeg "$rc" "$OUT.png"
    fi
  fi
fi
rm -f "$ERR"
echo "XC_RESULT error=no-tool-succeeded"
exit 43

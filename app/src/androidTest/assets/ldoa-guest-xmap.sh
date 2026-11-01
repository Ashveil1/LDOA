#!/bin/sh
# ldoa-guest-xmap.sh <outbase> - bounded window TOPOLOGY probe for the
# GNOME session arm. Answers ONE question: at probe time, does the X
# server hold VIEWABLE windows (map state), and which? It is NOT a
# content probe: Map State IsViewable != drawn != presented. A viewable
# window can be blank; an unmapped window proves nothing about overlay
# or root rendering. Topology only.
#
# stdout protocol (parsed by the instrumented test):
#   XM_TOOL xwininfo=present|absent      (explicit per-tool presence;
#        absence ends the probe as NO-RECORD, never zero-content)
#   XM_CHILDREN rc=<pipeline-rc> bytes=<n> capped=0|1
#        (children listing; production bounded AT THE STREAM: timeout
#        bounds wall time, head -c closes the pipe at 16384 bytes and
#        the writer dies on SIGPIPE - never head-after-unbounded-buffer.
#        dash reports only the pipeline rc; the xwininfo exit code
#        itself is unobserved and recorded as such)
#   XM_CANDIDATES rule=<rule> total-children=<n> picked=<id,...>
#        (selection rule logged verbatim; root is probed separately)
#   XM_STATS id=<xid> name=<name> mapstate=<verbatim|UNPARSED>
#        wxh=<WxH|UNPARSED> rc=<pipeline-rc>   (root + <=8 candidates)
#   XM_DONE probes=<n> (or XM_DONE probes=0 reason=tool-absent)
#   XM_TRUNCATED line-budget             (safety valve, fixed 64 lines)
#
# Mutations: ONE owned temp dir $1.dir with trap cleanup; no config, no
# auth changes, no installs. X access: DISPLAY from the caller's env
# (the session's owned :0 via the bound socket dir), same assumption
# the session launch itself uses; no XAUTHORITY manipulation.
set -u
BASE="${1:?outbase required}"
T="$BASE.dir"
mkdir -p "$T" || { echo "XM_DONE probes=0 reason=tempdir-failed"; exit 0; }
trap 'rm -rf "$T"' EXIT INT TERM

LINES=0
emit() { LINES=$((LINES+1)); if [ "$LINES" -gt 64 ]; then echo "XM_TRUNCATED line-budget"; exit 0; fi; echo "$1"; }

if command -v xwininfo >/dev/null 2>&1; then
  emit "XM_TOOL xwininfo=present"
else
  emit "XM_TOOL xwininfo=absent"
  emit "XM_DONE probes=0 reason=tool-absent"
  exit 0
fi

# Children listing, production bounded at the stream.
timeout -k 1 10 xwininfo -root -children 2>/dev/null | head -c 16384 > "$T.children"
rc=$?
bytes=$(wc -c < "$T.children" 2>/dev/null || echo 0)
capped=0; [ "$bytes" -ge 16384 ] && capped=1
emit "XM_CHILDREN rc=$rc bytes=$bytes capped=$capped"

# Parse: id + name pairs from the children listing. Root id via -root.
ROOTID=$(timeout -k 1 5 xwininfo -root 2>/dev/null | sed -n 's/^xwininfo: Window id: \([^ ]*\).*/\1/p' | head -1)
[ -n "$ROOTID" ] || ROOTID=UNPARSED

# Candidate selection: window ids whose quoted name matches the
# shell-relevant pattern (case-insensitive), up to 8; if none match,
# the first up-to-8 parsed child ids. Rule logged verbatim.
MATCHES=$(grep -ioE '0x[0-9a-f]+ "[^"]*(shell|mutter|gnome|desktop|nautilus)[^"]*"' "$T.children" | awk '{print $1}' | head -8)
if [ -n "$MATCHES" ]; then
  RULE='name~/(shell|mutter|gnome|desktop|nautilus)/i, first 8'
  PICKED="$MATCHES"
else
  RULE='no name match; first 8 parsed child ids'
  PICKED=$(awk '{ if ($1 ~ /^0x[0-9a-fA-F]+$/) print $1 }' "$T.children" | head -8)
fi
TOTAL=$(awk '{ if ($1 ~ /^0x[0-9a-fA-F]+$/) n++ } END { print n+0 }' "$T.children")
emit "XM_CANDIDATES rule=[$RULE] total-children=$TOTAL picked=$(echo $PICKED | tr ' ' ',')"

PROBES=0
stats() { # $1=id
  sid="$1"
  nm=$(grep -F "$sid" "$T.children" | head -1 | sed -n 's/^[^"]*"\([^"]*\)".*/\1/p')
  [ -n "$nm" ] || nm=UNNAMED
  timeout -k 1 5 xwininfo -stats -id "$sid" 2>/dev/null | head -c 4096 > "$T.s"
  src=$?
  ms=$(sed -n 's/^  Map State: //p' "$T.s" | head -1)
  [ -n "$ms" ] || ms=UNPARSED
  wh=$(awk '/^  Width:/{w=$2}/^  Height:/{h=$2}END{if(w&&h)printf "%sx%s",w,h}' "$T.s")
  [ -n "$wh" ] || wh=UNPARSED
  nm1=$(echo "$nm" | tr ' ' '_' | head -c 64)
  emit "XM_STATS id=$sid name=$nm1 mapstate=$ms wxh=$wh rc=$src"
  PROBES=$((PROBES+1))
}

[ "$ROOTID" != UNPARSED ] && stats "$ROOTID"
for id in $PICKED; do stats "$id"; done
emit "XM_DONE probes=$PROBES"
exit 0

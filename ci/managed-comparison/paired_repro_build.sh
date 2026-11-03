#!/bin/bash
# Build only. Explicitly no AVD/app install or comparison fallback.
set -euo pipefail
[[ $# == 1 && ( $1 == first || $1 == second ) ]]
: "${FLOOR_EVIDENCE:?}"
FIXED_PREFIX_REVIEWED_BUILD=yes timeout -k 10 600 bash ci/managed-comparison/build-and-test.sh
LOCAL=$(cat "$FLOOR_EVIDENCE/runner-local-location.txt")
for case in original patched; do
  timeout -k 2 15 python3 ci/managed-comparison/prepare_incoming.py --local "$LOCAL" --case "$case" --out "$FLOOR_EVIDENCE/incoming-$case" > "$FLOOR_EVIDENCE/pin-$case"
done
yes | timeout -k 5 180 "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'ndk;29.0.14206865' 'platforms;android-35' 'build-tools;35.0.0' >/dev/null || [[ ${PIPESTATUS[1]} == 0 ]]

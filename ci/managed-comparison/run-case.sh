#!/bin/bash
# UNEXECUTED one fresh AVD case, manually installed to preserve incomingdata.
set -euo pipefail
: "${MANAGED_CASE_EXEC_APPROVED:?separate execution approval required}"
[[ $MANAGED_CASE_EXEC_APPROVED == yes && $# == 3 ]]
CASE=$1;INCOMING=$2;PIN=$3
[[ $CASE == original || $CASE == patched ]]
[[ $PIN =~ ^[0-9a-f]{64}$ ]]
SERIAL=emulator-5554 # exact selected port, no optional silent fallback
OUT="$GITHUB_WORKSPACE/comparison-evidence/$CASE"
[[ ! -e "$OUT" ]];mkdir -p "$OUT"
trap 'rc=$?; printf "caller_exit=%s\n" "$rc" > "$OUT/caller.txt"' EXIT
# Same APK build reused in two separate fresh AVDs. No -r upgrade/userdata.
timeout -k 2 60 adb -s "$SERIAL" install -g app/build/outputs/apk/debug/app-debug.apk
timeout -k 2 60 adb -s "$SERIAL" install -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
MANAGED_TRANSFER_CASE="$CASE" DISPOSABLE_TRANSFER_APPROVED=yes timeout -k 5 410 bash ci/managed-comparison/incoming-transfer.sh "$INCOMING" "$PIN" "$SERIAL"
# am instrument return0 isn't test success: exact XML/receipt verifier below.
set +e
timeout -k 10 2400 adb -s "$SERIAL" shell am instrument -w -e class dev.phonelinux.proto.MobileDesktopEntryTest#mobileEntryManagedDesktopComparison -e listener dev.phonelinux.proto.ManagedXmlListener -e ldoa_ci_emulator true -e ldoa_managed_desktop true -e ldoa_managed_case "$CASE" -e ldoa_managed_manifest "$PIN" dev.phonelinux.proto.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrumentation.txt" 2>&1
rc=$?;set -e
printf 'instrumentation_caller_rc=%s\n' "$rc" > "$OUT/phase.txt"
# Evidence copied BEFORE emulator termination; no UTP uninstalls in this path.
timeout -k 2 60 adb -s "$SERIAL" pull /sdcard/Android/media/dev.phonelinux.proto/additional_test_output "$OUT/device" || exit 70
[[ $rc == 0 ]]
timeout -k 2 15 python3 ci/managed-comparison/check_case.py "$OUT" "$CASE" "$PIN"
# Actual @After Stop is verified by instrumentation identity checks; do not
# count emulator death alone as owned-process teardown qualification.

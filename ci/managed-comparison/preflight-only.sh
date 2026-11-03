#!/bin/bash
set -euo pipefail
SERIAL=emulator-5554
# Fixed labels and rc only. Never echo canonical paths or private contents.
RECEIPT="$GITHUB_WORKSPACE/preflight-evidence/transfer-guards.txt"
[[ -d $(dirname "$RECEIPT") && ! -e "$RECEIPT" && ! -L "$RECEIPT" ]]
printf 'schema=1\n' > "$RECEIPT"
guard() {
 local label=$1;shift
 local rc=0
 "$@" >/dev/null 2>&1 || rc=$?
 printf 'guard=%s rc=%s\n' "$label" "$rc" >> "$RECEIPT"
 return 0 # measurement records every guard; verdict below
}
SCRIPT=$(realpath -e "$0")
BODY="$(dirname "$SCRIPT")/identity-body.sh"
guard context-identity timeout -k 2 15 adb -s "$SERIAL" shell -T run-as dev.phonelinux.proto sh < "$BODY"
guard parent-nonlink timeout -k 2 15 adb -s "$SERIAL" shell "run-as dev.phonelinux.proto sh -c 'test ! -L files'"
guard parent-type timeout -k 2 15 adb -s "$SERIAL" shell "run-as dev.phonelinux.proto sh -c 'test ! -e files || test -d files'"
guard incoming-absent timeout -k 2 15 adb -s "$SERIAL" shell "run-as dev.phonelinux.proto sh -c 'test ! -e files/managed-incoming'"
guard incoming-nonlink timeout -k 2 15 adb -s "$SERIAL" shell "run-as dev.phonelinux.proto sh -c 'test ! -L files/managed-incoming'"
guard tmp-absent timeout -k 2 15 adb -s "$SERIAL" shell "test ! -e /data/local/tmp/ldoa-managed-incoming"
guard tmp-nonlink timeout -k 2 15 adb -s "$SERIAL" shell "test ! -L /data/local/tmp/ldoa-managed-incoming"
# Identity rc71..77 per reviewed body; other labels original refusal rc. Never values.
if grep -Eq ' rc=[1-9]' "$RECEIPT"; then exit 1; fi

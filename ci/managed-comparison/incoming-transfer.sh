#!/bin/bash
# UNEXECUTED LOCAL candidate. Exactly one already-reviewed case per fresh
# disposable emulator; original/patch are sequential steps on SAME runner.
set -euo pipefail
: "${DISPOSABLE_TRANSFER_APPROVED:?separate binary transfer approval}"
[[ $DISPOSABLE_TRANSFER_APPROVED == yes && $# == 3 ]]
SERIAL=$3
[[ $SERIAL == emulator-5554 ]] # two sequential fresh AVDs on exact same bound port
CASE=$(realpath -e "$1") # prepared incoming dir with prefix payload + sources
PIN=$2
[[ $PIN =~ ^[0-9a-f]{64}$ ]]
[[ $(sha256sum "$CASE/manifest.json" | cut -d' ' -f1) == "$PIN" ]]
# Gate content and size before transfer. No HOME/env or arbitrary runner dirs.
[[ -z $(find "$CASE" -type l -print -quit) ]]
[[ $(find "$CASE" -type f | wc -l) -le 1024 ]]
[[ $(du -sb "$CASE" | cut -f1) -le 134217728 ]]
# Fixed labels and rc only. Never echo canonical paths or private contents.
RECEIPT="$GITHUB_WORKSPACE/comparison-evidence/$MANAGED_TRANSFER_CASE/transfer-guards.txt"
[[ $MANAGED_TRANSFER_CASE == original || $MANAGED_TRANSFER_CASE == patched ]]
[[ -d $(dirname "$RECEIPT") && ! -e "$RECEIPT" && ! -L "$RECEIPT" ]]
printf 'schema=1\n' > "$RECEIPT"
guard() {
 local label=$1;shift
 local rc=0
 "$@" >/dev/null 2>&1 || rc=$?
 printf 'guard=%s rc=%s\n' "$label" "$rc" >> "$RECEIPT"
 return "$rc"
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
# /data/local/tmp is ephemeral emulator storage, not Actions artifact service.
# Plans built only after independent manifest+sourcepin+actualinventory validation.
PLAN=$(mktemp -d)
rmdir "$PLAN"
python3 "$(dirname "$SCRIPT")/directory_plan.py" "$CASE" "$PIN" "$PLAN"
trap 'rm -rf "$PLAN"' EXIT
guard tmp-scaffold timeout -k 2 30 adb -s "$SERIAL" shell -T sh < "$PLAN/scaffold.sh"
guard push timeout -k 5 120 adb -s "$SERIAL" push "$CASE/manifest.json" "$CASE/payload" "$CASE/source" /data/local/tmp/ldoa-managed-incoming/
guard tmp-inventory timeout -k 2 30 adb -s "$SERIAL" shell -T sh -s /data/local/tmp/ldoa-managed-incoming < "$PLAN/verify.sh"
# Same stdin shell runs exact identitybody and then the exclusive copy, so
# no separate run-as invocation gap between identity and mutation.
COPY_BODY=$(mktemp)
trap 'rm -f "$COPY_BODY"; rm -rf "$PLAN"' EXIT
printf 'set -e\n' > "$COPY_BODY"
cat "$BODY" >> "$COPY_BODY"
cat >> "$COPY_BODY" <<'DEVICE_COPY'
test ! -L files && { if test -e files; then test -d files; else mkdir -m 700 files; fi; } && test ! -e files/managed-incoming && test ! -L files/managed-incoming && mkdir -m 700 files/managed-incoming && cp -R /data/local/tmp/ldoa-managed-incoming/. files/managed-incoming/
DEVICE_COPY
# Append exact final inventory verification in same identity-checked shell.
printf 'set -- files/managed-incoming\n' >> "$COPY_BODY"
cat "$PLAN/verify.sh" >> "$COPY_BODY"
guard private-copy timeout -k 2 30 adb -s "$SERIAL" shell -T run-as dev.phonelinux.proto sh < "$COPY_BODY"
echo 'TRANSFER ONLY; package hash/pin reverified in-app before exclusive rootfs stage; NO launch here.'

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
# Exact package/debug identity must be installed on opted-in disposable CI.
timeout -k 2 15 adb -s "$SERIAL" shell run-as dev.phonelinux.proto test ! -e files/managed-incoming
timeout -k 2 15 adb -s "$SERIAL" shell test ! -e /data/local/tmp/ldoa-managed-incoming
# /data/local/tmp is ephemeral emulator storage, not Actions artifact service.
timeout -k 5 120 adb -s "$SERIAL" push "$CASE" /data/local/tmp/ldoa-managed-incoming
timeout -k 2 30 adb -s "$SERIAL" shell "run-as dev.phonelinux.proto sh -c 'mkdir -m 700 files/managed-incoming && cp -R /data/local/tmp/ldoa-managed-incoming/. files/managed-incoming/'"
echo 'TRANSFER ONLY; package hash/pin reverified in-app before exclusive rootfs stage; NO launch here.'

#!/bin/sh
# Read-only evidence capture at readiness-gate failure.
# Invoked as ONE self-contained command per gate-failure line because
# reactivecircus/android-emulator-runner executes every script: line in a
# SEPARATE sh -c process (src/main.ts: for (const script of scripts)
# await exec.exec('sh', ['-c', script])) - no shell state (functions,
# variables) persists between lines. Each adb call is timeout-bounded and
# individually guarded: capture can never mask the gate's exit code.
# All outputs land in paths the always() artifact upload already covers
# (screenshots/, /tmp/shotlog.txt).
mode="${1:-gate}"
timeout 10 adb -s emulator-5554 exec-out screencap -p > "screenshots/gate-fail-${mode}.png" 2>/dev/null || true
timeout 15 adb -s emulator-5554 shell dumpsys window displays > "screenshots/gate-fail-${mode}-displays.txt" 2>/dev/null || true
timeout 20 adb -s emulator-5554 logcat -d > /tmp/shotlog.txt 2>/dev/null || true
exit 0

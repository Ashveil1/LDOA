#!/usr/bin/env python3
"""Host-driven cold-start E2E - the path in-process instrumentation cannot
cover: install the APK onto the RUNNING emulator, drive the real app UI via
uiautomator dumps + input taps, verify the full desktop session comes up
from the app's own "Open desktop" entry point, then am force-stop (app
process death - X server and guest processes share the app uid) and
relaunch: the desktop must return with NO reinstall.

Evidence written: COLDSTART: lines on stdout (workflow log) plus
screenshots/17-cold-desktop.png and screenshots/18-relaunch-desktop.png.
"""
import os
import re
import subprocess
import sys
import time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
PKG = "dev.phonelinux.proto"
APK = "app/build/outputs/apk/debug/app-debug.apk"
MARKER = "files/distros/alpine/.install-complete"
DESKLOG = "files/guest-desktop.log"


def adb(*args, timeout=120):
    return subprocess.run(["adb", "-s", SERIAL, *args],
                          capture_output=True, text=True, timeout=timeout)


def shell(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def runas(cmd, timeout=60):
    return shell("run-as %s %s" % (PKG, cmd), timeout=timeout).stdout


def log(msg):
    print("COLDSTART: " + msg, flush=True)


def fail(msg):
    log("FAIL: " + msg)
    # Evidence at the failure moment: UI tree + pixels + app log tail.
    try:
        xml = dump_xml()
        os.makedirs("screenshots", exist_ok=True)
        open("screenshots/coldstart-failure-ui.xml", "w").write(xml)
        log("failure UI tree saved (" + str(len(xml)) + " bytes)")
    except Exception as e:
        log("failure UI dump failed: " + str(e))
    try:
        shot("coldstart-failure")
    except Exception as e:
        log("failure screencap failed: " + str(e))
    try:
        tail = shell("logcat -d -t 80 -s PhoneLinuxX PhoneLinuxSpike AndroidRuntime", 30).stdout
        open("screenshots/coldstart-failure-logcat.txt", "w").write(tail)
    except Exception as e:
        log("failure logcat failed: " + str(e))
    sys.exit(1)


def dump_xml():
    shell("uiautomator dump /sdcard/coldstart-ui.xml")
    return shell("cat /sdcard/coldstart-ui.xml").stdout


def tap_desc(desc, timeout_s=30):
    deadline = time.time() + timeout_s
    pat = re.compile(
        r'<node[^>]*content-desc="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(desc))
    pat2 = re.compile(
        r'<node[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="%s"' % re.escape(desc))
    while time.time() < deadline:
        xml = dump_xml()
        m = pat.search(xml) or pat2.search(xml)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            shell("input tap %d %d" % ((x1 + x2) // 2, (y1 + y2) // 2))
            log("tapped %s at (%d,%d)" % (desc, (x1 + x2) // 2, (y1 + y2) // 2))
            return True
        time.sleep(2)
    return False


def wait_marker(timeout_s):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if runas("ls %s 2>&1" % MARKER).strip() == MARKER:
            return True
        time.sleep(10)
    return False


def wait_chosen_wm(timeout_s):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        m = re.search(r"chosen-wm=(\w+)", runas("tail -c 3000 %s 2>&1" % DESKLOG))
        if m:
            return m.group(1)
        time.sleep(10)
    return ""


def shot(name):
    shell("screencap -p /sdcard/%s.png" % name)
    os.makedirs("screenshots", exist_ok=True)
    r = adb("pull", "/sdcard/%s.png" % name, "screenshots/%s.png" % name)
    log("screenshot %s: %s %s" % (name, r.stdout.strip(), r.stderr.strip()))


def marker_mtime():
    return runas("stat -c %%Y %s 2>/dev/null || echo NA" % MARKER).strip()


log("install debug apk on the running emulator")
r = adb("install", "-r", APK, timeout=300)
if "Success" not in r.stdout + r.stderr:
    fail("adb install: %s %s" % (r.stdout.strip(), r.stderr.strip()))

def tap_first_match(patterns, timeout_s):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        xml = dump_xml()
        for pat in patterns:
            m = re.search(pat, xml)
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                shell("input tap %d %d" % ((x1 + x2) // 2, (y1 + y2) // 2))
                return pat
        time.sleep(2)
    return ""


log("cold start #1: launch app")
shell("am start -n %s/.MainActivity" % PKG)
time.sleep(6)
# First launch requests POST_NOTIFICATIONS up front (MainActivity.onCreate,
# SDK>=33): the system permission dialog covers the picker. Answer it like a
# user would, then the picker is reachable. (Run 40629958216: install button
# never appeared with the dialog unanswered.)
hit = tap_first_match([
    r'<node[^>]*resource-id="[^"]*permission_allow_button"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    r'<node[^>]*text="Allow"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
], 30)
log("permission dialog handled: " + (hit if hit else "no dialog shown"))
if not tap_desc("install-distro", 60):
    fail("install button never appeared")
log("waiting for distro install marker (up to 15 min)")
if not wait_marker(900):
    fail("distro install never completed")
mtime1 = marker_mtime()
log("install complete, marker mtime=%s" % mtime1)

# Product default, verified through the SHIPPED entry point (not the test
# harness): the app's own first-launch onCreate sets direct touch
# (tap-to-point) when the user has never chosen a mode. Data starts empty
# here because the instrumentation job uninstalls both APKs before us.
prefs = runas("cat shared_prefs/%s_preferences.xml 2>&1" % PKG)
if 'name="touchMode" value="3"' not in prefs:
    fail("direct-touch default not written by shipped app entry; prefs=" + prefs[:600])
log("direct-touch default present after first shipped-app launch")

if not tap_desc("open-desktop", 60):
    fail("open-desktop button never appeared after install")
log("waiting for desktop wm (first-time shell provisioning included, up to 10 min)")
wm = wait_chosen_wm(600)
if not wm:
    fail("desktop session never chose a wm. log tail: " + runas("tail -c 1500 %s 2>&1" % DESKLOG))
log("desktop up: chosen-wm=" + wm)
time.sleep(20)  # let clients map before the pixel check
shot("17-cold-desktop")

log("am force-stop: app process death (X server + guests share the app uid)")
shell("am force-stop " + PKG)
time.sleep(5)
survivors = shell("pgrep -f 'phonelinux|CmdEntryPoint' || true").stdout.strip()
log("uid processes after force-stop: [%s]" % survivors)

log("cold start #2: relaunch - must NOT reinstall")
shell("am start -n %s/.MainActivity" % PKG)
time.sleep(6)
# Truncate the desktop log so a chosen-wm line below proves a FRESH launch.
runas("sh -c '> %s'" % DESKLOG)
if not tap_desc("open-desktop", 90):
    fail("open-desktop button missing after relaunch - installed state did not restore")
log("waiting for desktop to return (packages cached - should be fast)")
wm2 = wait_chosen_wm(300)
if not wm2:
    fail("desktop did not return after process kill. log tail: " + runas("tail -c 1500 %s 2>&1" % DESKLOG))
log("desktop returned: chosen-wm=" + wm2)
time.sleep(20)
shot("18-relaunch-desktop")
mtime2 = marker_mtime()
log("marker mtime before=%s after=%s" % (mtime1, mtime2))
if mtime1 != "NA" and mtime2 != "NA" and mtime2 != mtime1:
    fail("install marker mtime changed - a REINSTALL happened on relaunch")
log("PASS: cold install -> desktop -> process kill -> desktop returns without reinstall")

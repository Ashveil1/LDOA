package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Ubuntu userland GUI: provisions GNOME inside the installed Ubuntu Base
 * rootfs and launches `gnome-shell --x11` against the embedded lorie X
 * server (B: standard Ubuntu layout).
 *
 * STATUS: feasibility UNPROVEN on our stack. Community evidence that
 * Ubuntu GNOME can run under proot + termux-x11 exists
 * (atamshkai/Ubuntu-Gnome-Desktop-On-Termux-x11, LinuxDroidMaster
 * startgnome_ubuntu.sh, docs.udroid.org termux-x11 page), all using the
 * workarounds applied in the session script below; one recipe warns the
 * desktop breaks after a system upgrade, and a Mali/VirGL crash report
 * exists. We run software rendering, so the first goal is "does
 * gnome-shell --x11 start and stay up", captured via guest-gnome.log.
 */
object GuestUbuntu {
    private const val TAG = "PhoneLinuxGnome"

    // Ubuntu-appearance set, tracked explicitly per directive: vanilla
    // GNOME alone is NOT "standard Ubuntu". The Ubuntu look = ubuntu-session
    // (session gsettings overrides via ubuntu-settings) + Yaru themes +
    // ubuntu-wallpapers + ubuntu-dock + the GNOME top bar. Verified by
    // real-render screenshot pixel inspection, not just shell liveness.
    // --no-install-recommends keeps snapd and the full ubuntu-desktop meta
    // out; snapd cannot work without systemd.
    private val GNOME_PACKAGES = listOf(
        "dbus-x11",
        "gnome-shell",
        "gnome-session",
        "ubuntu-session",
        "ubuntu-settings",
        "gnome-shell-extension-ubuntu-dock",
        "yaru-theme-gnome-shell",
        "yaru-theme-gtk",
        "yaru-theme-icon",
        "ubuntu-wallpapers",
        "gnome-terminal",
        "nautilus",
        "x11-utils",
        "xdotool",
        "adwaita-icon-theme",
        "fonts-dejavu-core"
    )

    fun hasGnome(rootfs: File): Boolean = File(rootfs, "usr/bin/gnome-shell").isFile

    // fakeRoot: apt/dpkg refuse to install as a non-root euid; proot -0
    // fakes uid 0 inside the guest (same trick proot-distro uses).
    private fun apt(ctx: Context, rootfs: File, onLine: (String) -> Unit, vararg args: String): Proot.Result =
        Proot.runGuest(ctx, rootfs, listOf("apt-get") + args.toList(),
            extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"), fakeRoot = true, onLine = onLine)

    /** apt-get update + install the GNOME set. One retry on failure: apt
     *  fetch hiccups under proot are transient. */
    fun provisionGnome(ctx: Context, rootfs: File, onLine: (String) -> Unit = {}): Proot.Result {
        onLine("apt-get update: fetching package lists")
        var r = apt(ctx, rootfs, onLine, "update")
        if (r.exitCode != 0) {
            onLine("apt-get update failed (exit " + r.exitCode + ") - one retry")
            Thread.sleep(5_000)
            r = apt(ctx, rootfs, onLine, "update")
            if (r.exitCode != 0) return r
        }
        onLine("apt-get install: downloading and installing the GNOME desktop packages")
        r = apt(ctx, rootfs, onLine, "install", "-y", "--no-install-recommends", *GNOME_PACKAGES.toTypedArray())
        if (r.exitCode != 0) {
            onLine("apt-get install failed (exit " + r.exitCode + ") - one retry")
            Thread.sleep(5_000)
            r = apt(ctx, rootfs, onLine, "install", "-y", "--no-install-recommends", *GNOME_PACKAGES.toTypedArray())
        }
        Log.i(TAG, "gnome provision exit=" + r.exitCode)
        return r
    }

    /** The generated /root/gnome-session.sh content, extracted so the session
     *  env can be regression-tested without launching proot. */
    internal fun gnomeSessionScript(): String =
            """
            #!/bin/sh
            # Backend selection: logind is renamed away below, so mutter cannot
            # learn the session type from it; its tty-gated DISPLAY legacy
            # fallback is also unavailable. Export the type explicitly or
            # gnome-shell exits 1 with "Unsupported session type".
            # Locale: nothing in this launch path sets LANG/LC_*, and
            # gnome-terminal-server exits status 8 (_EXIT_FAILURE_NO_UTF8)
            # under a non-UTF-8 locale (POCO log 2026-10-30: "Process
            # org.gnome.Terminal exited with status 8"). C.UTF-8 is compiled
            # into glibc (>= 2.35), so no locale-gen or extra package.
            export DISPLAY=:0 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg XDG_CURRENT_DESKTOP=ubuntu:GNOME XDG_SESSION_TYPE=x11 LANG=C.UTF-8 LC_ALL=C.UTF-8
            export PLINUX_SESSION=desktop
            # Verbose scope for this diagnostic build - NARROW channels only
            # (2026-10-31 privacy review): G_MESSAGES_DEBUG=all would also
            # enable gvfs/gtk/gio debug domains, which can carry document
            # names, URIs and other guest-private content unrelated to
            # session startup, so it is deliberately NOT set. gnome-shell's
            # glib-domain debug stays off for the same reason (its domain
            # coverage is not verified). Enabled instead:
            # - GNOME_SESSION_DEBUG=1 (set below): session phase/app
            #   transitions - this is what produced the GsmManager DEBUG
            #   lines in existing bundles.
            # - MUTTER_DEBUG with topic names verified against the vendored
            #   mutter 46.2 source (src/core/util.c meta_debug_keys):
            #   backend (backend/renderer setup), dbus (bus registration -
            #   the org.gnome.Shell registration path), stack (window
            #   map/stack changes), sm (session management), startup
            #   (startup notification). No per-frame topics (render/frames):
            #   per-frame spam would cost CPU in the measured path.
            # Remaining exposure: individual lines CAN still contain window
            #   titles or guest paths (e.g. stack traces name windows); the
            #   export scrubber removes secret-shaped values only. Log size
            #   stays capped by the 4 MiB rotation + export budget; verbose
            #   output costs some CPU in the measured path, so timings here
            #   are diagnostic-mode timings, not clean-session timings.
            export MUTTER_DEBUG=backend,dbus,stack,sm,startup
            LAUNCH_ID=$(date +%s)
            echo "gnome-launch: launch id=${'$'}LAUNCH_ID"
            mkdir -p /tmp/xdg
            chmod 0700 /tmp/xdg
            # A stale fail-closed marker from a previous launch must never
            # suppress THIS launch's fallback.
            rm -f /tmp/xdg/ldoa-teardown-failed
            # EVIDENCE + BOUNDED FIX ATTEMPT: dump session state, then
            # explicitly enable the ubuntu dock (enabled-extensions=@as []
            # in run f81bd1a; wallpaper+top bar already render). Logged so
            # the settled shot ties the dock's presence to this action.
            ( sleep 25
              echo "=== gnome-diag begin ==="
              echo "XDG_CURRENT_DESKTOP=${'$'}XDG_CURRENT_DESKTOP"
              echo "-- shell mode (before) --"
              gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Properties.Get org.gnome.Shell Mode 2>&1
              echo "-- enabled-extensions (before) --"
              gsettings get org.gnome.shell enabled-extensions 2>&1
              echo "-- extensions on disk --"
              ls /usr/share/gnome-shell/extensions/ 2>&1
              echo "-- gnome-extensions list --"
              gnome-extensions list 2>&1
              echo "-- FIX ATTEMPT: enable ubuntu-dock --"
              gsettings set org.gnome.shell enabled-extensions "['ubuntu-dock@ubuntu.com']" 2>&1; echo "gsettings-set rc=${'$'}?"
              gnome-extensions enable ubuntu-dock@ubuntu.com 2>&1; echo "ext-enable rc=${'$'}?"
              sleep 15
              echo "-- enabled-extensions (after) --"
              gsettings get org.gnome.shell enabled-extensions 2>&1
              echo "-- shell mode (after) --"
              gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Properties.Get org.gnome.Shell Mode 2>&1
              echo "-- look --"
              gsettings get org.gnome.desktop.background picture-uri 2>&1
              gsettings get org.gnome.desktop.interface gtk-theme 2>&1
              echo "-- gsd processes --"
              pgrep -a -f gsd 2>&1 || echo "no gsd processes"
              echo "=== gnome-diag end ==="
            ) &
            # Same identity fix as the Alpine session: GNOME components call
            # getpwuid(getuid()) and fail for the Android app uid.
            UID_NOW=${'$'}(id -u); GID_NOW=${'$'}(id -g)
            echo "guest-identity: uid=${'$'}UID_NOW gid=${'$'}GID_NOW"
            if ! awk -F: '${'$'}3 == u {f=1} END {exit !f}' u="${'$'}UID_NOW" /etc/passwd; then
              echo "plinux:x:${'$'}UID_NOW:${'$'}GID_NOW:Phone Linux:/root:/bin/sh" >> /etc/passwd
              echo "guest-identity: added passwd entry for uid ${'$'}UID_NOW"
            fi
            if ! awk -F: '${'$'}3 == g {f=1} END {exit !f}' g="${'$'}GID_NOW" /etc/group; then
              echo "plinux:x:${'$'}GID_NOW:" >> /etc/group
            fi
            # proot workarounds from the community GNOME recipes. Each is
            # logged verbosely so a failure can be tied to the workaround
            # that did/did not address it (directive: concrete failures/logs,
            # not cargo-culted changes).
            # - systemd-logind client libraries abort session startup under
            #   proot; rename them away. Idempotency fix: the find must
            #   exclude *.back* or every script pass re-renames yesterday's
            #   backups (POCO VD 2026-10-31: .back.back.back layers in the
            #   failure tail), which is log spam, not a correctness issue.
            #   CAVEAT (consequential, keep): an apt upgrade of the owning
            #   packages restores the files and the session breaks again
            #   until this script re-runs.
            for f in ${'$'}(find /usr -type f -iname "*login1*" ! -iname "*.back*" 2>/dev/null); do mv -v "${'$'}f" "${'$'}f.back"; done
            echo "workaround-login1: ${'$'}(find /usr -type f -iname '*login1*' 2>/dev/null | wc -l) remaining"
            # - the dbus launch helper must be setuid to spawn the session bus.
            #   CAVEAT (consequential, keep): this relaxes a guest file's
            #   permissions vs upstream packaging; scoped to the dbus helper
            #   only, no other setuid changes.
            chmod u+s /usr/lib/dbus-1.0/dbus-daemon-launch-helper 2>/dev/null
            # SYSTEM BUS robustness (bounded; evidence 2026-10-31 phone launch
            # 1793454731 + noble source): /run/systemd/seats exists on this rootfs
            # (package-created, no systemd runs), so gnome-session (gsm-systemd.c:382
            # g_error) and gnome-shell 46 (loginManager.js:17 haveSystemd) select
            # their systemd backends, which need a REACHABLE system bus. Rounds
            # 1-479 connected (upload WARNINGs only, zero connect ERRORs); at 20:52
            # the listener was gone: gnome-session got EPIPE sending credentials ->
            # exited=133; the fallback shell then got ECONNREFUSED (socket file
            # present, no listener) -> main.js exception -> shell died.
            # Rules: NEVER unlink a live daemon's socket/pidfile. Probe first;
            # unlink only after the listener is proven unavailable; validate and log
            # the pidfile pid identity (comm+starttime+uid); capture start output
            # (previously swallowed) so the next bundle says WHY a start failed.
            DBUS_RUN_DIR=${'$'}LDOA_DBUS_RUN_DIR
            [ -z "${'$'}DBUS_RUN_DIR" ] && DBUS_RUN_DIR=/run/dbus
            mkdir -p "${'$'}DBUS_RUN_DIR"
            sysbus_live() {
              gdbus call --system --timeout 2 --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.ListNames >/dev/null 2>&1
            }
            start_system_bus() {
              rm -f "${'$'}DBUS_RUN_DIR/system_bus_socket" "${'$'}DBUS_RUN_DIR/pid"
              BO=${'$'}(dbus-daemon --system --fork 2>&1); BRC=${'$'}?
              echo "gnome-launch: system-bus start rc=${'$'}BRC output=${'$'}{BO:-none}"
              if [ "${'$'}BRC" -ne 0 ]; then
                SO=${'$'}(service dbus start 2>&1); SRC=${'$'}?
                echo "gnome-launch: system-bus service fallback rc=${'$'}SRC output=${'$'}{SO:-none}"
              fi
              sleep 1
              if sysbus_live; then
                echo "gnome-launch: system-bus LIVE after start pid=${'$'}(cat "${'$'}DBUS_RUN_DIR/pid" 2>/dev/null | tr -d '[:space:]')"
              else
                echo "gnome-launch: system-bus UNAVAILABLE after start attempt - systemd-gated components (gnome-session gsm-systemd, shell LoginManagerSystemd) expected to fail this round; continuing for diagnostics"
              fi
            }
            echo "gnome-launch: system-bus state dir=${'$'}DBUS_RUN_DIR ${'$'}(ls -la "${'$'}DBUS_RUN_DIR" 2>&1 | tr '\n' '|')"
            BP=""; BCOMM=""
            if [ -f "${'$'}DBUS_RUN_DIR/pid" ]; then
              BP=${'$'}(cat "${'$'}DBUS_RUN_DIR/pid" 2>/dev/null | tr -d '[:space:]')
              if [ -n "${'$'}BP" ] && [ -d "/proc/${'$'}BP" ]; then
                BCOMM=${'$'}(cat /proc/${'$'}BP/comm 2>/dev/null)
                echo "gnome-launch: system-bus pidfile pid=${'$'}BP alive comm=${'$'}BCOMM starttime=${'$'}(sed 's/^[^)]*) //' /proc/${'$'}BP/stat 2>/dev/null | awk '{print ${'$'}20}') uid=${'$'}(awk '/^Uid:/{print ${'$'}2}' /proc/${'$'}BP/status 2>/dev/null)"
              else
                echo "gnome-launch: system-bus pidfile pid=${'$'}{BP:-empty} not alive"
                BP=""
              fi
            else
              echo "gnome-launch: system-bus pidfile absent"
            fi
            # Decision tree. A failed/timed-out ListNames probe ALONE is not proof of
            # a dead listener: a live daemon can be busy. Unlink is grounded only
            # when the endpoint is proven dead (no live pidfile daemon claiming it).
            if sysbus_live; then
              echo "gnome-launch: system-bus LIVE - preserving existing daemon (socket/pidfile untouched)"
            elif [ -n "${'$'}BP" ] && [ -n "${'$'}BCOMM" ]; then
              case "${'$'}BCOMM" in
                dbus-daemon*)
                  # LIVE daemon, probe failed: possibly a BUSY (transient) daemon.
                  # Never unlink a live daemon's endpoint out from under it. One
                  # bounded retry; if it still does not answer, FAIL CLOSED: keep
                  # the endpoint and run this round without a usable system bus -
                  # the sampler's per-sample probe records whether it recovers, and
                  # the next launch re-evaluates from a preserved endpoint.
                  sleep 2
                  if sysbus_live; then
                    echo "gnome-launch: system-bus LIVE after retry (transient probe failure) - preserved pid=${'$'}BP"
                  else
                    echo "gnome-launch: system-bus daemon pid=${'$'}BP alive but not answering after retry - FAIL CLOSED: endpoint preserved (no unlink/restart); systemd-gated components expected to fail this round"
                  fi ;;
                *)
                  # Pidfile names a live UNRELATED process: the file is stale (pid
                  # reused). Probe failed too, so the endpoint is grounded dead;
                  # the referenced process itself is NOT touched.
                  echo "gnome-launch: system-bus pidfile stale (pid=${'$'}BP comm=${'$'}BCOMM, not a daemon) and listener unavailable - clearing stale endpoint and starting a fresh daemon"
                  start_system_bus ;;
              esac
            else
              echo "gnome-launch: system-bus listener unavailable, no live pidfile daemon - clearing stale endpoint and starting a fresh daemon"
              start_system_bus
            fi
            echo "gnome-launch: pkg gnome-shell=${'$'}(dpkg-query -W -f='${'$'}{Version}' gnome-shell 2>&1 | head -1) gnome-session=${'$'}(dpkg-query -W -f='${'$'}{Version}' gnome-session 2>&1 | head -1) gjs=${'$'}(dpkg-query -W -f='${'$'}{Version}' gjs 2>&1 | head -1) libmutter-14-0=${'$'}(dpkg-query -W -f='${'$'}{Version}' libmutter-14-0 2>&1 | head -1)"
            # FRESH session bus per launch: a killed session's bus address
            # lingers in the X root window (_DBUS_SESSION_BUS_* properties)
            # while the X server keeps running, and the next session then
            # gets "Connection refused" on the dead address - shell never
            # starts (evidence: run 40729917335, proc alive, no shell 300s;
            # the host probe's fresh-X session came up in ~35s). Clear the
            # stale properties and launch our own bus for this session.
            xprop -root -remove _DBUS_SESSION_BUS_ADDRESS 2>/dev/null
            xprop -root -remove _DBUS_SESSION_BUS_PID 2>/dev/null
            eval $(dbus-launch --sh-syntax) 2>&1
            echo "gnome-launch: session bus ${'$'}DBUS_SESSION_BUS_ADDRESS"
            echo ${'$'}DBUS_SESSION_BUS_PID > /root/.gnome-bus.pid 2>/dev/null
            # LDOA DIAGNOSTIC SAMPLER (bounded, read-only): recurring
            # session-state samples for the export ZIP, written to
            # /tmp/ldoa-probes.log (rootfs-local tmp; the app picks it up at
            # distros/ubuntu/tmp/ldoa-probes.log). The cadence captures the
            # stall WINDOW over ~12 min, not one export-time point; a sample
            # can be up to ~15s stale. Per-probe timeouts bound every sample
            # (worst sample ~8s: two 2s bus queries + window loop). Window
            # TITLES are excluded AT COLLECTION: ids come from xprop
            # _NET_CLIENT_LIST (ids only), the xwininfo title header line is
            # never emitted, and only Map State / geometry / WM_CLASS are
            # recorded. _NET_CLIENT_LIST holds MANAGED clients only (mutter
            # guard/composite windows are not listed), so client_windows=0
            # does not prove no override-redirect window exists. GL module
            # paths are what the shell process had LOADED (/proc/pid/maps):
            # evidence, NOT proof of the active renderer (multiple modules
            # can be loaded while one renders). X identity (pid+starttime)
            # is recorded at session start AND per sample: identical
            # starttimes prove the SAME server instance at both points, a
            # changed starttime proves a restart (pairwise comparison;
            # single-snapshot reuse is never claimed).
            ( PF=/tmp/ldoa-probes.log
              MAXB=131072
              # Per-session coverage: if a PREVIOUS session already filled
              # the file, rotate it aside (ONE generation, mirroring the
              # app's log rotation) so this session's evidence is never
              # starved by stale bytes. Bounded: current + prev, never more.
              # The rotation is recorded in the new file, and a session that
              # stops early writes an explicit "stopped: size cap" line -
              # missing samples always have a stated reason, never silence.
              if [ -f "${'$'}PF" ] && [ "${'$'}(wc -c < "${'$'}PF" 2>/dev/null || echo 0)" -gt 65536 ]; then
                mv -f "${'$'}PF" /tmp/ldoa-probes.prev.log
                ROTATED=yes
              fi
              {
              if [ "${'$'}ROTATED" = yes ]; then echo "=== ldoa-probes rotated: previous session's samples are in ldoa-probes.prev.log ==="; fi
              echo "=== ldoa-probes begin utc=${'$'}(date -u +%FT%TZ) launch=${'$'}LAUNCH_ID ==="
              for t in gdbus xwininfo xdpyinfo xrandr glxinfo pgrep pidof xprop; do
                if command -v "${'$'}t" >/dev/null 2>&1; then echo "tool ${'$'}t=available"; else echo "tool ${'$'}t=UNAVAILABLE"; fi
              done
              XP=${'$'}(pgrep -f CmdEntryPoint | head -1)
              if [ -n "${'$'}XP" ]; then
                echo "xserver_identity_at_session_start pid=${'$'}XP ${'$'}(awk '{print "comm=" ${'$'}2 " starttime=" ${'$'}22}' /proc/${'$'}XP/stat 2>/dev/null)"
              else
                echo "xserver_identity_at_session_start NOT_FOUND"
              fi
              i=0
              while [ "${'$'}i" -lt 40 ]; do
                SZ=${'$'}(wc -c < "${'$'}PF" 2>/dev/null || echo 0)
                if [ "${'$'}SZ" -gt "${'$'}MAXB" ]; then echo "=== ldoa-probes stopped: size cap ${'$'}MAXB bytes ==="; break; fi
                i=${'$'}((i + 1))
                echo "--- sample ${'$'}i +${'$'}(( ${'$'}(date +%s) - LAUNCH_ID ))s utc=${'$'}(date -u +%FT%TZ) ---"
                SP=${'$'}(pgrep -x gnome-shell | head -1)
                echo "shell_pid=${'$'}{SP:-none}"
                OWN=${'$'}(gdbus call --session --timeout 2 --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.NameHasOwner org.gnome.Shell 2>&1 | head -1)
                echo "bus_owner_org.gnome.Shell=${'$'}{OWN:-query_failed_or_timeout}"
                case "${'$'}OWN" in
                  *true*) echo "bus_owner_pid=${'$'}(gdbus call --session --timeout 2 --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.GetConnectionUnixProcessID org.gnome.Shell 2>&1 | head -1)" ;;
                esac
                # System bus liveness per sample (the 20:52 bus death was invisible
                # until components failed); COUNT only, never the service names.
                SB=${'$'}(gdbus call --system --timeout 2 --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.ListNames 2>&1 | head -1)
                case "${'$'}SB" in
                  "("*) echo "sysbus_live=yes names=${'$'}(echo "${'$'}SB" | tr ',' '
                ' | grep -c 'org\.')" ;;
                  *) echo "sysbus_live=no err=${'$'}(echo "${'$'}SB" | cut -c1-80)" ;;
                esac
                XP=${'$'}(pgrep -f CmdEntryPoint | head -1)
                if [ -n "${'$'}XP" ]; then
                  echo "xserver_now pid=${'$'}XP ${'$'}(awk '{print "comm=" ${'$'}2 " starttime=" ${'$'}22}' /proc/${'$'}XP/stat 2>/dev/null)"
                else
                  echo "xserver_now NOT_FOUND"
                fi
                V=0; N=0
                for id in ${'$'}(xprop -root _NET_CLIENT_LIST 2>/dev/null | sed 's/.*# //' | tr ',' ' '); do
                  N=${'$'}((N + 1)); [ "${'$'}N" -gt 12 ] && break
                  INFO=${'$'}(xwininfo -id "${'$'}id" 2>/dev/null | awk '/Map State:/{ms=${'$'}3} /Width:/{w=${'$'}2} /Height:/{h=${'$'}2} END{printf "map=%s geo=%sx%s", ms, w, h}')
                  case "${'$'}INFO" in *IsViewable*) V=${'$'}((V + 1)) ;; esac
                  echo "win ${'$'}id ${'$'}INFO class=${'$'}(xprop -id "${'$'}id" WM_CLASS 2>/dev/null | sed 's/.*= //')"
                done
                echo "client_windows=${'$'}N viewable=${'$'}V (sample-time map state of managed clients; titles excluded at collection)"
                if [ "${'$'}i" = "3" ] || [ "${'$'}i" = "8" ]; then
                  if [ -n "${'$'}SP" ]; then
                    echo "shell_gl_modules_begin pid=${'$'}SP (loaded modules; evidence, not active-renderer proof)"
                    grep -oE '/[^ ]*\.so[0-9.]*' "/proc/${'$'}SP/maps" 2>/dev/null | grep -Ei 'dri|libGL|libEGL|llvm|virgl|zink|swrast|vulkan' | sort -u | head -24 | sed 's/^/  /'
                    echo "shell_gl_modules_end"
                  else
                    echo "shell_gl_modules: shell pid not found"
                  fi
                fi
                sleep 15
              done
              echo "=== ldoa-probes end: budget 40 samples reached launch=${'$'}LAUNCH_ID ==="
              } >> "${'$'}PF" 2>&1
            ) &
            # READINESS evidence for the app-side gate: org.gnome.Shell owned
            # on THIS launch's bus. A stalled shell (run 40864638283 re-enter:
            # fallback shell never registered, black screen) fails this poll.
            ( for i in $(seq 1 30); do
                sleep 2
                if gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                  echo "gnome-launch: shell registered launch=${'$'}LAUNCH_ID bus=${'$'}DBUS_SESSION_BUS_ADDRESS"
                  break
                fi
              done
              if ! gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                echo "gnome-launch: shell missing after 60s launch=${'$'}LAUNCH_ID"
                echo "gnome-launch: shell-missing-state xdg=$(ls /tmp/xdg 2>&1 | tr '\n' '|') ice=$(ls /tmp/.ICE-unix 2>&1 | tr '\n' '|') x11=$(ls /tmp/.X11-unix 2>&1 | tr '\n' '|')"
                ps 2>&1 | head -25
              fi ) &
            POLL_A_PID=${'$'}!
            # FIRST-PAINT window evidence (diagnostic): the bus poll above
            # proves only session-bus registration, not windows on screen.
            # Splitting "guest never mapped a window" from "guest mapped,
            # app-side presentation black" needs guest-side window ground
            # truth in this log; the app-side wait conditions are the
            # renderer.cpp buffer-wait logs (Buffer not found / not of
            # expected size). xwininfo/xdpyinfo exist in this rootfs (POCO
            # 2026-10-30). The IsViewable count is INDICATIVE ONLY: on a
            # healthy session mutter's stage/composite windows make it
            # nonzero, but it is not proof of pixels presented on screen.
            ( sleep 5
              PAINT=0
              for i in ${'$'}(seq 1 30); do
                V=0
                for id in ${'$'}(xwininfo -root -children 2>/dev/null | awk '${'$'}1 ~ /^0x/ {print ${'$'}1}'); do
                  S=${'$'}(xwininfo -id "${'$'}id" 2>/dev/null | awk '/Map State:/{print ${'$'}3}')
                  [ "${'$'}S" = "IsViewable" ] && V=${'$'}((V + 1))
                done
                if [ "${'$'}V" -gt 0 ]; then
                  echo "gnome-launch: first-viewable-window +${'$'}((5 + (i - 1) * 2))s count=${'$'}V launch=${'$'}LAUNCH_ID (indicative, not pixel proof)"
                  PAINT=1
                  break
                fi
                sleep 2
              done
              [ "${'$'}PAINT" = 1 ] || echo "gnome-launch: no viewable window after ~65s launch=${'$'}LAUNCH_ID (guest-side map silence suspected, not proven)"
              echo "gnome-launch: x11-geometry=${'$'}(xdpyinfo 2>/dev/null | awk '/dimensions:/{print ${'$'}2}') surface-compare=pending"
            ) &
            # Diag (exit=1 root-cause): gnome-session's g_debug output names the
            # phase/app transitions and the acceleration-check verdicts; the
            # captured stderr otherwise ends at the IceLockAuthFile warning with
            # none of the source's exit=1 signatures (fail-whale paths are
            # silent when zenity cannot run under proot).
            export GNOME_SESSION_DEBUG=1
            # ICE lock probe + ICEAUTHORITY relocation (exit=1 fix attempt):
            # gnome-session died immediately after "IceLockAuthFile failed:
            # Function not implemented" (ENOSYS). libICE IceLockAuthFile
            # (authutil.c) locks with the link() hardlink algorithm - no
            # fcntl/flock - so ENOSYS names a filesystem or proot-emulation
            # gap for link(2). gsm-xsmp-server.c treats the ICEauthority
            # update failure as fatal (gsm_util_init_error(TRUE) -> exit(1)).
            # Probe link() in /root and /tmp/xdg so the log distinguishes
            # path-dependent failure from syscall-level failure, and point
            # ICEAUTHORITY at /tmp/xdg (honored as the full authority path
            # by IceAuthFileName) so the lock lives on tmpfs when /root's
            # backing fs rejects hardlinks.
            ICEHOME=""; ICETMP=""
            touch /root/.iceprobe-a 2>/dev/null && { ln /root/.iceprobe-a /root/.iceprobe-b 2>/dev/null && ICEHOME=OK || ICEHOME=FAIL; }
            rm -f /root/.iceprobe-a /root/.iceprobe-b
            touch /tmp/xdg/.iceprobe-a 2>/dev/null && { ln /tmp/xdg/.iceprobe-a /tmp/xdg/.iceprobe-b 2>/dev/null && ICETMP=OK || ICETMP=FAIL; }
            rm -f /tmp/xdg/.iceprobe-a /tmp/xdg/.iceprobe-b
            echo "gnome-launch: ice-link-probe home=${'$'}ICEHOME tmpxdg=${'$'}ICETMP"
            echo "gnome-launch: ice-pkg $(dpkg-query -W -f='${'$'}{Package}=${'$'}{Version} ' libice6 gnome-session 2>&1)"
            export ICEAUTHORITY=/tmp/xdg/ICEauthority
            echo "gnome-launch: trying gnome-session --session=ubuntu (top bar + ubuntu-dock + yaru)"
            # Syscall-level ICE evidence (run 41192498450): the link probe
            # proved link(2) works from the shell in BOTH candidate dirs,
            # the dpkg line pinned libice6=2:1.0.10-1build3, yet
            # IceLockAuthFile still fails ENOSYS inside gnome-session -
            # so the failure is process-context-specific, not path/fs.
            # Trace the failing syscall + args; the excerpt lands in this
            # log after the death path below. The filter covers the path
            # syscalls AND the lock family (fcntl/flock): the 2026-10-31
            # trace showed ICEauthority creat() succeed with no ENOSYS in
            # the path set. Caveat: proot can synthesize ENOSYS in
            # userspace (no kernel syscall for strace to see) and nested
            # ptrace can be partial, so an empty ENOSYS section never
            # proves the failing call sits outside the filter. Install is
            # guarded: rootfs bootstrap already proved apt works here.
            if ! command -v strace >/dev/null 2>&1; then
              apt-get install -y strace >/dev/null 2>&1
            fi
            if command -v strace >/dev/null 2>&1; then
              strace -f -o /tmp/xdg/gs-ice.strace -e trace=link,linkat,unlink,unlinkat,creat,open,openat,stat,newfstatat,fcntl,flock,close,dup,dup2,dup3 gnome-session --session=ubuntu 2>&1 &
            else
              echo "gnome-launch: strace unavailable - launching untraced"
              gnome-session --session=ubuntu 2>&1 &
            fi
            GSPID=${'$'}!
            # --- Managed-tree teardown helpers (watchdog use only) ----------------
            # The wrapper pid (strace, when instrumentation is active) is NOT the
            # session: killing it only DETACHES the tracees and they keep running
            # (ptrace semantics; device-proven 2026-10-31 rounds 1-479: managed
            # shell JS lines continued ~78s after the wrapper kill, and the
            # fallback never ran). The watchdog must resolve the real gnome-session
            # child, snapshot the OWNED tree as pid:starttime identities BEFORE any
            # kill (pid-reuse safe), re-verify identity per member at kill time,
            # and prove the tree empty before the fallback may exec - a bare shell
            # over live managed components is two WMs on one display. No
            # pkill/killall, no name-based sweeps outside this tree. Zombies count
            # as dead (they hold no session resources).
            pid_starttime() { sed 's/^[^)]*) //' "/proc/${'$'}1/stat" 2>/dev/null | awk '{print ${'$'}20}'; }
            pid_state() { sed 's/^[^)]*) //' "/proc/${'$'}1/stat" 2>/dev/null | awk '{print ${'$'}1}'; }
            children_of() {
              for s in /proc/[0-9]*/stat; do
                P=${'$'}{s#/proc/}; P=${'$'}{P%/stat}
                PP=${'$'}(sed 's/^[^)]*) //' "${'$'}s" 2>/dev/null | awk '{print ${'$'}2}')
                [ "${'$'}PP" = "${'$'}1" ] && echo "${'$'}P"
              done
            }
            snapshot_tree() {
              SNAP="${'$'}1:${'$'}(pid_starttime "${'$'}1")"; FRONT="${'$'}1"; CT=1
              while [ -n "${'$'}FRONT" ] && [ "${'$'}CT" -lt 512 ]; do
                NXT=""
                for P in ${'$'}FRONT; do
                  for C in ${'$'}(children_of "${'$'}P"); do
                    SNAP="${'$'}SNAP ${'$'}C:${'$'}(pid_starttime "${'$'}C")"; NXT="${'$'}NXT ${'$'}C"; CT=${'$'}((CT + 1))
                  done
                done
                FRONT="${'$'}NXT"
              done
              echo "${'$'}SNAP"
            }
            member_alive() {
              [ -d "/proc/${'$'}1" ] || return 1
              [ "${'$'}(pid_state "${'$'}1")" = "Z" ] && return 1
              [ "${'$'}(pid_starttime "${'$'}1")" = "${'$'}2" ]
            }
            kill_tree_verified() {
              for M in ${'$'}1; do
                P=${'$'}{M%%:*}; ST=${'$'}{M##*:}
                if member_alive "${'$'}P" "${'$'}ST"; then
                  echo "gnome-launch: watchdog-tree kill ${'$'}2 pid=${'$'}P comm=${'$'}(cat /proc/${'$'}P/comm 2>/dev/null) starttime=${'$'}ST (identity verified)"
                  [ -z "${'$'}LDOA_FIXTURE_NOKILL" ] && kill -"${'$'}2" "${'$'}P" 2>/dev/null
                fi
              done
              return 0
            }
            tree_survivors() {
              LEFT=""
              for M in ${'$'}1; do P=${'$'}{M%%:*}; ST=${'$'}{M##*:}; member_alive "${'$'}P" "${'$'}ST" && LEFT="${'$'}LEFT ${'$'}M"; done
              echo "${'$'}{LEFT# }"
            }
            managed_teardown() {
              if ! kill -0 "${'$'}1" 2>/dev/null; then
                echo "gnome-launch: watchdog: wrapper pid=${'$'}1 already dead - orphans (if any) have reparented away and cannot be enumerated without a forbidden name sweep; teardown UNRESOLVABLE"
                return 2
              fi
              ROOT=""
              for C in ${'$'}(children_of "${'$'}1"); do
                case "${'$'}(cat /proc/${'$'}C/comm 2>/dev/null)" in gnome-session*) ROOT=${'$'}C ;; esac
              done
              if [ -z "${'$'}ROOT" ]; then
                echo "gnome-launch: watchdog: no gnome-session child under wrapper pid=${'$'}1 (children:${'$'}(for C in ${'$'}(children_of "${'$'}1"); do echo -n " ${'$'}C=${'$'}(cat /proc/${'$'}C/comm 2>/dev/null)"; done)) - killing wrapper only"
                kill "${'$'}1" 2>/dev/null
                return 0
              fi
              SNAP=${'$'}(snapshot_tree "${'$'}ROOT")
              echo "gnome-launch: watchdog: terminating managed session tree root=${'$'}ROOT members=${'$'}(echo ${'$'}SNAP | wc -w) wrapper=${'$'}1"
              kill_tree_verified "${'$'}SNAP" TERM
              kill "${'$'}1" 2>/dev/null
              i=0
              while [ "${'$'}i" -lt 6 ]; do
                LEFT=${'$'}(tree_survivors "${'$'}SNAP"); [ -z "${'$'}LEFT" ] && break
                sleep 0.5; i=${'$'}((i + 1))
              done
              LEFT=${'$'}(tree_survivors "${'$'}SNAP")
              if [ -n "${'$'}LEFT" ]; then
                kill_tree_verified "${'$'}SNAP" KILL
                i=0
                while [ "${'$'}i" -lt 6 ]; do
                  LEFT=${'$'}(tree_survivors "${'$'}SNAP"); [ -z "${'$'}LEFT" ] && break
                  sleep 0.5; i=${'$'}((i + 1))
                done
              fi
              LEFT=${'$'}(tree_survivors "${'$'}SNAP")
              if [ -n "${'$'}LEFT" ]; then
                echo "gnome-launch: watchdog: managed tree NOT empty after TERM+KILL survivors=${'$'}LEFT - FAIL CLOSED"
                return 1
              fi
              echo "gnome-launch: watchdog: managed tree empty (pid+starttime verified, zombies excluded)"
              return 0
            }
            # Watchdog: an unbounded wait would let a wedged gnome-session (shell
            # never registers, session never exits) block the fallback forever
            # while the app gate burns its budget. If no shell owns the bus by
            # ~+112s, terminate the managed tree so the unified fallback runs; the
            # fallback poll's marker or the definitive verdict then lands within
            # the app budget (210s: 112 + <=7 teardown + 90 fallback poll). On an
            # incomplete teardown the fallback is SUPPRESSED (fail closed): the
            # gate-matched definitive verdict is emitted with its reason, and the
            # main flow exits instead of exec'ing a second WM.
            ( for i in ${'$'}(seq 1 55); do
                sleep 2
                # Session gone on its own? The main flow is already heading
                # to the fallback; this poll's work is over. (Also bounds
                # the main flow's "wait WATCHDOG_PID" below to one cycle.)
                kill -0 ${'$'}GSPID 2>/dev/null || exit 0
                if gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                  exit 0
                fi
              done
              if ! gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                echo "gnome-launch: no shell on bus by +112s - terminating managed session tree (wrapper pid=${'$'}GSPID) to reach the fallback"
                managed_teardown "${'$'}GSPID"; TRC=${'$'}?
                if [ "${'$'}TRC" -eq 1 ]; then
                  echo "gnome-launch: fallback shell missing after 90s (not attempted: managed teardown incomplete - fail closed, duplicate-WM risk)"
                  : > /tmp/xdg/ldoa-teardown-failed
                elif [ "${'$'}TRC" -eq 2 ]; then
                  echo "gnome-launch: watchdog: teardown unresolvable (wrapper dead) - proceeding as pre-fix behavior (orphan risk logged)"
                fi
              fi ) &
            WATCHDOG_PID=${'$'}!
            sleep 20
            if kill -0 ${'$'}GSPID 2>/dev/null; then
              echo "gnome-launch: gnome-session alive pid=${'$'}GSPID"
              wait ${'$'}GSPID
              echo "gnome-launch: gnome-session exited=${'$'}? after surviving the 20s check - falling back to bare gnome-shell --x11"
            else
              wait ${'$'}GSPID 2>/dev/null
              echo "gnome-launch: gnome-session died exit=${'$'}? - falling back to bare gnome-shell --x11"
            fi
            if [ -f /tmp/xdg/gs-ice.strace ]; then
              echo "gnome-launch: ice-strace ENOSYS lines:"
              grep -n "ENOSYS" /tmp/xdg/gs-ice.strace | head -6
              echo "gnome-launch: ice-strace ICEauthority-path lines:"
              grep -n "ICEauthority" /tmp/xdg/gs-ice.strace | head -10
              echo "gnome-launch: ice-strace lock-syscall lines:"
              grep -nE "fcntl|flock" /tmp/xdg/gs-ice.strace | head -10
              echo "gnome-launch: ice-strace total lines: ${'$'}(wc -l < /tmp/xdg/gs-ice.strace)"
            fi
            # A LATE gnome-session exit must not end the script. POCO log
            # 2026-10-30 (launch 1793346581): the shell REGISTERED at ~+30s,
            # then gsd-rfkill (signal 6: rfkill udev device has no device
            # file under proot) and gsd-usb-protection (signal 11)
            # crash-looped into gnome-session-failed and the shell vanished
            # from the bus. The bare shell is a working degraded desktop
            # (x86 evidence: wallpaper + top bar + dock pixel-verified).
            echo "gnome-launch: fallback-state xdg=$(ls /tmp/xdg 2>&1 | tr '\n' '|') ice=$(ls /tmp/.ICE-unix 2>&1 | tr '\n' '|') x11=$(ls /tmp/.X11-unix 2>&1 | tr '\n' '|')"
            # The phase-1 readiness poll and watchdog belong to the
            # gnome-session phase: left running, the poll could report the
            # FALLBACK shell's registration as a full success (the markers
            # must stay distinguishable), and the watchdog's kill is moot.
            kill ${'$'}POLL_A_PID 2>/dev/null
            # The watchdog may be MID-TEARDOWN right now: its wrapper kill
            # (or the wrapper's natural exit after our TERM to the session)
            # is what released "wait GSPID" above. Killing the watchdog
            # subshell here (pre-fix code did) can interrupt the teardown
            # AFTER some members died but BEFORE the fail-closed marker is
            # written - the suppression check would then pass over a live
            # managed tree. Wait for it instead; it always exits (ping
            # success, GSPID gone, or teardown complete).
            wait ${'$'}WATCHDOG_PID 2>/dev/null
            if [ -f /tmp/xdg/ldoa-teardown-failed ]; then
              echo "gnome-launch: fallback suppressed - managed teardown incomplete (fail closed; duplicate-WM risk)"
              exit 1
            fi
                        # Fallback-phase readiness poll:
 the first 60s poll can expire
            # before the fallback shell registers. Emit the SAME success
            # marker the app gate matches; this phase's verdict is the
            # definitive failure (the first-phase "shell missing after 60s"
            # is not - the fallback may still be coming).
            ( for i in ${'$'}(seq 1 45); do
                sleep 2
                if gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                  echo "gnome-launch: fallback shell registered launch=${'$'}LAUNCH_ID bus=${'$'}DBUS_SESSION_BUS_ADDRESS"
                  break
                fi
              done
              if ! gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                echo "gnome-launch: fallback shell missing after 90s launch=${'$'}LAUNCH_ID"
              fi ) &
            exec gnome-shell --x11
            """.trimIndent() + "\n"

    /** Launch the GNOME session: workarounds first, then gnome-shell --x11
     *  as the session leader (exec keeps it proot-root's direct child). */
    fun launchGnomeSession(ctx: Context, rootfs: File): Process {
        File(rootfs, "root/gnome-session.sh").writeText(gnomeSessionScript())
        val nd = ctx.applicationInfo.nativeLibraryDir
        val log = File(ctx.filesDir, "guest-gnome.log")
        // Bounded retention: the log is append-only across launches; keep one
        // previous generation instead of growing forever (the diagnostic
        // export includes both files, tail-capped).
        if (log.isFile && log.length() > 4L * 1024 * 1024) {
            val prev = File(ctx.filesDir, "guest-gnome.prev.log")
            try { prev.delete(); if (!log.renameTo(prev)) Log.w(TAG, "gnome log rotation rename failed") }
            catch (t: Throwable) { Log.w(TAG, "gnome log rotation failed", t) }
        }
        val pb = ProcessBuilder(
            File(nd, "libproot.so").absolutePath,
            "--link2symlink",
            "-0", // fake root: GNOME session components + dbus expect euid 0
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root",
            "/bin/sh", "/root/gnome-session.sh"
        )
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root",
            "DISPLAY" to ":0",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath,
            // Same TMPDIR leak as Proot.exec (see there).
            "TMPDIR" to "/tmp"
        ))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        Log.i(TAG, "launching GNOME session, log=$log")
        return pb.start()
    }
}

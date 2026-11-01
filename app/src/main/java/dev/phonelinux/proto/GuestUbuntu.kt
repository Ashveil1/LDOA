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
            # Verbose scope - NARROW channels only
            # (2026-10-31 privacy review): G_MESSAGES_DEBUG=all would also
            # enable gvfs/gtk/gio debug domains, which can carry document
            # names, URIs and other guest-private content unrelated to
            # session startup, so it is deliberately NOT set. gnome-shell's
            # glib-domain debug stays off for the same reason (its domain
            # coverage is not verified).
            # 2026-11-01 narrowing (phone evidence, POCO 25062PC34G, d7fe421):
            # frame-present intervals were 0.5-4.5s during interaction (a
            # presentation-path timing, not isolated render cost; active
            # renderer identity NOT established - libLLVM loads for any
            # gallium driver), and the two
            # heaviest topics were paying CPU in the measured path on every
            # frame and restack: backend logs each cogl_onscreen_swap_* call
            # (per-frame) and stack logs the full MetaStackTracker dump per
            # window-stack op (1/3 of a 390 KB phone export). Default topics
            # are now dbus,sm,startup (bus registration, session management,
            # startup notification - the gate/registration diagnostics that
            # proved their worth this week). LDOA_MUTTER_DEBUG_TOPICS restores
            # the full set for a dedicated diagnostic session, e.g.
            # LDOA_MUTTER_DEBUG_TOPICS=backend,dbus,stack,sm,startup.
            # Remaining exposure: individual lines CAN still contain window
            # titles or guest paths; the export scrubber removes
            # secret-shaped values only. Log size stays capped by the 4 MiB
            # rotation + export budget.
            export MUTTER_DEBUG="${'$'}{LDOA_MUTTER_DEBUG_TOPICS:-dbus,sm,startup}"
            LAUNCH_ID=$(date +%s)
            echo "gnome-launch: launch id=${'$'}LAUNCH_ID"
            mkdir -p /tmp/xdg
            chmod 0700 /tmp/xdg
            # Owned-tree snapshots are keyed by THIS launch's id; drop
            # snapshots from launches over an hour old (never a concurrent
            # launch's file - an in-flight exit check may still read it).
            find /tmp/xdg -maxdepth 1 -name 'ldoa-owned-tree.*' -mmin +60 -delete 2>/dev/null
            # A stale fail-closed marker from a previous launch must never
            # suppress THIS launch's fallback.
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
            sysbus_probe() {
              # Classifies the ENDPOINT, not just success/failure (a boolean probe
              # cannot distinguish a dead socket from a busy live daemon).
              # Prints "CLASS|detail": LIVE | REFUSED (socket file exists, NO
              # listener - endpoint grounded dead) | NOENT (no socket file - no
              # endpoint) | OTHER (timeout/auth/handshake - an endpoint may be
              # listening but unresponsive: state UNKNOWN, preserve). LIVE
              # carries no detail (never log the name list).
              E=${'$'}(gdbus call --system --timeout 2 --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.ListNames 2>&1 | head -1)
              case "${'$'}E" in
                "("*) echo "LIVE|" ;;
                *"Connection refused"*) echo "REFUSED|${'$'}(echo "${'$'}E" | cut -c1-120)" ;;
                *"No such file or directory"*) echo "NOENT|${'$'}(echo "${'$'}E" | cut -c1-120)" ;;
                *) echo "OTHER|${'$'}(echo "${'$'}E" | cut -c1-120)" ;;
              esac
            }
            sysbus_probe_log() { echo "gnome-launch: system-bus probe=${'$'}1 detail=${'$'}2"; }
            start_system_bus() {
              rm -f "${'$'}DBUS_RUN_DIR/system_bus_socket" "${'$'}DBUS_RUN_DIR/pid"
              BO=${'$'}(dbus-daemon --system --fork 2>&1); BRC=${'$'}?
              echo "gnome-launch: system-bus start rc=${'$'}BRC output=${'$'}{BO:-none}"
              if [ "${'$'}BRC" -ne 0 ]; then
                SO=${'$'}(service dbus start 2>&1); SRC=${'$'}?
                echo "gnome-launch: system-bus service fallback rc=${'$'}SRC output=${'$'}{SO:-none}"
              fi
              sleep 1
              R=${'$'}(sysbus_probe); P=${'$'}{R%%|*}; sysbus_probe_log "${'$'}P" "${'$'}{R#*|}"
              if [ "${'$'}P" = LIVE ]; then
                echo "gnome-launch: system-bus LIVE after start pid=${'$'}(cat "${'$'}DBUS_RUN_DIR/pid" 2>/dev/null | tr -d '[:space:]')"
              else
                echo "gnome-launch: system-bus UNAVAILABLE after start attempt (probe=${'$'}P) - systemd-gated components (gnome-session gsm-systemd, shell LoginManagerSystemd) expected to fail this round; continuing for diagnostics"
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
            R=${'$'}(sysbus_probe); P=${'$'}{R%%|*}; sysbus_probe_log "${'$'}P" "${'$'}{R#*|}"
            case "${'$'}P" in
              LIVE)
                echo "gnome-launch: system-bus LIVE - preserving existing daemon (socket/pidfile untouched)" ;;
              REFUSED)
                if [ -n "${'$'}BP" ] && [ -n "${'$'}BCOMM" ]; then
                  case "${'$'}BCOMM" in
                    dbus-daemon*)
                      # Socket has NO listener yet the pidfile's daemon is ALIVE:
                      # wedged or mid-restart daemon - never unlink a live daemon's
                      # endpoint. One bounded retry, then FAIL CLOSED.
                      sleep 2
                      R=${'$'}(sysbus_probe); P=${'$'}{R%%|*}; sysbus_probe_log "${'$'}P" "${'$'}{R#*|}"
                      if [ "${'$'}P" = LIVE ]; then
                        echo "gnome-launch: system-bus LIVE after retry - preserved pid=${'$'}BP"
                      else
                        echo "gnome-launch: system-bus daemon pid=${'$'}BP alive but socket not listening after retry (probe=${'$'}P) - FAIL CLOSED: endpoint preserved (no unlink/restart); systemd-gated components expected to fail this round"
                      fi ;;
                    *)
                      # Pidfile names a live UNRELATED process (stale, pid reused)
                      # and the socket has no listener: endpoint grounded dead.
                      # The referenced process itself is NOT touched.
                      echo "gnome-launch: system-bus pidfile stale (pid=${'$'}BP comm=${'$'}BCOMM, not a daemon) and socket not listening (ECONNREFUSED) - clearing grounded-dead endpoint and starting a fresh daemon"
                      start_system_bus ;;
                  esac
                else
                  echo "gnome-launch: system-bus socket not listening (ECONNREFUSED), no live pidfile daemon - clearing grounded-dead endpoint and starting a fresh daemon"
                  start_system_bus
                fi ;;
              NOENT)
                if [ -n "${'$'}BP" ] && [ -n "${'$'}BCOMM" ]; then
                  # A live daemon but no socket file: do not race it - it may be
                  # (re)creating its endpoint. FAIL CLOSED.
                  echo "gnome-launch: system-bus socket missing (ENOENT) but pidfile daemon pid=${'$'}BP alive - FAIL CLOSED: not racing a live daemon (no new daemon started)"
                else
                  echo "gnome-launch: system-bus endpoint absent (ENOENT), no live pidfile daemon - starting a fresh daemon"
                  start_system_bus
                fi ;;
              *)
                # Timeout/auth/handshake/unknown: an endpoint may be LISTENING but
                # unresponsive. State UNKNOWN - never unlink on unknown. One bounded
                # retry; if it answers, preserve; otherwise preserve and fail closed.
                # (Do not call this round bus-less: the bus state is UNKNOWN.)
                sleep 2
                R=${'$'}(sysbus_probe); P=${'$'}{R%%|*}; sysbus_probe_log "${'$'}P" "${'$'}{R#*|}"
                if [ "${'$'}P" = LIVE ]; then
                  echo "gnome-launch: system-bus LIVE after retry (initial probe was unresponsive) - preserved"
                else
                  echo "gnome-launch: system-bus state UNKNOWN after retry (probe=${'$'}P - an unresponsive endpoint may be listening) - FAIL CLOSED: endpoint preserved (no unlink/restart); systemd-gated components may fail this round"
                fi ;;
            esac
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
            BUS_NONCE=$(cat /proc/sys/kernel/random/uuid 2>/dev/null)
            echo "gnome-launch: session bus ${'$'}DBUS_SESSION_BUS_ADDRESS nonce=${'$'}BUS_NONCE"
            echo ${'$'}DBUS_SESSION_BUS_PID > /root/.gnome-bus.pid 2>/dev/null
            # Creation-time identity record of THIS launch's bus, written
            # by the launcher that spawned it: pid + start ticks (stat
            # field 22, parsed after the last ')' so any comm shape is
            # safe) + uid +
            # per-launch nonce (also logged above, so the caller binds the
            # record to ITS OWN launch's log line, never to whatever the
            # latest tail shows). The app adopts a bus identity ONLY by
            # validating the live process against this record; a pid
            # reused after the real bus died (even by another same-uid
            # dbus-daemon, even moments later) fails the exact tick
            # binding and is preserved, never killed.
            { printf 'pid=%s startticks=%s uid=%s nonce=%s\n' \
                "${'$'}DBUS_SESSION_BUS_PID" \
                "$(sed 's/^.*) //' /proc/${'$'}DBUS_SESSION_BUS_PID/stat 2>/dev/null | awk '{print ${'$'}20}')" \
                "$(stat -c %u /proc/${'$'}DBUS_SESSION_BUS_PID 2>/dev/null)" \
                "${'$'}BUS_NONCE" \
                > /root/.gnome-bus.ident.tmp \
              && mv /root/.gnome-bus.ident.tmp /root/.gnome-bus.ident; } 2>/dev/null
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
              for t in gdbus xwininfo xdpyinfo xrandr glxinfo pgrep pidof xprop timeout; do
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
                if [ "${'$'}i" = "1" ]; then
                  # X-level capability evidence (2026-11-01 renderer question):
                  # which DRI/GLX extensions the vendored X server actually
                  # offers - bounds what any guest GL stack can use.
                  echo "x_extensions_begin"
                  timeout 5 xdpyinfo -queryExtensions 2>&1 | head -40 | sed 's/^/  /'
                  echo "x_extensions_end"
                fi
                SP=${'$'}(pgrep -x gnome-shell | head -1)
                echo "shell_pid=${'$'}{SP:-none}"
                # CPU/memory pressure evidence (2026-11-01 lag diagnosis):
                # cumulative shell jiffies let sample-to-sample deltas show
                # compositor CPU burn; loadavg + MemAvailable ground system
                # contention without any package install.
                echo "loadavg=${'$'}(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
                echo "mem_available_kb=${'$'}(awk '/MemAvailable/{print ${'$'}2}' /proc/meminfo 2>/dev/null)"
                if [ -n "${'$'}SP" ]; then
                  echo "shell_cpu_jiffies=${'$'}(awk '{print ${'$'}14+${'$'}15}' /proc/${'$'}SP/stat 2>/dev/null) (cumulative utime+stime)"
                fi
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
                # X calls have NO internal timeout - bound each with external
                # `timeout` (3s) or a hung X server wedges the sampler (phone
                # evidence 2026-10-31: probes file froze after sample 1).
                # Guard the parse: an absent property yields error text, not
                # a "# id" list - never parse error text as window ids.
                CL=${'$'}(timeout 3 xprop -root _NET_CLIENT_LIST 2>/dev/null)
                case "${'$'}CL" in
                  *" # "*)
                    for id in ${'$'}(echo "${'$'}CL" | sed 's/.*# //' | tr ',' ' '); do
                      N=${'$'}((N + 1)); [ "${'$'}N" -gt 12 ] && break
                      INFO=${'$'}(timeout 3 xwininfo -id "${'$'}id" 2>/dev/null | awk '/Map State:/{ms=${'$'}3} /Width:/{w=${'$'}2} /Height:/{h=${'$'}2} END{printf "map=%s geo=%sx%s", ms, w, h}')
                      case "${'$'}INFO" in *IsViewable*) V=${'$'}((V + 1)) ;; esac
                      echo "win ${'$'}id ${'$'}INFO class=${'$'}(timeout 3 xprop -id "${'$'}id" WM_CLASS 2>/dev/null | sed 's/.*= //')"
                    done ;;
                  *) echo "win-list probe unavailable/empty: ${'$'}(echo "${'$'}CL" | head -1 | cut -c1-80)" ;;
                esac
                echo "client_windows=${'$'}N viewable=${'$'}V (sample-time map state of managed clients; titles excluded at collection)"
                if [ "${'$'}i" = "3" ] || [ "${'$'}i" = "8" ]; then
                  if [ -n "${'$'}SP" ]; then
                    echo "shell_gl_modules_begin pid=${'$'}SP (loaded modules; evidence, not active-renderer proof)"
                    grep -oE '/[^ ]*\.so[0-9.]*' "/proc/${'$'}SP/maps" 2>/dev/null | grep -Ei 'gallium|_dri\.so|dri|libGL|libEGL|llvm|virgl|zink|swrast|freedreno|vulkan' | sort -u | head -24 | sed 's/^/  /'
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
              # Renderer probe (bounded, non-blocking, opt-in install):
              # glxinfo -B reports THIS PROBE CLIENT's GLX context vendor/
              # device/renderer - it is NOT proof of mutter's active EGL
              # renderer and cannot alone settle virgl vs llvmpipe for the
              # compositor; correlate with mutter-side evidence before any
              # active-renderer claim. Existing binary preferred; mesa-utils
              # install only when LDOA_GLX_PROBE_INSTALL=1, time-boxed so a
              # slow network can never delay the session. Accurate lifetime:
              # this subshell runs the ~65s viewable-window poll FIRST, then
              # this probe - worst case ~65s poll + <=60s install + <=30s
              # probe + ~12s roster/mode/extension reads ~= 170s. There is no
              # outer deadline; the bound comes from every long child being
              # individually time-boxed, except xwininfo/xdpyinfo in the poll
              # which carry no timeout (X-server responsiveness dependent). Asynchronous to the session launch
              # throughout, so launch is never blocked.
              if ! command -v glxinfo >/dev/null 2>&1 && [ "${'$'}LDOA_GLX_PROBE_INSTALL" = 1 ]; then
                timeout 60 apt-get install -y mesa-utils >/dev/null 2>&1
              fi
              if command -v glxinfo >/dev/null 2>&1; then
                echo "gnome-launch: renderer-probe-glx BEGIN launch=${'$'}LAUNCH_ID (probe-client GLX context, not mutter EGL - correlate before renderer claim)"
                timeout 30 glxinfo -B 2>&1 | grep -E "Accelerated|Device:|OpenGL renderer string|OpenGL version string" | head -6 | while read -r L; do echo "gnome-launch: renderer-probe-glx launch=${'$'}LAUNCH_ID ${'$'}L"; done
                echo "gnome-launch: renderer-probe-glx END launch=${'$'}LAUNCH_ID"
              else
                echo "gnome-launch: renderer-probe-glx SKIPPED launch=${'$'}LAUNCH_ID (no glxinfo; opt-in install via LDOA_GLX_PROBE_INSTALL=1) - renderer identity unverified this launch"
              fi
              # Component-roster evidence (read-only, bounded): which gsd
              # plugins actually run after the gates - harvest checks
              # gsd-rfkill/gsd-usb-protection absent and the rest healthy.
              # Shell Mode + enabled-extensions tie the runtime back to the
              # unchanged identity inputs (ubuntu:GNOME, ubuntu-dock).
              echo "gnome-launch: gsd-roster launch=${'$'}LAUNCH_ID ${'$'}(ps -A -o comm= 2>/dev/null | grep '^gsd-' | sort | tr '\n' ' ')"
              echo "gnome-launch: shell-mode launch=${'$'}LAUNCH_ID ${'$'}(timeout 4 gdbus call --session --timeout 2 --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Properties.Get org.gnome.Shell Mode 2>&1 | head -1)"
              echo "gnome-launch: shell-extensions launch=${'$'}LAUNCH_ID ${'$'}(timeout 4 gsettings get org.gnome.shell enabled-extensions 2>&1 | head -1)"
            ) &
            # Diag (exit=1 root-cause): gnome-session's g_debug output names the
            # phase/app transitions and the acceleration-check verdicts; the
            # captured stderr otherwise ends at the IceLockAuthFile warning with
            # none of the source's exit=1 signatures (fail-whale paths are
            # silent when zenity cannot run under proot).
            export GNOME_SESSION_DEBUG=1
            # ICEAUTHORITY stays relocated to /tmp/xdg (tmpfs; honored as the
            # full authority path by IceAuthFileName). The earlier link() probe
            # here is REMOVED: it probed the wrong call. The grounded failing
            # call is creat(2); handled by the shim+probe block below.
            echo "gnome-launch: ice-pkg $(dpkg-query -W -f='${'$'}{Package}=${'$'}{Version} ' libice6 gnome-session 2>&1)"
            export ICEAUTHORITY=/tmp/xdg/ICEauthority
            # --- LDOA creat->openat shim + ICE dance probe (x86_64 CI DIAGNOSTIC ONLY;
            # gated on uname below - no ARM/product effect). Grounded chain (binary +
            # source verified, 2026-11-01): glibc 2.39 x86_64 creat() is a raw legacy
            # syscall (NR 85, disassembly of Noble libc6_2.39-0ubuntu8.9_amd64); the
            # Android-app seccomp profile blocks the legacy path-syscall class;
            # vendored proot 5.1.107.101 (Termux) rewrites PR_link->linkat but has NO
            # PR_creat case (src/tracee/seccomp.c) -> -ENOSYS; libICE 1.0.10
            # IceLockAuthFile (src/authutil.c) creat fails -> update_iceauthority
            # FALSE (gsm-xsmp-server.c:655) -> gsm_util_init_error fatal -> exit(1).
            # ARM aarch64 libc creat tail-calls openat (Noble arm64 libc6 verified),
            # so ARM is unaffected for THIS call path only (no broader ARM ICE-lock or
            # emulator-fidelity claim). Unresolved micro-link: an observed -1/ENOSYS
            # does NOT distinguish RET_ERRNO from SIGSYS->proot-default delivery.
            # Lock files (-c/-l) are TRANSIENT (IceUnlockAuthFile unlinks them at the
            # end of update_iceauthority, gsm-xsmp-server.c:547), so positive runtime
            # evidence = constructor line + SESSION_MANAGER milestone + phase
            # progression, never lock-file presence during the session.
            GS_PRELOAD=""
            GS_PRELOAD_BRANCH="skipped-arch(not x86_64)"
            if [ "${'$'}(uname -m)" = "x86_64" ]; then
              D=/tmp/ldoa-diag
              mkdir -p "${'$'}D"
              LDOA_SHIM_B64='
            f0VMRgIBAQAAAAAAAAAAAAMAPgABAAAAAAAAAAAAAABAAAAAAAAAAPA1AAAAAAAAAAAAAEAAOAAL
            AEAAHgAdAAEAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAuAUAAAAAAAC4BQAAAAAAAAAQ
            AAAAAAAAAQAAAAUAAAAAEAAAAAAAAAAQAAAAAAAAABAAAAAAAACpAQAAAAAAAKkBAAAAAAAAABAA
            AAAAAAABAAAABAAAAAAgAAAAAAAAACAAAAAAAAAAIAAAAAAAACABAAAAAAAAIAEAAAAAAAAAEAAA
            AAAAAAEAAAAGAAAACC4AAAAAAAAIPgAAAAAAAAg+AAAAAAAAKAIAAAAAAAAwAgAAAAAAAAAQAAAA
            AAAAAgAAAAYAAAAgLgAAAAAAACA+AAAAAAAAID4AAAAAAADAAQAAAAAAAMABAAAAAAAACAAAAAAA
            AAAEAAAABAAAAKgCAAAAAAAAqAIAAAAAAACoAgAAAAAAACAAAAAAAAAAIAAAAAAAAAAIAAAAAAAA
            AAQAAAAEAAAAyAIAAAAAAADIAgAAAAAAAMgCAAAAAAAAJAAAAAAAAAAkAAAAAAAAAAQAAAAAAAAA
            U+V0ZAQAAACoAgAAAAAAAKgCAAAAAAAAqAIAAAAAAAAgAAAAAAAAACAAAAAAAAAACAAAAAAAAABQ
            5XRkBAAAADAgAAAAAAAAMCAAAAAAAAAwIAAAAAAAADwAAAAAAAAAPAAAAAAAAAAEAAAAAAAAAFHl
            dGQGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAUuV0
            ZAQAAAAILgAAAAAAAAg+AAAAAAAACD4AAAAAAAD4AQAAAAAAAPgBAAAAAAAAAQAAAAAAAAAEAAAA
            EAAAAAUAAABHTlUAAgAAwAQAAAADAAAAAAAAAAQAAAAUAAAAAwAAAEdOVQCciGrIJe2G/UqLo23C
            t0mGSLFARAAAAAACAAAABwAAAAEAAAAGAAAAAIAQgAAAAEAHAAAAAAAAANS3Pg//A8zZAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAEAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAVQAAABIAAAAAAAAAAAAA
            AAAAAAAAAAAAYQAAABIAAAAAAAAAAAAAAAAAAAAAAAAAAQAAACAAAAAAAAAAAAAAAAAAAAAAAAAA
            LAAAACAAAAAAAAAAAAAAAAAAAAAAAAAARgAAACIAAAAAAAAAAAAAAAAAAAAAAAAAWwAAABIADgBg
            EQAAAAAAABoAAAAAAAAAaAAAABIADgCAEQAAAAAAABoAAAAAAAAAAF9fZ21vbl9zdGFydF9fAF9J
            VE1fZGVyZWdpc3RlclRNQ2xvbmVUYWJsZQBfSVRNX3JlZ2lzdGVyVE1DbG9uZVRhYmxlAF9fY3hh
            X2ZpbmFsaXplAHdyaXRlAGNyZWF0AG9wZW5hdABjcmVhdDY0AGxpYmMuc28uNgBHTElCQ18yLjQA
            R0xJQkNfMi4yLjUAAAABAAIAAwABAAEAAgABAAEAAAAAAAAAAQACAHAAAAAQAAAAAAAAABRpaQ0A
            AAMAegAAABAAAAB1GmkJAAACAIQAAAAAAAAACD4AAAAAAAAIAAAAAAAAAFARAAAAAAAAED4AAAAA
            AAAIAAAAAAAAAIAQAAAAAAAAGD4AAAAAAAAIAAAAAAAAABARAAAAAAAAKEAAAAAAAAAIAAAAAAAA
            AChAAAAAAAAA4D8AAAAAAAAGAAAAAQAAAAAAAAAAAAAA6D8AAAAAAAAGAAAABAAAAAAAAAAAAAAA
            8D8AAAAAAAAGAAAABQAAAAAAAAAAAAAA+D8AAAAAAAAGAAAABgAAAAAAAAAAAAAAGEAAAAAAAAAH
            AAAAAgAAAAAAAAAAAAAAIEAAAAAAAAAHAAAAAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPMPHvpIg+wI
            SIsF2S8AAEiFwHQC/9BIg8QIwwAAAAAA/zXiLwAA8v8l4y8AAA8fAPMPHvpoAAAAAPLp4f///5Dz
            Dx76aAEAAADy6dH///+Q8w8e+vL/JZ0vAAAPH0QAAPMPHvry/yWtLwAADx9EAADzDx768v8lpS8A
            AA8fRAAA8w8e+rovAAAASI01cA8AAL8CAAAA6cb///9mDx9EAABIjT2JLwAASI0Fgi8AAEg5+HQV
            SIsFJi8AAEiFwHQJ/+APH4AAAAAAww8fgAAAAABIjT1ZLwAASI01Ui8AAEgp/kiJ8EjB7j9IwfgD
            SAHGSNH+dBRIiwX1LgAASIXAdAj/4GYPH0QAAMMPH4AAAAAA8w8e+oA9FS8AAAB1K1VIgz3SLgAA
            AEiJ5XQMSIs99i4AAOgZ////6GT////GBe0uAAABXcMPHwDDDx+AAAAAAPMPHvrpd////w8fgAAA
            AADzDx76ifG6QQIAAEiJ/jHAv5z////p9v7//2YPH0QAAPMPHvqJ8bpBAgAASIn+McC/nP///+nW
            /v//AADzDx76SIPsCEiDxAjDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABsZG9hLWNyZWF0LXNoaW06
            IGxvYWRlZCAoeDg2XzY0IENJIGRpYWdub3N0aWMpCgABGwM7PAAAAAYAAADw7///WAAAACDw//+A
            AAAAMPD//5gAAABQ8P//sAAAADDx///EAAAAUPH//9gAAAAAAAAAFAAAAAAAAAABelIAAXgQARsM
            BwiQAQAAJAAAABwAAACQ7///MAAAAAAOEEYOGEoPC3cIgAA/GjoqMyQiAAAAABQAAABEAAAAmO//
            /xAAAAAAAAAAAAAAABQAAABcAAAAkO///yAAAAAAAAAAAAAAABAAAAB0AAAAmO///xoAAAAAAAAA
            EAAAAIgAAABk8P//GgAAAAAAAAAQAAAAnAAAAHDw//8aAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUBEAAAAAAACAEAAAAAAA
            ABARAAAAAAAAAQAAAAAAAABwAAAAAAAAAAwAAAAAAAAAABAAAAAAAAANAAAAAAAAAJwRAAAAAAAA
            GQAAAAAAAAAIPgAAAAAAABsAAAAAAAAAEAAAAAAAAAAaAAAAAAAAABg+AAAAAAAAHAAAAAAAAAAI
            AAAAAAAAAPX+/28AAAAA8AIAAAAAAAAFAAAAAAAAAPADAAAAAAAABgAAAAAAAAAYAwAAAAAAAAoA
            AAAAAAAAkAAAAAAAAAALAAAAAAAAABgAAAAAAAAAAwAAAAAAAAAAQAAAAAAAAAIAAAAAAAAAMAAA
            AAAAAAAUAAAAAAAAAAcAAAAAAAAAFwAAAAAAAACIBQAAAAAAAAcAAAAAAAAAyAQAAAAAAAAIAAAA
            AAAAAMAAAAAAAAAACQAAAAAAAAAYAAAAAAAAAP7//28AAAAAmAQAAAAAAAD///9vAAAAAAEAAAAA
            AAAA8P//bwAAAACABAAAAAAAAPn//28AAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAID4AAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            MBAAAAAAAABAEAAAAAAAAChAAAAAAAAAR0NDOiAoVWJ1bnR1IDExLjQuMC0xdWJ1bnR1MX4yMi4w
            NC4zKSAxMS40LjAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAQA8f8AAAAAAAAAAAAA
            AAAAAAAAEwAAAAIADgCAEAAAAAAAABoAAAAAAAAAKAAAAAEAEAAAIAAAAAAAADAAAAAAAAAALgAA
            AAQA8f8AAAAAAAAAAAAAAAAAAAAAOQAAAAIADgCgEAAAAAAAAAAAAAAAAAAAOwAAAAIADgDQEAAA
            AAAAAAAAAAAAAAAATgAAAAIADgAQEQAAAAAAAAAAAAAAAAAAZAAAAAEAGQAwQAAAAAAAAAEAAAAA
            AAAAcAAAAAEAFAAYPgAAAAAAAAAAAAAAAAAAlwAAAAIADgBQEQAAAAAAAAAAAAAAAAAAowAAAAEA
            EwAIPgAAAAAAAAAAAAAAAAAALgAAAAQA8f8AAAAAAAAAAAAAAAAAAAAAwgAAAAEAEgAcIQAAAAAA
            AAAAAAAAAAAAAAAAAAQA8f8AAAAAAAAAAAAAAAAAAAAA0AAAAAIADwCcEQAAAAAAAAAAAAAAAAAA
            1gAAAAEAGAAoQAAAAAAAAAAAAAAAAAAA4wAAAAEAFQAgPgAAAAAAAAAAAAAAAAAA7AAAAAAAEQAw
            IAAAAAAAAAAAAAAAAAAA/wAAAAEAGAAwQAAAAAAAAAAAAAAAAAAACwEAAAEAFwAAQAAAAAAAAAAA
            AAAAAAAAIgAAAAIACgAAEAAAAAAAAAAAAAAAAAAAIQEAACAAAAAAAAAAAAAAAAAAAAAAAAAAPQEA
            ABIAAAAAAAAAAAAAAAAAAAAAAAAATwEAABIAAAAAAAAAAAAAAAAAAAAAAAAAYAEAABIADgBgEQAA
            AAAAABoAAAAAAAAAZgEAACAAAAAAAAAAAAAAAAAAAAAAAAAAdQEAABIADgCAEQAAAAAAABoAAAAA
            AAAAfQEAACAAAAAAAAAAAAAAAAAAAAAAAAAAlwEAACIAAAAAAAAAAAAAAAAAAAAAAAAAAGxkb2Et
            Y3JlYXQtc2hpbS5jAGxkb2FfY3JlYXRfc2hpbV9pbml0AG1zZy4wAGNydHN0dWZmLmMAZGVyZWdp
            c3Rlcl90bV9jbG9uZXMAX19kb19nbG9iYWxfZHRvcnNfYXV4AGNvbXBsZXRlZC4wAF9fZG9fZ2xv
            YmFsX2R0b3JzX2F1eF9maW5pX2FycmF5X2VudHJ5AGZyYW1lX2R1bW15AF9fZnJhbWVfZHVtbXlf
            aW5pdF9hcnJheV9lbnRyeQBfX0ZSQU1FX0VORF9fAF9maW5pAF9fZHNvX2hhbmRsZQBfRFlOQU1J
            QwBfX0dOVV9FSF9GUkFNRV9IRFIAX19UTUNfRU5EX18AX0dMT0JBTF9PRkZTRVRfVEFCTEVfAF9J
            VE1fZGVyZWdpc3RlclRNQ2xvbmVUYWJsZQB3cml0ZUBHTElCQ18yLjIuNQBvcGVuYXRAR0xJQkNf
            Mi40AGNyZWF0AF9fZ21vbl9zdGFydF9fAGNyZWF0NjQAX0lUTV9yZWdpc3RlclRNQ2xvbmVUYWJs
            ZQBfX2N4YV9maW5hbGl6ZUBHTElCQ18yLjIuNQAALnN5bXRhYgAuc3RydGFiAC5zaHN0cnRhYgAu
            bm90ZS5nbnUucHJvcGVydHkALm5vdGUuZ251LmJ1aWxkLWlkAC5nbnUuaGFzaAAuZHluc3ltAC5k
            eW5zdHIALmdudS52ZXJzaW9uAC5nbnUudmVyc2lvbl9yAC5yZWxhLmR5bgAucmVsYS5wbHQALmlu
            aXQALnBsdC5nb3QALnBsdC5zZWMALnRleHQALmZpbmkALnJvZGF0YQAuZWhfZnJhbWVfaGRyAC5l
            aF9mcmFtZQAuaW5pdF9hcnJheQAuZmluaV9hcnJheQAuZHluYW1pYwAuZ290LnBsdAAuZGF0YQAu
            YnNzAC5jb21tZW50AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAGwAAAAcAAAACAAAAAAAAAKgCAAAAAAAAqAIAAAAAAAAgAAAA
            AAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAC4AAAAHAAAAAgAAAAAAAADIAgAAAAAAAMgCAAAA
            AAAAJAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAABBAAAA9v//bwIAAAAAAAAA8AIAAAAA
            AADwAgAAAAAAACgAAAAAAAAABAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAASwAAAAsAAAACAAAAAAAA
            ABgDAAAAAAAAGAMAAAAAAADYAAAAAAAAAAUAAAABAAAACAAAAAAAAAAYAAAAAAAAAFMAAAADAAAA
            AgAAAAAAAADwAwAAAAAAAPADAAAAAAAAkAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAABb
            AAAA////bwIAAAAAAAAAgAQAAAAAAACABAAAAAAAABIAAAAAAAAABAAAAAAAAAACAAAAAAAAAAIA
            AAAAAAAAaAAAAP7//28CAAAAAAAAAJgEAAAAAAAAmAQAAAAAAAAwAAAAAAAAAAUAAAABAAAACAAA
            AAAAAAAAAAAAAAAAAHcAAAAEAAAAAgAAAAAAAADIBAAAAAAAAMgEAAAAAAAAwAAAAAAAAAAEAAAA
            AAAAAAgAAAAAAAAAGAAAAAAAAACBAAAABAAAAEIAAAAAAAAAiAUAAAAAAACIBQAAAAAAADAAAAAA
            AAAABAAAABcAAAAIAAAAAAAAABgAAAAAAAAAiwAAAAEAAAAGAAAAAAAAAAAQAAAAAAAAABAAAAAA
            AAAbAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAIYAAAABAAAABgAAAAAAAAAgEAAAAAAA
            ACAQAAAAAAAAMAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAACRAAAAAQAAAAYAAAAAAAAA
            UBAAAAAAAABQEAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAABAAAAAAAAAAmgAAAAEAAAAG
            AAAAAAAAAGAQAAAAAAAAYBAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAAKMA
            AAABAAAABgAAAAAAAACAEAAAAAAAAIAQAAAAAAAAGgEAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAA
            AAAAAACpAAAAAQAAAAYAAAAAAAAAnBEAAAAAAACcEQAAAAAAAA0AAAAAAAAAAAAAAAAAAAAEAAAA
            AAAAAAAAAAAAAAAArwAAAAEAAAACAAAAAAAAAAAgAAAAAAAAACAAAAAAAAAwAAAAAAAAAAAAAAAA
            AAAAIAAAAAAAAAAAAAAAAAAAALcAAAABAAAAAgAAAAAAAAAwIAAAAAAAADAgAAAAAAAAPAAAAAAA
            AAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAADFAAAAAQAAAAIAAAAAAAAAcCAAAAAAAABwIAAAAAAA
            ALAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAzwAAAA4AAAADAAAAAAAAAAg+AAAAAAAA
            CC4AAAAAAAAQAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAIAAAAAAAAANsAAAAPAAAAAwAAAAAAAAAY
            PgAAAAAAABguAAAAAAAACAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAACAAAAAAAAADnAAAABgAAAAMA
            AAAAAAAAID4AAAAAAAAgLgAAAAAAAMABAAAAAAAABQAAAAAAAAAIAAAAAAAAABAAAAAAAAAAlQAA
            AAEAAAADAAAAAAAAAOA/AAAAAAAA4C8AAAAAAAAgAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAIAAAA
            AAAAAPAAAAABAAAAAwAAAAAAAAAAQAAAAAAAAAAwAAAAAAAAKAAAAAAAAAAAAAAAAAAAAAgAAAAA
            AAAACAAAAAAAAAD5AAAAAQAAAAMAAAAAAAAAKEAAAAAAAAAoMAAAAAAAAAgAAAAAAAAAAAAAAAAA
            AAAIAAAAAAAAAAAAAAAAAAAA/wAAAAgAAAADAAAAAAAAADBAAAAAAAAAMDAAAAAAAAAIAAAAAAAA
            AAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAQBAAABAAAAMAAAAAAAAAAAAAAAAAAAADAwAAAAAAAA
            LQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAABAAAAAgAAAAAAAAAAAAAAAAAAAAAAAABg
            MAAAAAAAANACAAAAAAAAHAAAABYAAAAIAAAAAAAAABgAAAAAAAAACQAAAAMAAAAAAAAAAAAAAAAA
            AAAAAAAAMDMAAAAAAACyAQAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAABEAAAADAAAAAAAA
            AAAAAAAAAAAAAAAAAOI0AAAAAAAADQEAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAA='
              LDOA_PROBE_B64='
            f0VMRgIBAQAAAAAAAAAAAAMAPgABAAAAABUAAAAAAABAAAAAAAAAAAA5AAAAAAAAAAAAAEAAOAAN
            AEAAHwAeAAYAAAAEAAAAQAAAAAAAAABAAAAAAAAAAEAAAAAAAAAA2AIAAAAAAADYAgAAAAAAAAgA
            AAAAAAAAAwAAAAQAAAAYAwAAAAAAABgDAAAAAAAAGAMAAAAAAAAcAAAAAAAAABwAAAAAAAAAAQAA
            AAAAAAABAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHgJAAAAAAAAeAkAAAAAAAAAEAAA
            AAAAAAEAAAAFAAAAABAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAcQYAAAAAAABxBgAAAAAAAAAQAAAA
            AAAAAQAAAAQAAAAAIAAAAAAAAAAgAAAAAAAAACAAAAAAAACkAgAAAAAAAKQCAAAAAAAAABAAAAAA
            AAABAAAABgAAAFAtAAAAAAAAUD0AAAAAAABQPQAAAAAAAMACAAAAAAAAyAIAAAAAAAAAEAAAAAAA
            AAIAAAAGAAAAYC0AAAAAAABgPQAAAAAAAGA9AAAAAAAA8AEAAAAAAADwAQAAAAAAAAgAAAAAAAAA
            BAAAAAQAAAA4AwAAAAAAADgDAAAAAAAAOAMAAAAAAAAwAAAAAAAAADAAAAAAAAAACAAAAAAAAAAE
            AAAABAAAAGgDAAAAAAAAaAMAAAAAAABoAwAAAAAAAEQAAAAAAAAARAAAAAAAAAAEAAAAAAAAAFPl
            dGQEAAAAOAMAAAAAAAA4AwAAAAAAADgDAAAAAAAAMAAAAAAAAAAwAAAAAAAAAAgAAAAAAAAAUOV0
            ZAQAAABMIQAAAAAAAEwhAAAAAAAATCEAAAAAAAA8AAAAAAAAADwAAAAAAAAABAAAAAAAAABR5XRk
            BgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAFLldGQE
            AAAAUC0AAAAAAABQPQAAAAAAAFA9AAAAAAAAsAIAAAAAAACwAgAAAAAAAAEAAAAAAAAAL2xpYjY0
            L2xkLWxpbnV4LXg4Ni02NC5zby4yAAAAAAAEAAAAIAAAAAUAAABHTlUAAgAAwAQAAAADAAAAAAAA
            AAKAAMAEAAAAAQAAAAAAAAAEAAAAFAAAAAMAAABHTlUAEQ9SAWWmNzX7PgoqkUU0QVGvx10EAAAA
            EAAAAAEAAABHTlUAAAAAAAMAAAACAAAAAAAAAAAAAAACAAAAEwAAAAEAAAAGAAAAAACBAAAAAAAT
            AAAAAAAAANFlzm0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUAAAABIAAAAAAAAAAAAAAAAA
            AAAAAAAANwAAABIAAAAAAAAAAAAAAAAAAAAAAAAAfQAAABIAAAAAAAAAAAAAAAAAAAAAAAAASQAA
            ABIAAAAAAAAAAAAAAAAAAAAAAAAA4QAAACAAAAAAAAAAAAAAAAAAAAAAAAAAKwAAABIAAAAAAAAA
            AAAAAAAAAAAAAAAAbQAAABIAAAAAAAAAAAAAAAAAAAAAAAAAjgAAABIAAAAAAAAAAAAAAAAAAAAA
            AAAAXwAAABIAAAAAAAAAAAAAAAAAAAAAAAAAZQAAABIAAAAAAAAAAAAAAAAAAAAAAAAA/QAAACAA
            AAAAAAAAAAAAAAAAAAAAAAAAMgAAABIAAAAAAAAAAAAAAAAAAAAAAAAAEAAAABIAAAAAAAAAAAAA
            AAAAAAAAAAAAIwAAABIAAAAAAAAAAAAAAAAAAAAAAAAAHQAAABIAAAAAAAAAAAAAAAAAAAAAAAAA
            SwAAABIAAAAAAAAAAAAAAAAAAAAAAAAADAEAACAAAAAAAAAAAAAAAAAAAAAAAAAAdAAAABIAAAAA
            AAAAAAAAAAAAAAAAAAAAAQAAACIAAAAAAAAAAAAAAAAAAAAAAAAAAF9fY3hhX2ZpbmFsaXplAF9f
            cHJpbnRmX2NoawBjcmVhdABjcmVhdDY0AGdldHBpZABzdGF0AF9fbGliY19zdGFydF9tYWluAHVu
            bGluawBfX3NucHJpbnRmX2NoawBjbG9zZQBzeXNjYWxsAG9wZW5hdABzdHJlcnJvcgBfX2Vycm5v
            X2xvY2F0aW9uAF9fc3RhY2tfY2hrX2ZhaWwAbGliYy5zby42AEdMSUJDXzIuMzMAR0xJQkNfMi40
            AEdMSUJDXzIuMi41AEdMSUJDXzIuMzQAR0xJQkNfMi4zLjQAX0lUTV9kZXJlZ2lzdGVyVE1DbG9u
            ZVRhYmxlAF9fZ21vbl9zdGFydF9fAF9JVE1fcmVnaXN0ZXJUTUNsb25lVGFibGUAAAACAAMABAAE
            AAEABAAFAAUABAAEAAEABgACAAQABAAEAAEABAAEAAAAAQAFAJ8AAAAQAAAAAAAAALORlgYAAAYA
            qQAAABAAAAAUaWkNAAAFALQAAAAQAAAAdRppCQAABAC+AAAAEAAAALSRlgYAAAMAygAAABAAAAB0
            GWkJAAACANUAAAAAAAAAUD0AAAAAAAAIAAAAAAAAAOAVAAAAAAAAWD0AAAAAAAAIAAAAAAAAAKAV
            AAAAAAAACEAAAAAAAAAIAAAAAAAAAAhAAAAAAAAA2D8AAAAAAAAGAAAAAgAAAAAAAAAAAAAA4D8A
            AAAAAAAGAAAABQAAAAAAAAAAAAAA6D8AAAAAAAAGAAAACwAAAAAAAAAAAAAA8D8AAAAAAAAGAAAA
            EQAAAAAAAAAAAAAA+D8AAAAAAAAGAAAAEwAAAAAAAAAAAAAAaD8AAAAAAAAHAAAAAQAAAAAAAAAA
            AAAAcD8AAAAAAAAHAAAAAwAAAAAAAAAAAAAAeD8AAAAAAAAHAAAABAAAAAAAAAAAAAAAgD8AAAAA
            AAAHAAAABgAAAAAAAAAAAAAAiD8AAAAAAAAHAAAABwAAAAAAAAAAAAAAkD8AAAAAAAAHAAAACAAA
            AAAAAAAAAAAAmD8AAAAAAAAHAAAACQAAAAAAAAAAAAAAoD8AAAAAAAAHAAAACgAAAAAAAAAAAAAA
            qD8AAAAAAAAHAAAADAAAAAAAAAAAAAAAsD8AAAAAAAAHAAAADQAAAAAAAAAAAAAAuD8AAAAAAAAH
            AAAADgAAAAAAAAAAAAAAwD8AAAAAAAAHAAAADwAAAAAAAAAAAAAAyD8AAAAAAAAHAAAAEAAAAAAA
            AAAAAAAA0D8AAAAAAAAHAAAAEgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPMPHvpIg+wI
            SIsF2S8AAEiFwHQC/9BIg8QIwwAAAAAA/zUyLwAA8v8lMy8AAA8fAPMPHvpoAAAAAPLp4f///5Dz
            Dx76aAEAAADy6dH///+Q8w8e+mgCAAAA8unB////kPMPHvpoAwAAAPLpsf///5DzDx76aAQAAADy
            6aH///+Q8w8e+mgFAAAA8umR////kPMPHvpoBgAAAPLpgf///5DzDx76aAcAAADy6XH///+Q8w8e
            +mgIAAAA8ulh////kPMPHvpoCQAAAPLpUf///5DzDx76aAoAAADy6UH///+Q8w8e+mgLAAAA8ukx
            ////kPMPHvpoDAAAAPLpIf///5DzDx76aA0AAADy6RH///+Q8w8e+vL/Jd0uAAAPH0QAAPMPHvry
            /yU9LgAADx9EAADzDx768v8lNS4AAA8fRAAA8w8e+vL/JS0uAAAPH0QAAPMPHvry/yUlLgAADx9E
            AADzDx768v8lHS4AAA8fRAAA8w8e+vL/JRUuAAAPH0QAAPMPHvry/yUNLgAADx9EAADzDx768v8l
            BS4AAA8fRAAA8w8e+vL/Jf0tAAAPH0QAAPMPHvry/yX1LQAADx9EAADzDx768v8l7S0AAA8fRAAA
            8w8e+vL/JeUtAAAPH0QAAPMPHvry/yXdLQAADx9EAADzDx768v8l1S0AAA8fRAAA8w8e+kFXQVZB
            VUFUVVNIgewYBQAATI2kJKAAAABIjawkoAEAAGRIiwQlKAAAAEiJhCQIBQAAMcBMjbQkwAIAAEyN
            rCTgAwAA6AP///9Miee5AAEAAEyNBecNAACJw0GJwboBAAAAvgABAAAxwOiu/v//TYnhuSABAABI
            ie9MjQXcDQAAugEAAAC+IAEAADHA6Iv+//9NieG5IAEAAEyJ90yNBb4NAAC6AQAAAL4gAQAAMcDo
            aP7//0GJ2bkgAQAATInvTI0FoA0AALoBAAAAviABAAAxwOhF/v//6FD+//+6tgEAAEiJ7r9VAAAA
            xwAAAAAASInDMcDok/7//0iNNYINAABIjT2ODQAASYnESInC6NoCAABJg/z/dBBEiefoXP7//0iJ
            7+gU/v//xwMAAAAAvrYBAABIie/okf7//0iNNV0NAABIjT1eDQAATGP4TIn66JgCAABJg///dBBE
            if/oGv7//0iJ7+jS/f//xwMAAAAAvrYBAABIie/oP/7//0iNNS4NAABIjT0xDQAATGP4TIn66FYC
            AABJg///dBBEif/o2P3//0iJ7+iQ/f//xwMAAAAAukECAABMie4xwL+c////uYABAADokf3//0iN
            NfcMAABIjT35DAAATGP4TIn66AgCAABJg///dAhEif/oiv3//8cDAAAAAEiNdCQQSInvTI094wwA
            AOiQ/f//SI01zgwAAEyJ/0hj0OjOAQAAxwMAAAAAvrYBAABIie/om/3//0iNNbgMAABMif9BicRI
            mEiJwkiJRCQI6J8BAABIi0QkCEiD+P91WUiNFcQLAABIjTWlDAAAvwEAAAAxwOg6/f//SInv6ML8
            //9Miffouvz//0yJ7+iy/P//SIuEJAgFAABkSCsEJSgAAAB1WEiBxBgFAAAxwFtdQVxBXUFeQV/D
            RInn6MP8///HAwAAAABMifZIie/oEv3//0iNNS4MAABMif9IY9hIidroDQEAAEiDwwEPhG////9I
            jRUVCwAA6Wr////ocvz//2aQ8w8e+jHtSYnRXkiJ4kiD5PBQVEUxwDHJSI094fz///8VsyoAAPRm
            Lg8fhAAAAAAASI092SoAAEiNBdIqAABIOfh0FUiLBZYqAABIhcB0Cf/gDx+AAAAAAMMPH4AAAAAA
            SI09qSoAAEiNNaIqAABIKf5IifBIwe4/SMH4A0gBxkjR/nQUSIsFZSoAAEiFwHQI/+BmDx9EAADD
            Dx+AAAAAAPMPHvqAPWUqAAAAdStVSIM9QioAAABIieV0DEiLPUYqAADoSfv//+hk////xgU9KgAA
            AV3DDx8Aww8fgAAAAADzDx766Xf///8PH4AAAAAAQVVFMclJifVBVEmJ/FVIjS3/CQAAU0iJ00iD
            7BhIg/r/dCxIg+wISYnYTInpTIniVUiNNQALAAC/AQAAADHA6Hz7//9Ig8QoW11BXEFdw+js+v//
            RIsIRYXJdMdEic9EiUwkDOiX+///RItMJAxIicXrsADzDx76SIPsCEiDxAjDAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAIAbm9uZQBJY2VBdXRo
            TG9ja1N1Y2Nlc3MtZXF1aXZhbGVudABJY2VBdXRoTG9ja0Vycm9yLWVxdWl2YWxlbnQAL3RtcC9s
            ZG9hLWRpYWcvcHJvYmUtJWQtYmFzZQAlcy1jACVzLWwAL3RtcC9sZG9hLWRpYWcvcHJvYmUtJWQt
            Y3RsAHN5c2NhbGwoU1lTX2NyZWF0KQByYXdfY3JlYXQAY3JlYXQoKQBsaWJjX2NyZWF0AGNyZWF0
            NjQoKQBsaWJjX2NyZWF0NjQAb3BlbmF0KCkAb3BlbmF0X2NvbnRyb2wAc3RhdCgtYykAZGFuY2UA
            Y3JlYXQoLWMsMDY2NikAbGluaygtYywtbCkAYXJtPWRhbmNlIG91dGNvbWU9JXMKAAAAAGFybT0l
            cyBjYWxsPSVzIHJjPSVsZCBlcnJubz0lZCglcykKAAEbAzs4AAAABgAAANTu//9sAAAAxO///5QA
            AADU7///rAAAALTw//8EAQAAtPP//1QAAACk9P//xAAAABQAAAAAAAAAAXpSAAF4EAEbDAcIkAEA
            ABQAAAAcAAAAWPP//yYAAAAARAcQAAAAACQAAAA0AAAAYO7///AAAAAADhBGDhhKDwt3CIAAPxo6
            KjMkIgAAAAAUAAAAXAAAACjv//8QAAAAAAAAAAAAAAAUAAAAdAAAACDv///gAAAAAAAAAAAAAAA8
            AAAAjAAAANjz//9zAAAAAEIOEI0CSA4YjANEDiCGBEgOKIMFRw5ASg5ISg5QVw4oQQ4gQQ4YQg4Q
            Qg4IQQ5ATAAAAMwAAACo7////gIAAABGDhCPAkIOGI4DQg4gjQRCDiiMBUEOMIYGQQ44gwdHDtAK
            A5MCCg44Qw4wQQ4oQg4gQg4YQg4QQg4IQQsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADgFQAAAAAAAKAVAAAAAAAAAQAAAAAAAACfAAAA
            AAAAAAwAAAAAAAAAABAAAAAAAAANAAAAAAAAAGQWAAAAAAAAGQAAAAAAAABQPQAAAAAAABsAAAAA
            AAAACAAAAAAAAAAaAAAAAAAAAFg9AAAAAAAAHAAAAAAAAAAIAAAAAAAAAPX+/28AAAAAsAMAAAAA
            AAAFAAAAAAAAALgFAAAAAAAABgAAAAAAAADYAwAAAAAAAAoAAAAAAAAAJgEAAAAAAAALAAAAAAAA
            ABgAAAAAAAAAFQAAAAAAAAAAAAAAAAAAAAMAAAAAAAAAUD8AAAAAAAACAAAAAAAAAFABAAAAAAAA
            FAAAAAAAAAAHAAAAAAAAABcAAAAAAAAAKAgAAAAAAAAHAAAAAAAAAGgHAAAAAAAACAAAAAAAAADA
            AAAAAAAAAAkAAAAAAAAAGAAAAAAAAAAeAAAAAAAAAAgAAAAAAAAA+///bwAAAAABAAAIAAAAAP7/
            /28AAAAACAcAAAAAAAD///9vAAAAAAEAAAAAAAAA8P//bwAAAADeBgAAAAAAAPn//28AAAAAAwAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGA9AAAAAAAAAAAAAAAAAAAAAAAAAAAAADAQAAAA
            AAAAQBAAAAAAAABQEAAAAAAAAGAQAAAAAAAAcBAAAAAAAACAEAAAAAAAAJAQAAAAAAAAoBAAAAAA
            AACwEAAAAAAAAMAQAAAAAAAA0BAAAAAAAADgEAAAAAAAAPAQAAAAAAAAABEAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIQAAAAAAAAEdDQzogKFVi
            dW50dSAxMS40LjAtMXVidW50dTF+MjIuMDQuMykgMTEuNC4wAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAEAAAAEAPH/AAAAAAAAAAAAAAAAAAAAAAkAAAABAAQAjAMAAAAAAAAgAAAAAAAAABMA
            AAAEAPH/AAAAAAAAAAAAAAAAAAAAACUAAAACABAA8BUAAAAAAABzAAAAAAAAACwAAAAEAPH/AAAA
            AAAAAAAAAAAAAAAAADcAAAACABAAMBUAAAAAAAAAAAAAAAAAADkAAAACABAAYBUAAAAAAAAAAAAA
            AAAAAEwAAAACABAAoBUAAAAAAAAAAAAAAAAAAGIAAAABABoAEEAAAAAAAAABAAAAAAAAAG4AAAAB
            ABYAWD0AAAAAAAAAAAAAAAAAAJUAAAACABAA4BUAAAAAAAAAAAAAAAAAAKEAAAABABUAUD0AAAAA
            AAAAAAAAAAAAACwAAAAEAPH/AAAAAAAAAAAAAAAAAAAAAMAAAAABABQAoCIAAAAAAAAAAAAAAAAA
            AAAAAAAEAPH/AAAAAAAAAAAAAAAAAAAAAM4AAAABABcAYD0AAAAAAAAAAAAAAAAAANcAAAAAABMA
            TCEAAAAAAAAAAAAAAAAAAOoAAAABABgAUD8AAAAAAAAAAAAAAAAAAAABAAASAAAAAAAAAAAAAAAA
            AAAAAAAAABsBAAASAAAAAAAAAAAAAAAAAAAAAAAAADgBAAASAAAAAAAAAAAAAAAAAAAAAAAAAFUB
            AAASAAAAAAAAAAAAAAAAAAAAAAAAAGgBAAAgAAAAAAAAAAAAAAAAAAAAAAAAAOQBAAAgABkAAEAA
            AAAAAAAAAAAAAAAAAIQBAAASAAAAAAAAAAAAAAAAAAAAAAAAAJcBAAAQABkAEEAAAAAAAAAAAAAA
            AAAAAJ4BAAASAhEAZBYAAAAAAAAAAAAAAAAAAKQBAAASAAAAAAAAAAAAAAAAAAAAAAAAALUBAAAS
            AAAAAAAAAAAAAAAAAAAAAAAAANABAAASAAAAAAAAAAAAAAAAAAAAAAAAAOIBAAAQABkAAEAAAAAA
            AAAAAAAAAAAAAO8BAAASAAAAAAAAAAAAAAAAAAAAAAAAAAMCAAAgAAAAAAAAAAAAAAAAAAAAAAAA
            ABICAAASAAAAAAAAAAAAAAAAAAAAAAAAACICAAARAhkACEAAAAAAAAAAAAAAAAAAAC8CAAARABIA
            ACAAAAAAAAAEAAAAAAAAAD4CAAAQABoAGEAAAAAAAAAAAAAAAAAAAOgBAAASABAAABUAAAAAAAAm
            AAAAAAAAAEMCAAAQABoAEEAAAAAAAAAAAAAAAAAAAE8CAAASABAAABIAAAAAAAD+AgAAAAAAAFQC
            AAASAAAAAAAAAAAAAAAAAAAAAAAAAG0CAAASAAAAAAAAAAAAAAAAAAAAAAAAAIECAAASAAAAAAAA
            AAAAAAAAAAAAAAAAAFcBAAASAAAAAAAAAAAAAAAAAAAAAAAAAJMCAAARAhkAEEAAAAAAAAAAAAAA
            AAAAAJ8CAAAgAAAAAAAAAAAAAAAAAAAAAAAAALkCAAASAAAAAAAAAAAAAAAAAAAAAAAAAM4CAAAi
            AAAAAAAAAAAAAAAAAAAAAAAAAOkCAAASAgwAABAAAAAAAAAAAAAAAAAAAABTY3J0MS5vAF9fYWJp
            X3RhZwBpY2UtZGFuY2UtcHJvYmUuYwByZXBvcnQAY3J0c3R1ZmYuYwBkZXJlZ2lzdGVyX3RtX2Ns
            b25lcwBfX2RvX2dsb2JhbF9kdG9yc19hdXgAY29tcGxldGVkLjAAX19kb19nbG9iYWxfZHRvcnNf
            YXV4X2ZpbmlfYXJyYXlfZW50cnkAZnJhbWVfZHVtbXkAX19mcmFtZV9kdW1teV9pbml0X2FycmF5
            X2VudHJ5AF9fRlJBTUVfRU5EX18AX0RZTkFNSUMAX19HTlVfRUhfRlJBTUVfSERSAF9HTE9CQUxf
            T0ZGU0VUX1RBQkxFXwBfX3NucHJpbnRmX2Noa0BHTElCQ18yLjMuNABfX2xpYmNfc3RhcnRfbWFp
            bkBHTElCQ18yLjM0AF9fZXJybm9fbG9jYXRpb25AR0xJQkNfMi4yLjUAdW5saW5rQEdMSUJDXzIu
            Mi41AF9JVE1fZGVyZWdpc3RlclRNQ2xvbmVUYWJsZQBnZXRwaWRAR0xJQkNfMi4yLjUAX2VkYXRh
            AF9maW5pAG9wZW5hdEBHTElCQ18yLjQAX19zdGFja19jaGtfZmFpbEBHTElCQ18yLjQAY2xvc2VA
            R0xJQkNfMi4yLjUAX19kYXRhX3N0YXJ0AHN5c2NhbGxAR0xJQkNfMi4yLjUAX19nbW9uX3N0YXJ0
            X18Ac3RhdEBHTElCQ18yLjMzAF9fZHNvX2hhbmRsZQBfSU9fc3RkaW5fdXNlZABfZW5kAF9fYnNz
            X3N0YXJ0AG1haW4AX19wcmludGZfY2hrQEdMSUJDXzIuMy40AGNyZWF0NjRAR0xJQkNfMi4yLjUA
            Y3JlYXRAR0xJQkNfMi4yLjUAX19UTUNfRU5EX18AX0lUTV9yZWdpc3RlclRNQ2xvbmVUYWJsZQBz
            dHJlcnJvckBHTElCQ18yLjIuNQBfX2N4YV9maW5hbGl6ZUBHTElCQ18yLjIuNQBfaW5pdAAALnN5
            bXRhYgAuc3RydGFiAC5zaHN0cnRhYgAuaW50ZXJwAC5ub3RlLmdudS5wcm9wZXJ0eQAubm90ZS5n
            bnUuYnVpbGQtaWQALm5vdGUuQUJJLXRhZwAuZ251Lmhhc2gALmR5bnN5bQAuZHluc3RyAC5nbnUu
            dmVyc2lvbgAuZ251LnZlcnNpb25fcgAucmVsYS5keW4ALnJlbGEucGx0AC5pbml0AC5wbHQuZ290
            AC5wbHQuc2VjAC50ZXh0AC5maW5pAC5yb2RhdGEALmVoX2ZyYW1lX2hkcgAuZWhfZnJhbWUALmlu
            aXRfYXJyYXkALmZpbmlfYXJyYXkALmR5bmFtaWMALmRhdGEALmJzcwAuY29tbWVudAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAABsAAAABAAAAAgAAAAAAAAAYAwAAAAAAABgDAAAAAAAAHAAAAAAAAAAAAAAAAAAAAAEA
            AAAAAAAAAAAAAAAAAAAjAAAABwAAAAIAAAAAAAAAOAMAAAAAAAA4AwAAAAAAADAAAAAAAAAAAAAA
            AAAAAAAIAAAAAAAAAAAAAAAAAAAANgAAAAcAAAACAAAAAAAAAGgDAAAAAAAAaAMAAAAAAAAkAAAA
            AAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAEkAAAAHAAAAAgAAAAAAAACMAwAAAAAAAIwDAAAA
            AAAAIAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAABXAAAA9v//bwIAAAAAAAAAsAMAAAAA
            AACwAwAAAAAAACQAAAAAAAAABgAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAYQAAAAsAAAACAAAAAAAA
            ANgDAAAAAAAA2AMAAAAAAADgAQAAAAAAAAcAAAABAAAACAAAAAAAAAAYAAAAAAAAAGkAAAADAAAA
            AgAAAAAAAAC4BQAAAAAAALgFAAAAAAAAJgEAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAABx
            AAAA////bwIAAAAAAAAA3gYAAAAAAADeBgAAAAAAACgAAAAAAAAABgAAAAAAAAACAAAAAAAAAAIA
            AAAAAAAAfgAAAP7//28CAAAAAAAAAAgHAAAAAAAACAcAAAAAAABgAAAAAAAAAAcAAAABAAAACAAA
            AAAAAAAAAAAAAAAAAI0AAAAEAAAAAgAAAAAAAABoBwAAAAAAAGgHAAAAAAAAwAAAAAAAAAAGAAAA
            AAAAAAgAAAAAAAAAGAAAAAAAAACXAAAABAAAAEIAAAAAAAAAKAgAAAAAAAAoCAAAAAAAAFABAAAA
            AAAABgAAABgAAAAIAAAAAAAAABgAAAAAAAAAoQAAAAEAAAAGAAAAAAAAAAAQAAAAAAAAABAAAAAA
            AAAbAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAJwAAAABAAAABgAAAAAAAAAgEAAAAAAA
            ACAQAAAAAAAA8AAAAAAAAAAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAACnAAAAAQAAAAYAAAAAAAAA
            EBEAAAAAAAAQEQAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAABAAAAAAAAAAsAAAAAEAAAAG
            AAAAAAAAACARAAAAAAAAIBEAAAAAAADgAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAALkA
            AAABAAAABgAAAAAAAAAAEgAAAAAAAAASAAAAAAAAYwQAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAA
            AAAAAAC/AAAAAQAAAAYAAAAAAAAAZBYAAAAAAABkFgAAAAAAAA0AAAAAAAAAAAAAAAAAAAAEAAAA
            AAAAAAAAAAAAAAAAxQAAAAEAAAACAAAAAAAAAAAgAAAAAAAAACAAAAAAAABMAQAAAAAAAAAAAAAA
            AAAACAAAAAAAAAAAAAAAAAAAAM0AAAABAAAAAgAAAAAAAABMIQAAAAAAAEwhAAAAAAAAPAAAAAAA
            AAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAADbAAAAAQAAAAIAAAAAAAAAiCEAAAAAAACIIQAAAAAA
            ABwBAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAA5QAAAA4AAAADAAAAAAAAAFA9AAAAAAAA
            UC0AAAAAAAAIAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAIAAAAAAAAAPEAAAAPAAAAAwAAAAAAAABY
            PQAAAAAAAFgtAAAAAAAACAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAACAAAAAAAAAD9AAAABgAAAAMA
            AAAAAAAAYD0AAAAAAABgLQAAAAAAAPABAAAAAAAABwAAAAAAAAAIAAAAAAAAABAAAAAAAAAAqwAA
            AAEAAAADAAAAAAAAAFA/AAAAAAAAUC8AAAAAAACwAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAIAAAA
            AAAAAAYBAAABAAAAAwAAAAAAAAAAQAAAAAAAAAAwAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAgAAAAA
            AAAAAAAAAAAAAAAMAQAACAAAAAMAAAAAAAAAEEAAAAAAAAAQMAAAAAAAAAgAAAAAAAAAAAAAAAAA
            AAABAAAAAAAAAAAAAAAAAAAAEQEAAAEAAAAwAAAAAAAAAAAAAAAAAAAAEDAAAAAAAAAtAAAAAAAA
            AAAAAAAAAAAAAQAAAAAAAAABAAAAAAAAAAEAAAACAAAAAAAAAAAAAAAAAAAAAAAAAEAwAAAAAAAA
            sAQAAAAAAAAdAAAAEwAAAAgAAAAAAAAAGAAAAAAAAAAJAAAAAwAAAAAAAAAAAAAAAAAAAAAAAADw
            NAAAAAAAAO8CAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAEQAAAAMAAAAAAAAAAAAAAAAA
            AAAAAAAA3zcAAAAAAAAaAQAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAA=='
              printf '%s' "${'$'}LDOA_SHIM_B64" | tr -d ' \n' | base64 -d > "${'$'}D/ldoa-creat-shim.so" 2>/dev/null
              printf '%s' "${'$'}LDOA_PROBE_B64" | tr -d ' \n' | base64 -d > "${'$'}D/ice-dance-probe" 2>/dev/null
              chmod 644 "${'$'}D/ldoa-creat-shim.so" 2>/dev/null
              chmod 755 "${'$'}D/ice-dance-probe" 2>/dev/null
              SHIM_OK=0; PROBE_OK=0
              [ "${'$'}(head -c 4 "${'$'}D/ldoa-creat-shim.so" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ] && SHIM_OK=1
              [ "${'$'}(head -c 4 "${'$'}D/ice-dance-probe" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ] && PROBE_OK=1
              if [ "${'$'}PROBE_OK" = 1 ]; then
                echo "gnome-launch: ice-dance-probe baseline (no preload):"
                "${'$'}D/ice-dance-probe" 2>&1 | sed 's/^/  probe: /'
              else
                echo "gnome-launch: ice-dance-probe decode/ELF check FAILED - probe skipped"
              fi
              if [ "${'$'}SHIM_OK" = 1 ]; then
                if [ "${'$'}PROBE_OK" = 1 ]; then
                  echo "gnome-launch: ice-dance-probe with creat-shim:"
                  LD_PRELOAD="${'$'}D/ldoa-creat-shim.so" "${'$'}D/ice-dance-probe" 2>&1 | sed 's/^/  probe+shim: /'
                fi
                # Guest-side smoke: the shim must actually load in THIS rootfs/libc
                # before it touches the session; fail closed otherwise.
                if LD_PRELOAD="${'$'}D/ldoa-creat-shim.so" /bin/true 2>"${'$'}D/.smoke-err"; then
                  if [ "${'$'}{LD_PRELOAD+x}" = x ] && [ -n "${'$'}LD_PRELOAD" ]; then
                    GS_PRELOAD="${'$'}D/ldoa-creat-shim.so ${'$'}LD_PRELOAD"
                    GS_PRELOAD_BRANCH="chained(shim + preserved pre-existing runtime LD_PRELOAD)"
                  else
                    GS_PRELOAD="${'$'}D/ldoa-creat-shim.so"
                    GS_PRELOAD_BRANCH="fresh(shim only; no pre-existing LD_PRELOAD in launch env)"
                  fi
                else
                  GS_PRELOAD_BRANCH="fail-closed(shim guest smoke load failed: ${'$'}(head -c 200 "${'$'}D/.smoke-err" 2>/dev/null))"
                fi
              else
                GS_PRELOAD_BRANCH="fail-closed(shim decode/ELF check failed)"
              fi
            fi
            echo "gnome-launch: creat-shim preload-branch=${'$'}GS_PRELOAD_BRANCH"

            # BOUNDED + REVERSIBLE crash-component exclusion, two INDEPENDENT
            # gates: gateRfkill and gateUsbProtection. Evidence (corroborated,
            # chain not fully proven): POCO phone log 2026-10-30 launch
            # 1793346581 shows gsd-rfkill SIGABRT (rfkill udev device has no
            # device file under proot) and gsd-usb-protection SIGSEGV; both
            # .desktop files ship X-GNOME-AutoRestart=true, supporting the
            # crash-LOOP mechanism that ended in gnome-session-failed - the
            # exact escalation chain (required-component vs autorestart) is
            # NOT proven by that log alone. Grounded in the actual Noble
            # packages (not memory): ubuntu-session_46.0-1ubuntu4
            # ubuntu.session RequiredComponents (Rfkill present; UsbProtection
            # absent) and gnome-settings-daemon_46.0-1ubuntu1.24.04.1
            # /etc/xdg/autostart/org.gnome.SettingsDaemon.{Rfkill,UsbProtection}.desktop.
            # LOOKUP grounded in gnome-session 46.0 source (gsm-session-fill.c
            # find_valid_session_keyfile): the session file is searched in
            # user-config, system-config, then system-data dirs in order -
            # /usr/local/share precedes /usr/share in the glib default
            # XDG_DATA_DIRS (this script never sets XDG_DATA_DIRS), so a
            # same-name file under /usr/local/share/gnome-session/sessions/
            # wins WITHOUT touching the stock file. IDENTITY PRESERVED BY
            # CONSTRUCTION: the session NAME stays "ubuntu", and every
            # identity-relevant input (name, env) is byte-identical to the
            # dock/yaru-pixel-verified configuration - XDG_CURRENT_DESKTOP=
            # ubuntu:GNOME is exported by this script itself above;
            # DESKTOP_SESSION/XDG_SESSION_DESKTOP/GNOME_SHELL_SESSION_MODE
            # derivation, OnlyShowIn matching, dock and theme behavior cannot
            # change from a RequiredComponents-only diff.
            # OWNERSHIP: nothing pre-existing is overwritten. The marker alone
            # never authorizes a write: an existing file must ALSO match its
            # last .ldoa-manifest sha256, otherwise it is someone else's
            # edit and is preserved (gate BLOCKED, both hashes logged).
            # Revert = remove manifest-listed ldoa-managed files only.
            ldoa_sha() { sha256sum "${'$'}1" 2>/dev/null | awk '{print ${'$'}1}'; }
            ldoa_manifest_sha() { [ -f "${'$'}2" ] && awk -v p="${'$'}1" '${'$'}1==p {s=${'$'}2} END {if (s!="") print s}' "${'$'}2"; }
            # ldoa_put_managed PATH MANIFEST LABEL - content on stdin
            ldoa_put_managed() {
              if [ -f "${'$'}1" ]; then
                if ! grep -q '^# ldoa-managed' "${'$'}1" 2>/dev/null; then
                  echo "gnome-launch: ${'$'}3 BLOCKED - ${'$'}1 exists, not ldoa-managed (sha256=${'$'}(ldoa_sha "${'$'}1")) - left untouched"
                  return 1
                fi
                MS=${'$'}(ldoa_manifest_sha "${'$'}1" "${'$'}2"); CS=${'$'}(ldoa_sha "${'$'}1")
                if [ -z "${'$'}MS" ] || [ "${'$'}MS" != "${'$'}CS" ]; then
                  echo "gnome-launch: ${'$'}3 BLOCKED - ${'$'}1 ldoa-managed but changed since manifest (manifest=${'$'}{MS:-none} current=${'$'}CS) - preserving unknown modified content"
                  return 1
                fi
              fi
              if ! cat > "${'$'}1" 2>/dev/null || ! grep -q '^# ldoa-managed' "${'$'}1" 2>/dev/null; then
                rm -f "${'$'}1"
                echo "gnome-launch: ${'$'}3 WRITE-FAILED - ${'$'}1 removed, stock/defaults in effect"
                return 1
              fi
              echo "${'$'}1 ${'$'}(ldoa_sha "${'$'}1")" >> "${'$'}2"
              return 0
            }
            LDOA_SESS_DIR=/usr/local/share/gnome-session/sessions
            LDOA_AUTO_DIR=/root/.config/autostart
            SESS_STATE=stock
            if [ -f /usr/share/gnome-session/sessions/ubuntu.session ]; then
              mkdir -p "${'$'}LDOA_SESS_DIR" 2>/dev/null
              if { echo '# ldoa-managed: stock ubuntu.session minus Rfkill in RequiredComponents (revert: remove manifest-listed file)'
                   sed 's/;org.gnome.SettingsDaemon.Rfkill//' /usr/share/gnome-session/sessions/ubuntu.session; } | \
                   ldoa_put_managed "${'$'}LDOA_SESS_DIR/ubuntu.session" "${'$'}LDOA_SESS_DIR/.ldoa-manifest" "gateRfkill session-override" \
                 && grep -q 'RequiredComponents=org.gnome.Shell;org.gnome.SettingsDaemon.A11ySettings' "${'$'}LDOA_SESS_DIR/ubuntu.session" \
                 && ! grep -q 'SettingsDaemon.Rfkill' "${'$'}LDOA_SESS_DIR/ubuntu.session"; then
                SESS_STATE=ldoa-minus-rfkill
                echo "gnome-launch: gateRfkill session-override ACTIVE - ${'$'}LDOA_SESS_DIR/ubuntu.session (name stays ubuntu; only Rfkill removed; stock untouched)"
              else
                [ "${'$'}SESS_STATE" = stock ] && echo "gnome-launch: gateRfkill session-override INACTIVE - stock session in effect"
              fi
            else
              echo "gnome-launch: gateRfkill stock ubuntu.session MISSING - session override skipped"
            fi
            # Autostart masks, one independent verdict per component.
            mkdir -p "${'$'}LDOA_AUTO_DIR" 2>/dev/null
            for A in Rfkill UsbProtection; do
              if printf '# ldoa-managed: Hidden mask for gsd %s (revert: remove manifest-listed file)\n[Desktop Entry]\nType=Application\nName=org.gnome.SettingsDaemon.%s (ldoa-disabled)\nHidden=true\n' "${'$'}A" "${'$'}A" | \
                 ldoa_put_managed "${'$'}LDOA_AUTO_DIR/org.gnome.SettingsDaemon.${'$'}A.desktop" "${'$'}LDOA_AUTO_DIR/.ldoa-manifest" "gate${'$'}A mask"; then
                echo "gnome-launch: gate${'$'}A mask ACTIVE (Hidden=true; stock /etc/xdg file untouched)"
              fi
            done
            echo "gnome-launch: exclusion-state session=${'$'}SESS_STATE XDG_DATA_DIRS='${'$'}XDG_DATA_DIRS' (unset = glib default, /usr/local/share before /usr/share)"
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
              timeout 60 apt-get install -y strace >/dev/null 2>&1
            fi
            # strace 6.8 source (src/strace.c test_ptrace_seize): a failed
            # PTRACE_SEIZE self-test is error_func_msg_and_die - strace
            # EXITS 1 BEFORE tracing anything (run 41236388769: "unexpected
            # wait status 0" under proot, zero session output). So a dead
            # strace WRAPPER's exit code is not a gnome-session exit.
            # Self-test first; a blind strace must never masquerade as a
            # session death - fall back to an untraced launch whose exit
            # status IS the real session's, and label the branch.
            TRACED=untraced
            if command -v strace >/dev/null 2>&1; then
              timeout 30 strace -f -o /tmp/xdg/.strace-selftest -e trace=none /bin/true >/dev/null 2>&1
              STRC=${'$'}?
              if [ "${'$'}STRC" = 0 ]; then
                TRACED=traced
              else
                echo "gnome-launch: strace self-test FAILED rc=${'$'}STRC (nonzero/timeout=124: ptrace blind here, run 41236388769: unexpected wait status 0) - launching UNTRACED"
              fi
            else
              echo "gnome-launch: strace unavailable - launching untraced"
            fi
            if [ "${'$'}TRACED" = traced ]; then
              WNOTE="strace WRAPPER exit - session status UNKNOWN (a self-test pass does not prove this traced run; wrapper death never proves session death)"
              echo "gnome-launch: branch=traced (wrapper=strace; its exit is NOT the session exit)"
              if [ -n "${'$'}GS_PRELOAD" ]; then
                echo "gnome-launch: launch-env LD_PRELOAD=creat-shim applied (traced branch; session + its children inherit)"
                LD_PRELOAD="${'$'}GS_PRELOAD" strace -f -o /tmp/xdg/gs-ice.strace -e trace=link,linkat,unlink,unlinkat,creat,open,openat,stat,newfstatat,fcntl,flock,close,dup,dup2,dup3 gnome-session --session=ubuntu 2>&1 &
              else
                echo "gnome-launch: launch-env no preload (traced branch; fallback = current behavior)"
                strace -f -o /tmp/xdg/gs-ice.strace -e trace=link,linkat,unlink,unlinkat,creat,open,openat,stat,newfstatat,fcntl,flock,close,dup,dup2,dup3 gnome-session --session=ubuntu 2>&1 &
              fi
            else
              WNOTE="untraced - this IS the real session exit"
              echo "gnome-launch: branch=untraced"
              if [ -n "${'$'}GS_PRELOAD" ]; then
                echo "gnome-launch: launch-env LD_PRELOAD=creat-shim applied (untraced branch; session + its children inherit)"
                LD_PRELOAD="${'$'}GS_PRELOAD" gnome-session --session=ubuntu 2>&1 &
              else
                echo "gnome-launch: launch-env no preload (untraced branch; fallback = current behavior)"
                gnome-session --session=ubuntu 2>&1 &
              fi
            fi
            GSPID=${'$'}!
            # --- Owned-tree identity (observation/preservation only; NO kill
            # machinery lives here): the survivor checks below need to know
            # WHICH processes this launch actually owns. Name-matching alone
            # cannot say (a sibling session's components look identical), so
            # the session's process tree is snapshotted as pid:starttime
            # pairs (pid-reuse safe) at the +20s check and refreshed when the
            # observer's budget expires (later-spawned components included).
            # Detached/reparented members still escape enumeration - that
            # residue is labeled ownership-unknown, never asserted owned. ---
            pid_starttime() { sed 's/^[^)]*) //' "/proc/${'$'}1/stat" 2>/dev/null | awk '{print ${'$'}20}'; }
            pid_state() { sed 's/^[^)]*) //' "/proc/${'$'}1/stat" 2>/dev/null | awk '{print ${'$'}1}'; }
            children_of() {
              for s in /proc/[0-9]*/stat; do
                P=${'$'}{s#/proc/}; P=${'$'}{P%/stat}
                PP=${'$'}(sed 's/^[^)]*) //' "${'$'}s" 2>/dev/null | awk '{print ${'$'}2}')
                [ "${'$'}PP" = "${'$'}1" ] && echo "${'$'}P"
              done
            }
            snapshot_owned_tree() {
              [ -d "/proc/${'$'}1" ] || return 1
              FRONT="${'$'}1"; CT=1; : > /tmp/xdg/ldoa-owned-tree.${'$'}LAUNCH_ID.tmp
              while [ -n "${'$'}FRONT" ] && [ ${'$'}CT -lt 512 ]; do
                NXT=""
                for P in ${'$'}FRONT; do
                  echo "${'$'}P:${'$'}(pid_starttime "${'$'}P")" >> /tmp/xdg/ldoa-owned-tree.${'$'}LAUNCH_ID.tmp
                  for C in ${'$'}(children_of "${'$'}P"); do NXT="${'$'}NXT ${'$'}C"; CT=${'$'}((CT + 1)); done
                done
                FRONT="${'$'}NXT"
              done
              mv -f /tmp/xdg/ldoa-owned-tree.${'$'}LAUNCH_ID.tmp /tmp/xdg/ldoa-owned-tree.${'$'}LAUNCH_ID
            }
            # member_alive P ST: pid exists, not a zombie, starttime matches
            # the snapshot (zombies count as dead - they hold no resources).
            member_alive() {
              [ -d "/proc/${'$'}1" ] || return 1
              [ "${'$'}(pid_state "${'$'}1")" = "Z" ] && return 1
              [ "${'$'}(pid_starttime "${'$'}1")" = "${'$'}2" ]
            }
            # Observer (formerly a killing watchdog): the deadline poll exists
            # so a shell that never registers is NOTICED, never KILLED. Phone
            # evidence 2026-10-31: a healthy warm-start session registered at
            # +75s; a cold start can need longer, and no signal available in
            # this script (CPU delta, X answers, window churn) distinguishes
            # "slow but healthy" from "wedged" - heuristics would kill
            # healthy slow startups (the user's 2026-10-31 late-success
            # session is the proof shape). So after the budget this loop only
            # OBSERVES: doubly-bounded pings (gdbus --timeout 2 inside
            # `timeout 4` - the mechanisms are not interchangeable), a
            # late-readiness log line if the shell still makes it, and a work
            # cap that ends the observer WITHOUT touching the session. The
            # kill/stop decision for a healthy-unknown session belongs to the
            # user (Stop session in the app), never to a script heuristic.
            # The unified fallback below still runs on ACTUAL session-process
            # exit - verified by identity (wrapper AND owned components gone;
            # tracees outlive a dead wrapper).
            ( WD_START=${'$'}(date +%s); WD_BUDGET=${'$'}{LDOA_WD_BUDGET_S:-106}
              OBS_CAP=${'$'}{LDOA_WD_OBSERVER_S:-600}
              while [ ${'$'}(( ${'$'}(date +%s) - WD_START )) -lt ${'$'}WD_BUDGET ]; do
                sleep 2
                kill -0 ${'$'}GSPID 2>/dev/null || exit 0
                if timeout 4 gdbus call --session --timeout 2 --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                  exit 0
                fi
              done
              if timeout 4 gdbus call --session --timeout 2 --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                exit 0
              fi
              snapshot_owned_tree "${'$'}GSPID" 2>/dev/null || true
              echo "gnome-launch: no shell on bus by +${'$'}(( ${'$'}(date +%s) - WD_START ))s (budget=${'$'}WD_BUDGET) - STILL STARTING (readiness unknown, NOT a failure): observing up to +${'$'}{OBS_CAP}s, session left running, nothing killed; Stop session in the app ends it"
              while [ ${'$'}(( ${'$'}(date +%s) - WD_START )) -lt ${'$'}OBS_CAP ]; do
                sleep 10
                if ! kill -0 ${'$'}GSPID 2>/dev/null; then
                  # Wrapper death alone is NOT session death (ptrace detach
                  # history: tracees continue under a dead wrapper). Two
                  # evidence classes, both preservation-only (never a kill):
                  #  A) the owned-tree snapshot (pid+starttime verified)
                  #  B) pgrep name-match (ownership UNKNOWN - a sibling
                  #     session's components look identical; detached own
                  #     services may be missed).
                  OALIVE=""
                  if [ -f /tmp/xdg/ldoa-owned-tree.${'$'}LAUNCH_ID ]; then
                    for M in ${'$'}(cat /tmp/xdg/ldoa-owned-tree.${'$'}LAUNCH_ID); do
                      OP=${'$'}{M%%:*}; OST=${'$'}{M##*:}
                      [ "${'$'}OP" = "${'$'}GSPID" ] && continue
                      member_alive "${'$'}OP" "${'$'}OST" && OALIVE="${'$'}OALIVE ${'$'}OP"
                    done
                  fi
                  if [ -n "${'$'}OALIVE" ]; then
                    echo "gnome-launch: observer: wrapper pid=${'$'}GSPID gone but OWNED session components alive at +${'$'}(( ${'$'}(date +%s) - WD_START ))s (identity verified vs launch snapshot:${'$'}OALIVE) - continuing observation"
                  elif pgrep -f 'gnome-session-binary|gnome-shell' >/dev/null 2>&1; then
                    echo "gnome-launch: observer: wrapper pid=${'$'}GSPID gone and snapshot empty/stale, but name-matched GNOME components visible (ownership UNKNOWN) at +${'$'}(( ${'$'}(date +%s) - WD_START ))s - continuing observation conservatively"
                  else
                    echo "gnome-launch: observer: no owned (snapshot) or name-matched session processes visible at +${'$'}(( ${'$'}(date +%s) - WD_START ))s - observation ends, nothing killed; the main flow owns the actual-exit fallback"
                    exit 0
                  fi
                fi
                if timeout 4 gdbus call --session --timeout 2 --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                  echo "gnome-launch: late readiness - shell registered on the session bus at +${'$'}(( ${'$'}(date +%s) - WD_START ))s (after the initial budget: startup was slow, not failed)"
                  # Machine gate marker (same shape the app gate matches; late=1
                  # sits BEFORE bus= so the address comparison stays exact).
                  # Without it a shell slower than the first-phase budget never
                  # satisfies the gate even after registration (POCO 2026-11-01:
                  # shell registered at +143s, gate saw nothing).
                  echo "gnome-launch: shell registered late=1 launch=${'$'}LAUNCH_ID bus=${'$'}DBUS_SESSION_BUS_ADDRESS"
                  exit 0
                fi
              done
              echo "gnome-launch: observer capped at +${'$'}(( ${'$'}(date +%s) - WD_START ))s (cap=${'$'}OBS_CAP) - session left running, readiness still unknown, nothing killed"
              exit 0
            ) &
            WATCHDOG_PID=${'$'}!
            sleep 20
            if kill -0 ${'$'}GSPID 2>/dev/null; then
              echo "gnome-launch: gnome-session alive pid=${'$'}GSPID branch=${'$'}TRACED"
              snapshot_owned_tree "${'$'}GSPID" 2>/dev/null || true
              wait ${'$'}GSPID
              echo "gnome-launch: session launcher exited=${'$'}? after surviving the 20s check (branch=${'$'}TRACED: ${'$'}WNOTE) - falling back to bare gnome-shell --x11"
            else
              wait ${'$'}GSPID 2>/dev/null
              echo "gnome-launch: session launcher died exit=${'$'}? (branch=${'$'}TRACED: ${'$'}WNOTE) - falling back to bare gnome-shell --x11"
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
            kill ${'$'}POLL_A_PID 2>/dev/null
            # The observer never kills: it exits on ping success, session
            # death, or its cap. Waiting for it here keeps the fallback's
            # markers distinguishable (a stray poll must not report the
            # FALLBACK shell's registration as a full session success) and
            # bounds this wait to one observer cycle (<= 10s + <= 4s ping).
            wait ${'$'}WATCHDOG_PID 2>/dev/null
            # Verify the OWNED tree is really gone before bringing up a
            # second WM: the wrapper's exit does not prove the session's
            # components exited (ptrace detach history: tracees continue
            # under a dead wrapper). A name-READ is not a name-kill: any
            # surviving gnome-session-binary/gnome-shell means a fallback
            # exec would be a duplicate WM - fail closed. One short re-check
            # absorbs the normal shutdown ordering race (the session exits,
            # its managed clients exit a beat later).
            S=0; SURV=; SURV_OWNED=
            while [ ${'$'}S -lt 2 ]; do
              SURV_OWNED=""
              if [ -f /tmp/xdg/ldoa-owned-tree.${'$'}LAUNCH_ID ]; then
                for M in ${'$'}(cat /tmp/xdg/ldoa-owned-tree.${'$'}LAUNCH_ID); do
                  OP=${'$'}{M%%:*}; OST=${'$'}{M##*:}
                  [ "${'$'}OP" = "${'$'}GSPID" ] && continue
                  case "${'$'}(cat /proc/${'$'}OP/comm 2>/dev/null)" in
                    gnome-shell|gnome-session-binary)
                      member_alive "${'$'}OP" "${'$'}OST" && SURV_OWNED="${'$'}SURV_OWNED ${'$'}OP" ;;
                  esac
                done
              fi
              SURV=""
              for P in ${'$'}(pgrep -f 'gnome-session-binary|gnome-shell' 2>/dev/null); do
                case " ${'$'}SURV_OWNED " in *" ${'$'}P "*) ;; *) SURV="${'$'}SURV ${'$'}P" ;; esac
              done
              [ -z "${'$'}SURV${'$'}SURV_OWNED" ] && break
              sleep 3; S=${'$'}((S + 1))
            done
            if [ -n "${'$'}SURV${'$'}SURV_OWNED" ]; then
              echo "gnome-launch: fallback suppressed - possibly surviving session components after the session exit (owned-tree identity verified:${'$'}{SURV_OWNED:- none} | name-match only, ownership UNKNOWN:${'$'}{SURV:- none} - fail closed, duplicate-WM risk; Stop session in the app cleans up)"
              exit 1
            fi
                        # Fallback-phase readiness poll:
                        # the first 60s poll can expire
            # before the fallback shell registers. Emit the SAME success
            # marker the app gate matches; this phase's verdict is the
            # definitive failure (the first-phase "shell missing after 60s"
            # is not - the fallback may still be coming).
            ( FP_START=${'$'}(date +%s)
              while [ ${'$'}(( ${'$'}(date +%s) - FP_START )) -lt 90 ]; do
                sleep 2
                if timeout 4 gdbus call --session --timeout 2 --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
                  echo "gnome-launch: fallback shell registered launch=${'$'}LAUNCH_ID bus=${'$'}DBUS_SESSION_BUS_ADDRESS"
                  break
                fi
              done
              if ! timeout 4 gdbus call --session --timeout 2 --dest org.gnome.Shell --object-path /org/gnome/Shell --method org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
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

package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Guest-side GUI: provisions an X client inside the installed rootfs and
 * launches it against the embedded X server through the proot-bound socket.
 * Milestone client is xclock; on top of it a real desktop shell profile:
 * openbox (draggable/resizable windows) + tint2 (taskbar + launchers) +
 * pcmanfm (desktop icons + file manager). User-facing goal: a computer-like
 * desktop, not a bare X surface.
 */
object GuestDesktop {
    private const val TAG = "PhoneLinuxGui"

    /** apk add the minimal X client. Network in guest via rootfs resolv.conf. */
    fun provisionXclock(ctx: Context, rootfs: File): Proot.Result =
        // No --arch flag: /etc/apk/arch (pinned to the rootfs' own arch at
        // install) governs; a wrong override breaks signature verification.
        Proot.runGuest(ctx, rootfs, listOf("apk", "add", "--no-cache", "xclock"))

    fun hasXclock(rootfs: File): Boolean = File(rootfs, "usr/bin/xclock").isFile

    /** Run a shell line in the guest (diagnostics). */
    fun guestExec(ctx: Context, rootfs: File, shell: String): Proot.Result =
        Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", shell))

    /** Launch xclock with DISPLAY on the embedded server's bound socket. */
    fun launchXclock(ctx: Context, rootfs: File): Process {
        val nd = ctx.applicationInfo.nativeLibraryDir
        val log = File(ctx.filesDir, "guest-gui.log")
        val pb = ProcessBuilder(
            File(nd, "libproot.so").absolutePath,
            "--link2symlink",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root",
            "/usr/bin/xclock"
        )
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root",
            "DISPLAY" to ":0",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath
        ))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        Log.i(TAG, "launching xclock, log=$log")
        return pb.start()
    }

    /** Windows-like shell: taskbar (tint2), window management
     *  (openbox: drag, resize, minimize/maximize), file manager + desktop
     *  icons (pcmanfm), fonts so GUI text actually renders. lxpanel is gone
     *  from Alpine v3.21 (gtk2 purge); tint2 is the maintained light panel.
     *  No --arch flag: /etc/apk/arch (pinned to the rootfs' own arch) governs. */
    // Four candidate WMs: openbox SEGFAULTS (signal 11) under proot on the
    // x86_64 emulator (tint2 and pcmanfm run fine), and which WM survives may
    // differ on ARM phones - so the session script tries them in order and
    // keeps the first one that stays alive. All four exist in Alpine v3.21
    // community for both x86_64 and aarch64 (verified against the live index).
    private val DESKTOP_PACKAGES = listOf(
        "openbox", "jwm", "icewm", "fluxbox",
        "tint2", "pcmanfm", "xterm", "xsetroot", "fontconfig",
        "font-dejavu", "font-misc-misc", "hicolor-icon-theme",
        "util-linux", "st",
        // DIAGNOSTIC: dump the X window tree + screen size at screenshot
        // time to pin where tint2's panel actually landed.
        "xwininfo", "xdpyinfo")

    // tint2's own config generation corrupts its path under proot
    // ("/root/.config/tint2/t/nt2rc") and produces a 2px panel - ship a
    // known-good config instead: 48px bottom taskbar, launcher (xterm,
    // pcmanfm), task list, clock.
    // DIAGNOSTIC RED PANEL: xwininfo proves the tint2 window is mapped
    // (1080x48+0+1964 on a 1080x2012 screen) but its framebuffer zone reads
    // pure black in screenshots. An unmissable opaque red separates
    // "panel not rendering" from "panel dark-on-black"; revert to the dark
    // color once visibility is proven. All backgrounds made opaque: no
    // compositor runs under lorie, so any alpha < 100 cannot blend.
    private val TINT2RC = """
        rounded = 0
        border_width = 1
        background_color = #BF616A 100
        border_color = #3B4252 100
        rounded = 4
        border_width = 0
        background_color = #2E3440 100
        border_color = #000000 100
        panel_monitor = all
        panel_position = bottom center horizontal
        panel_size = 100% 48
        panel_margin = 0 0
        panel_padding = 6 4 6
        panel_background_id = 1
        panel_items = LTSC
        launcher_padding = 4 4 4
        launcher_background_id = 0
        launcher_icon_size = 32
        launcher_item_app = /usr/share/applications/xterm.desktop
        launcher_item_app = /usr/share/applications/pcmanfm.desktop
        taskbar_mode = single_desktop
        taskbar_padding = 2 2 4
        taskbar_background_id = 2
        taskbar_active_background_id = 1
        task_icon_size = 24
        task_text = 1
        time1_format = %H:%M
        time1_font = DejaVu Sans 10
        clock_padding = 6 2
        clock_background_id = 0
    """.trimIndent()

    fun provisionDesktopShell(ctx: Context, rootfs: File): Proot.Result =
        Proot.runGuest(ctx, rootfs, listOf("apk", "add", "--no-cache") + DESKTOP_PACKAGES)

    fun hasDesktopShell(rootfs: File): Boolean =
        File(rootfs, "usr/bin/openbox").isFile &&
        File(rootfs, "usr/bin/jwm").isFile &&
        File(rootfs, "usr/bin/icewm").isFile &&
        File(rootfs, "usr/bin/fluxbox").isFile &&
        File(rootfs, "usr/bin/tint2").isFile &&
        File(rootfs, "usr/bin/pcmanfm").isFile

    /** Launch the full desktop session: wm first, then desktop icons, then
     *  the panel; all against the embedded X server. */
    fun launchDesktopSession(ctx: Context, rootfs: File): Process {
        File(rootfs, "root/.config/tint2").mkdirs()
        File(rootfs, "root/.config/tint2/tint2rc").writeText(TINT2RC + "\n")
        // Desktop icons for pcmanfm --desktop (empty Desktop dir = no icons).
        File(rootfs, "root/Desktop").mkdirs()
        File(rootfs, "root/Desktop/xterm.desktop").writeText(
            "[Desktop Entry]\nType=Application\nName=Terminal\nExec=xterm -fa \"DejaVu Sans Mono\" -fs 12\nIcon=utilities-terminal\n")
        File(rootfs, "root/Desktop/files.desktop").writeText(
            "[Desktop Entry]\nType=Application\nName=Files\nExec=pcmanfm\nIcon=system-file-manager\n")
        File(rootfs, "root/desktop-session.sh").writeText(
            """
            #!/bin/sh
            export DISPLAY=:0 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg
            mkdir -p /tmp/xdg
            # Terminal emulators die if getpwuid(getuid()) fails: st's
            # execsh() calls die("who are you?") with child stderr on the
            # unread pty (invisible), parent prints "child exited with
            # status 1"; xterm fails the same lookup. The app uid is not in
            # Alpine's /etc/passwd, so add it before anything spawns.
            UID_NOW=${'$'}(id -u); GID_NOW=${'$'}(id -g)
            echo "guest-identity: uid=${'$'}UID_NOW gid=${'$'}GID_NOW"
            if ! awk -F: '${'$'}3 == u {f=1} END {exit !f}' u="${'$'}UID_NOW" /etc/passwd; then
              echo "plinux:x:${'$'}UID_NOW:${'$'}GID_NOW:Phone Linux:/root:/bin/sh" >> /etc/passwd
              echo "guest-identity: added passwd entry for uid ${'$'}UID_NOW"
            fi
            if ! awk -F: '${'$'}3 == g {f=1} END {exit !f}' g="${'$'}GID_NOW" /etc/group; then
              echo "plinux:x:${'$'}GID_NOW:" >> /etc/group
            fi
            WM=""
            for w in openbox jwm icewm fluxbox; do
              if command -v ${'$'}w >/dev/null 2>&1; then
                ${'$'}w &
                WMPID=${'$'}!
                sleep 5
                if kill -0 ${'$'}WMPID 2>/dev/null; then WM=${'$'}w; break; fi
                wait ${'$'}WMPID 2>/dev/null
                echo "wm ${'$'}w died, trying next"
              fi
            done
            echo "chosen-wm=${'$'}WM"
            if [ -z "${'$'}WM" ]; then echo "NO WM SURVIVED"; exit 1; fi
            fc-cache -f >/dev/null 2>&1
            pcmanfm --desktop &
            sleep 1
            xsetroot -solid "#2E3440"
            # jwm/icewm/fluxbox ship their own tray; only openbox needs tint2.
            if [ "${'$'}WM" = "openbox" ]; then tint2 & fi
            # -fa: Alpine xterm's default bitmap font "fixed" needs
            # font-misc-misc; Xft font avoids the dependency either way.
            # Both interactive xterm modes exited 0 silently in CI
            # (evidence: "XTERM DEAD both modes (exit=0)"). Exit 0 + silence
            # points at the pty child getting instant EOF, not a crash -
            # probe the pty layer directly and log which mode survives.
            if command -v script >/dev/null 2>&1; then
              script -q -c "echo PTY-PROBE-OK" /root/pty-probe.out
              echo "pty-probe exit=${'$'}? out=${'$'}(cat /root/pty-probe.out 2>/dev/null)"
            else
              echo "PTY-PROBE-SKIP (no script applet)"
            fi
            # Non-interactive pty commands work (probe above) but terminal
            # children - INTERACTIVE shells - exit instantly (st: child
            # exited status 1; xterm: silent exit 0). Feed script a fixed
            # two-line session so the inner interactive shell closes itself.
            printf 'echo INNER-ALIVE; exit\n' | script -q -c "/bin/sh -i" /root/pty-int.out
            echo "pty-interactive exit=${'$'}?"
            cat /root/pty-int.out
            printf 'echo INNER-ALIVE-NOM; exit\n' | script -q -c "/bin/sh +m -i" /root/pty-int-nom.out
            echo "pty-interactive-nom exit=${'$'}?"
            cat /root/pty-int-nom.out
            # Root cause (run 40562803151 + st 0.9.2 source read): pty,
            # setsid and TIOCSCTTY all work in the guest - the ctty probe
            # passed both st's and script's recipes. The killer was st.c
            # execsh(): getpwuid(getuid()) fails for the app uid, child
            # die("who are you?") goes to the unread pty, parent reports
            # "child exited with status 1" in every mode. The identity
            # block above adds the uid to the guest passwd first. xterm
            # does the same lookup and is expected to recover too.
            launch_term() {
              M=${'$'}1; BIN=${'$'}2; shift 2
              command -v ${'$'}BIN >/dev/null 2>&1 || { echo "term-skip mode=${'$'}M (no ${'$'}BIN)"; return 1; }
              ${'$'}BIN "${'$'}@" &
              TPID=${'$'}!
              sleep 8
              if kill -0 ${'$'}TPID 2>/dev/null; then
                echo "term-ok mode=${'$'}M bin=${'$'}BIN pid=${'$'}TPID"; return 0
              fi
              wait ${'$'}TPID; echo "term died mode=${'$'}M bin=${'$'}BIN exit=${'$'}?"; return 1
            }
            # Probes prove script's pty spawn recipe works for INTERACTIVE
            # shells (clean prompt, commands run) while every terminal-spawned
            # child dies instantly. st-nested applies that proven recipe as a
            # trampoline inside st; st-childerr redirects the child's stderr
            # to a file so its actual death message is captured (the child's
            # stderr normally dies with the pty, never reaching this log).
            # Geometry +40+420: keeps the top-left desktop icons and the
            # bottom panel visible in the same screenshot as the window.
            launch_term xterm-xft xterm -ut -fa "DejaVu Sans Mono" -fs 12 -geometry 100x30+40+420 ||
            launch_term st-nested st -f "DejaVu Sans Mono:size=12" -g 100x30+40+420 \
              -e script -qfc "/bin/sh -i" /dev/null ||
            launch_term st-childerr st -f "DejaVu Sans Mono:size=12" -g 100x30+40+420 \
              -e /bin/sh -c 'exec 2>/root/child-err.log; exec /bin/sh -i' ||
            echo "NO TERMINAL SURVIVED"
            cat /root/child-err.log 2>/dev/null
            wait
            """.trimIndent() + "\n")
        val nd = ctx.applicationInfo.nativeLibraryDir
        val log = File(ctx.filesDir, "guest-desktop.log")
        val pb = ProcessBuilder(
            File(nd, "libproot.so").absolutePath,
            "--link2symlink",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", XServer.socketDir(ctx).absolutePath + ":/tmp/.X11-unix",
            "-w", "/root",
            "/bin/sh", "/root/desktop-session.sh"
        )
        pb.environment().putAll(mapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root",
            "DISPLAY" to ":0",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to Proot.prootTmpDir(ctx).absolutePath
        ))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        Log.i(TAG, "launching desktop session, log=$log")
        return pb.start()
    }

    /** Guest shell command stdout (diagnostics). */
    fun guestShell(ctx: Context, rootfs: File, shellCmd: String,
                   extraBinds: List<Pair<String, String>> = emptyList()): String =
        (Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", shellCmd),
            extraBinds = extraBinds).stdout).trim()

    /** True when a guest shell command exits 0 (process liveness probes). */
    fun guestOk(ctx: Context, rootfs: File, shellCmd: String): Boolean =
        Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", shellCmd),
            extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix")).exitCode == 0

}
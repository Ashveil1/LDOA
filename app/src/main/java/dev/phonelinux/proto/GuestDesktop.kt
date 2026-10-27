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
        "font-dejavu", "font-misc-misc", "hicolor-icon-theme")

    // tint2's own config generation corrupts its path under proot
    // ("/root/.config/tint2/t/nt2rc") and produces a 2px panel - ship a
    // known-good config instead: 48px bottom taskbar, launcher (xterm,
    // pcmanfm), task list, clock.
    private val TINT2RC = """
        rounded = 0
        border_width = 1
        background_color = #1B1E28 100
        border_color = #3B4252 100
        rounded = 4
        border_width = 0
        background_color = #2E3440 60
        border_color = #000000 0
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
            xterm -fa "DejaVu Sans Mono" -fs 12 -geometry 100x30+120+120 &
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
    fun guestShell(ctx: Context, rootfs: File, shellCmd: String): String =
        (Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", shellCmd)).stdout).trim()

    /** True when a guest shell command exits 0 (process liveness probes). */
    fun guestOk(ctx: Context, rootfs: File, shellCmd: String): Boolean =
        Proot.runGuest(ctx, rootfs, listOf("/bin/sh", "-c", shellCmd),
            extraBinds = listOf(XServer.socketDir(ctx).absolutePath to "/tmp/.X11-unix")).exitCode == 0

}
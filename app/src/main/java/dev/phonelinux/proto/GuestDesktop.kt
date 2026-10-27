package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Guest-side GUI: provisions an X client inside the installed rootfs and
 * launches it against the embedded X server through the proot-bound socket.
 * Milestone client is xclock; on top of it a real desktop shell profile:
 * openbox (draggable/resizable windows) + lxpanel (taskbar + app menu) +
 * pcmanfm (desktop icons + file manager). User-facing goal: a computer-like
 * desktop, not a bare X surface.
 */
object GuestDesktop {
    private const val TAG = "PhoneLinuxGui"

    /** apk add the minimal X client. Network in guest via rootfs resolv.conf. */
    fun provisionXclock(ctx: Context, rootfs: File): Proot.Result =
        Proot.runGuest(ctx, rootfs, listOf("apk", "add", "--no-cache", "--arch", "aarch64", "xclock"))

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
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        ))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        Log.i(TAG, "launching xclock, log=$log")
        return pb.start()
    }

    /** Windows-like shell: taskbar/menu (lxpanel), window management
     *  (openbox: drag, resize, minimize/maximize), file manager + desktop
     *  icons (pcmanfm), fonts so GUI text actually renders. */
    private val DESKTOP_PACKAGES = listOf(
        "openbox", "lxpanel", "pcmanfm", "font-dejavu", "hicolor-icon-theme")

    fun provisionDesktopShell(ctx: Context, rootfs: File): Proot.Result =
        Proot.runGuest(ctx, rootfs, listOf("apk", "add", "--no-cache", "--arch", "aarch64") + DESKTOP_PACKAGES)

    fun hasDesktopShell(rootfs: File): Boolean =
        File(rootfs, "usr/bin/openbox").isFile &&
        File(rootfs, "usr/bin/lxpanel").isFile &&
        File(rootfs, "usr/bin/pcmanfm").isFile

    /** Launch the full desktop session: wm first, then desktop icons, then
     *  the panel; all against the embedded X server. */
    fun launchDesktopSession(ctx: Context, rootfs: File): Process {
        File(rootfs, "root/desktop-session.sh").writeText(
            """
            #!/bin/sh
            export DISPLAY=:0 HOME=/root XDG_RUNTIME_DIR=/tmp/xdg
            mkdir -p /tmp/xdg
            openbox &
            sleep 2
            pcmanfm --desktop &
            lxpanel &
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
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
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
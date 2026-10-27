package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Guest-side GUI: provisions an X client inside the installed rootfs and
 * launches it against the embedded X server through the proot-bound socket.
 * Milestone client is xclock; the XFCE session profile replaces it later.
 */
object GuestDesktop {
    private const val TAG = "PhoneLinuxGui"

    /** apk add the minimal X client. Network in guest via rootfs resolv.conf. */
    fun provisionXclock(ctx: Context, rootfs: File): Proot.Result =
        Proot.runGuest(ctx, rootfs, listOf("apk", "add", "--no-cache", "xclock"))

    fun hasXclock(rootfs: File): Boolean = File(rootfs, "usr/bin/xclock").isFile

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
}

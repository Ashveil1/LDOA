package dev.phonelinux.proto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Starts the vendored termux-x11 X server in a separate process via
 * app_process + com.termux.x11.CmdEntryPoint, then opens its MainActivity.
 *
 * Socket topology (vendored source, cmdentrypoint.cpp): the server listens
 * on $TMPDIR/.X11-unix/X0. We set TMPDIR to our private files dir so guests
 * reach the same socket through a proot bind:
 *   proot -b <files>/usr/tmp/.X11-unix:/tmp/.X11-unix ... with DISPLAY=:0
 */
object XServer {
    private const val TAG = "PhoneLinuxX"
    private var process: Process? = null

    fun tmpDir(ctx: Context): File = File(ctx.filesDir, "usr/tmp")
    fun socketDir(ctx: Context): File = File(tmpDir(ctx), ".X11-unix")

    @Synchronized
    fun start(ctx: Context) {
        if (process?.isAlive == true) return
        socketDir(ctx).mkdirs()
        val log = File(ctx.filesDir, "xserver.log")
        val pb = ProcessBuilder("/system/bin/app_process", "/", "com.termux.x11.CmdEntryPoint", ":0")
        pb.environment()["CLASSPATH"] = ctx.applicationInfo.sourceDir
        pb.environment()["TMPDIR"] = tmpDir(ctx).absolutePath
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        process = pb.start()
        Log.i(TAG, "X server spawned; TMPDIR=${tmpDir(ctx).absolutePath}; log=$log")
    }

    fun openDesktop(activity: Activity) {
        start(activity.applicationContext)
        activity.startActivity(Intent(activity, com.termux.x11.MainActivity::class.java))
    }
}

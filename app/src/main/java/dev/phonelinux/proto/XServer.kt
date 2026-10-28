package dev.phonelinux.proto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Starts the vendored termux-x11 X server in a separate process via
 * app_process + com.termux.x11.CmdEntryPoint, then opens its MainActivity.
 *
 * Verified topology (vendored source + CI):
 * - The server needs XKB_CONFIG_ROOT with real keymap data (XkbData).
 * - libXlorie.so is preloaded via the XSERVER_LIB env hook (vendored patch
 *   c): under extractNativeLibs=true the classloader-resource path upstream
 *   uses would resolve to a compressed in-APK entry that cannot be dlopened.
 * - Socket: $TMPDIR/.X11-unix/X0; guests reach it through a proot bind.
 */
object XServer {
    private const val TAG = "PhoneLinuxX"
    private var process: Process? = null
    @Volatile var preparing = false
        private set

    fun tmpDir(ctx: Context): File = File(ctx.filesDir, "usr/tmp")
    fun socketDir(ctx: Context): File = File(tmpDir(ctx), ".X11-unix")

    @Synchronized
    fun start(ctx: Context) {
        if (process?.isAlive == true) return
        XkbData.ensure(ctx)
        socketDir(ctx).mkdirs()
        val lib = File(ctx.applicationInfo.nativeLibraryDir, "libXlorie.so")
        check(lib.isFile) { "libXlorie.so missing from nativeLibraryDir" }
        val log = File(ctx.filesDir, "xserver.log")
        val pb = ProcessBuilder("/system/bin/app_process", "/", "com.termux.x11.CmdEntryPoint", ":0")
        val env = pb.environment()
        env["CLASSPATH"] = ctx.applicationInfo.sourceDir
        env["TMPDIR"] = tmpDir(ctx).absolutePath
        env["XKB_CONFIG_ROOT"] = XkbData.root(ctx).absolutePath
        env["XSERVER_LIB"] = lib.absolutePath
        env["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        process = pb.start()
        Log.i(TAG, "X server spawned; log=$log")
    }

    fun openDesktop(activity: Activity, distro: Distro) {
        if (preparing) return
        preparing = true
        thread(name = "xserver-start") {
            val ctx = activity.applicationContext
            try {
                start(ctx)
                // The desktop entry point must launch the DESKTOP the tests
                // prove (jwm + pcmanfm + st), not the old xclock smoke -
                // otherwise the shipped app does less than the CI evidence.
                val installer = DistroInstaller(ctx)
                if (installer.isInstalled(distro)) {
                    val rootfs = installer.rootfsDir(distro)
                    when (distro.pkgMgr) {
                        PkgMgr.APK -> {
                            if (!GuestDesktop.hasDesktopShell(rootfs)) {
                                Log.i(TAG, "desktop shell not provisioned; installing jwm/pcmanfm/st")
                                var add = GuestDesktop.provisionDesktopShell(ctx, rootfs)
                                if (add.exitCode != 0) {
                                    Thread.sleep(5_000)
                                    add = GuestDesktop.provisionDesktopShell(ctx, rootfs)
                                }
                                Log.i(TAG, "desktop shell provision exit=" + add.exitCode)
                            }
                            if (GuestDesktop.hasDesktopShell(rootfs)) {
                                Log.i(TAG, "launching full desktop session")
                                GuestDesktop.launchDesktopSession(ctx, rootfs)
                            } else {
                                Log.e(TAG, "desktop shell unavailable; desktop will be empty")
                            }
                        }
                        PkgMgr.APT -> {
                            // Ubuntu: GNOME (B). Feasibility on our lorie
                            // stack is unproven; guest-gnome.log is the
                            // evidence trail.
                            if (!GuestUbuntu.hasGnome(rootfs)) {
                                Log.i(TAG, "GNOME not provisioned; apt install gnome-shell et al")
                                val add = GuestUbuntu.provisionGnome(ctx, rootfs)
                                Log.i(TAG, "gnome provision exit=" + add.exitCode)
                            }
                            if (GuestUbuntu.hasGnome(rootfs)) {
                                Log.i(TAG, "launching GNOME session")
                                GuestUbuntu.launchGnomeSession(ctx, rootfs)
                            } else {
                                Log.e(TAG, "GNOME unavailable; desktop will be empty")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "X server start failed", t)
            }
            Handler(Looper.getMainLooper()).post {
                preparing = false
                activity.startActivity(Intent(activity, com.termux.x11.MainActivity::class.java))
            }
        }
    }
}

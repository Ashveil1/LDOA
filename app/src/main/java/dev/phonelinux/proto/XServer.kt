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
    @Volatile private var gnomeSession: Pair<String, Process>? = null
    @Volatile var preparing = false
        private set

    /** True when a GNOME desktop session for this distro is already alive:
     *  re-entering the desktop then only brings the X11 activity forward
     *  instead of racing a second gnome-session onto :0. */
    fun gnomeSessionActive(d: Distro): Boolean =
        gnomeSession?.let { it.first == d.id && it.second.isAlive } == true

    /** Single tracked launch path shared by desktop entry and restart tests. */
    @Synchronized
    fun launchOrReuseGnomeSession(ctx: Context, distro: Distro, rootfs: File): Process {
        val current = gnomeSession
        if (current != null && current.first == distro.id && current.second.isAlive) {
            Log.i(TAG, "GNOME session already running - bringing desktop forward")
            return current.second
        }
        Log.i(TAG, "launching GNOME session")
        return GuestUbuntu.launchGnomeSession(ctx, rootfs).also { gnomeSession = distro.id to it }
    }

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

    /** Open the desktop fail-CLOSED: the X11 activity only launches when a
     *  desktop session actually started; every failure path reports through
     *  onError with detail instead of opening a silent black screen
     *  (real-device report 2026-10-29: tap -> screen unchanged, cause
     *  invisible). onStage receives one-line progress for the preparing UI. */
    fun openDesktop(activity: Activity, distro: Distro,
                    onStage: (String) -> Unit = {},
                    onError: (String) -> Unit = {}) {
        if (preparing) {
            onStage("desktop start already in progress - tap ignored")
            return
        }
        preparing = true
        thread(name = "xserver-start") {
            val ctx = activity.applicationContext
            var launched = false
            var failure: String? = null
            try {
                onStage("Starting X server")
                start(ctx)
                val installer = DistroInstaller(ctx)
                if (installer.isInstalled(distro)) {
                    val rootfs = installer.rootfsDir(distro)
                    when (distro.pkgMgr) {
                        PkgMgr.APK -> {
                            if (!GuestDesktop.hasDesktopShell(rootfs)) {
                                onStage("Installing desktop shell (jwm/pcmanfm/st)")
                                var add = GuestDesktop.provisionDesktopShell(ctx, rootfs)
                                if (add.exitCode != 0) {
                                    Thread.sleep(5_000)
                                    add = GuestDesktop.provisionDesktopShell(ctx, rootfs)
                                }
                                Log.i(TAG, "desktop shell provision exit=" + add.exitCode)
                                if (add.exitCode != 0) failure =
                                    "Desktop shell install failed (exit " + add.exitCode + ")\n" + (add.stdout + add.stderr).takeLast(1200)
                            }
                            if (failure == null && GuestDesktop.hasDesktopShell(rootfs)) {
                                onStage("Launching desktop session")
                                val p = GuestDesktop.launchDesktopSession(ctx, rootfs)
                                launched = p.isAlive
                                if (!launched) failure = "Desktop session died immediately after launch"
                            } else if (failure == null) {
                                failure = "Desktop shell unavailable after install; desktop would be empty"
                            }
                        }
                        PkgMgr.APT -> {
                            // Ubuntu: GNOME (B). Feasibility on our lorie
                            // stack is unproven; guest-gnome.log is the
                            // evidence trail.
                            if (!GuestUbuntu.hasGnome(rootfs)) {
                                Log.i(TAG, "GNOME not provisioned; apt install gnome-shell et al")
                                val add = GuestUbuntu.provisionGnome(ctx, rootfs) { line -> onStage(line) }
                                Log.i(TAG, "gnome provision exit=" + add.exitCode)
                                if (add.exitCode != 0) failure =
                                    "GNOME package install failed (exit " + add.exitCode + ")\n" + (add.stdout + add.stderr).takeLast(1200)
                            }
                            if (failure == null && GuestUbuntu.hasGnome(rootfs)) {
                                ctx.getSharedPreferences("plinux_desktop", Context.MODE_PRIVATE)
                                    .edit().putBoolean("ubuntu_desktop_active", true).apply()
                                onStage("Launching GNOME session")
                                val p = launchOrReuseGnomeSession(ctx, distro, rootfs)
                                launched = p.isAlive
                                if (!launched) failure = "GNOME session died immediately after launch; see guest-gnome.log"
                            } else if (failure == null) {
                                failure = "GNOME unavailable after package install; desktop would be empty"
                            }
                        }
                    }
                } else {
                    failure = distro.displayName + " is not installed"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "X server start failed", t)
                failure = "X server failed: " + (t.message ?: t.javaClass.simpleName)
            }
            val f = failure
            Handler(Looper.getMainLooper()).post {
                preparing = false
                if (launched) {
                    activity.startActivity(Intent(activity, com.termux.x11.MainActivity::class.java))
                } else {
                    Log.e(TAG, "desktop open failed-closed: " + f)
                    onError(f ?: "unknown desktop start failure")
                }
            }
        }
    }
}

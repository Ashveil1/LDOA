package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Runs the Termux-built proot binary shipped inside this APK's jniLibs
 * (renamed to lib*.so so Android extracts it to nativeLibraryDir, which is
 * executable even with targetSdk 29+). SPIKE: validates guest exec under
 * modern targetSdk.
 */
object Proot {
    private const val TAG = "PhoneLinuxSpike"

    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val wallMs: Long)

    private fun nativeDir(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir

    fun binariesPresent(ctx: Context): Boolean {
        val nd = File(nativeDir(ctx))
        return listOf("libproot.so", "libproot_loader.so", "libtalloc.so", "libandroid-shmem.so")
            .all { File(nd, it).canExecute() || File(nd, it).exists() }
    }

    fun selfTest(ctx: Context): Result = runHost(ctx, listOf("--version"))

    /** Run a proot command on the host side (no rootfs). */
    fun runHost(ctx: Context, args: List<String>): Result =
        exec(ctx, listOf(prootPath(ctx)) + args)

    /** Run a command inside the installed guest rootfs.
     *  fakeRoot (-0): guest sees uid 0. Required for apt/dpkg (they refuse
     *  to install as non-root); Alpine's apk never needed it, so the flag
     *  is opt-in per call site, not a global behavior change. */
    fun runGuest(ctx: Context, rootfs: File, guestCmd: List<String>, extraBinds: List<Pair<String, String>> = emptyList(), extraEnv: Map<String, String> = emptyMap(), fakeRoot: Boolean = false): Result {
        val args = buildList {
            add(prootPath(ctx))
            add("--link2symlink")
            if (fakeRoot) add("-0")
            add("-r"); add(rootfs.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            for ((host, guest) in extraBinds) { add("-b"); add("$host:$guest") }
            add("-w"); add("/root")
            addAll(guestCmd)
        }
        return exec(ctx, args, extraEnv)
    }

    private fun prootPath(ctx: Context) = File(nativeDir(ctx), "libproot.so").absolutePath

    /** The Termux-built proot has a baked temp dir under com.termux that does
     *  not exist for us; without PROOT_TMP_DIR temp-file creation fails and
     *  guest processes (openbox) die mid-syscall. */
    fun prootTmpDir(ctx: Context): File =
        File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }

    private fun exec(ctx: Context, argv: List<String>, extraEnv: Map<String, String> = emptyMap()): Result {
        val nd = nativeDir(ctx)
        val env = mutableMapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to prootTmpDir(ctx).absolutePath
        )
        env.putAll(extraEnv)
        val start = System.nanoTime()
        return try {
            val pb = ProcessBuilder(argv)
            pb.environment().putAll(env)
            pb.directory(File(ctx.filesDir, "work").apply { mkdirs() })
            val proc = pb.start()
            val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
            val code = proc.waitFor()
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i(TAG, "exec ${argv.first()} exit=$code ${ms}ms")
            Result(code, out, err, ms)
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed", t)
            Result(-1, "", t.stackTraceToString(), (System.nanoTime() - start) / 1_000_000)
        }
    }
}

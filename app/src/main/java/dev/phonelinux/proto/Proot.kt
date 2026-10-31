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

    /** Synthetic exitCode for the bounded-exec timeout path (runGuest/
     *  exec timeoutMs > 0); real guest exits are >= 0 and -1 is the
     *  exec-failed catch path. */
    const val EXIT_TIMED_OUT: Int = -2

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
    fun runGuest(ctx: Context, rootfs: File, guestCmd: List<String>, extraBinds: List<Pair<String, String>> = emptyList(), extraEnv: Map<String, String> = emptyMap(), fakeRoot: Boolean = false, onLine: ((String) -> Unit)? = null, timeoutMs: Long = 0): Result {
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
        return exec(ctx, args, extraEnv, onLine, timeoutMs)
    }

    private fun prootPath(ctx: Context) = File(nativeDir(ctx), "libproot.so").absolutePath

    /** The Termux-built proot has a baked temp dir under com.termux that does
     *  not exist for us; without PROOT_TMP_DIR temp-file creation fails and
     *  guest processes (openbox) die mid-syscall. */
    fun prootTmpDir(ctx: Context): File =
        File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }

    private fun exec(ctx: Context, argv: List<String>, extraEnv: Map<String, String> = emptyMap(), onLine: ((String) -> Unit)? = null, timeoutMs: Long = 0): Result {
        val nd = nativeDir(ctx)
        val env = mutableMapOf(
            "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
            "LD_LIBRARY_PATH" to nd,
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to prootTmpDir(ctx).absolutePath,
            // ProcessBuilder inherits the app process env; Android sets
            // TMPDIR to the app cache dir, which does not exist inside the
            // guest rootfs. apt's ldhash/store methods then abort with
            // "Cannot make temporary directory under /data/user/0/...".
            "TMPDIR" to "/tmp"
        )
        env.putAll(extraEnv)
        val start = System.nanoTime()
        return try {
            val pb = ProcessBuilder(argv)
            pb.environment().putAll(env)
            pb.directory(File(ctx.filesDir, "work").apply { mkdirs() })
            val proc = pb.start()
            // Drain stderr concurrently (apt/dpkg can fill the pipe buffer
            // and deadlock a sequential read); stdout lines stream to the
            // optional progress sink for the provisioning UI.
            val errSb = StringBuilder()
            val errThread = kotlin.concurrent.thread {
                try {
                    proc.errorStream.bufferedReader().forEachLine { errSb.append(it).append('\n') }
                } catch (t: Throwable) {
                    // Drain teardown is normal on the bounded path (the caller
                    // closes the stream after a timeout) and on process-level
                    // signals (EINTR from SIGQUIT/GC suspension). A drain must
                    // NEVER die with an uncaught exception: run 41179905458
                    // crashed the whole instrumentation process mid-arm when
                    // both drains threw InterruptedIOException unhandled.
                    Log.w(TAG, "proot stderr drain ended: " + t.javaClass.simpleName + ": " + t.message)
                }
            }
            val outSb = StringBuilder()
            // timeoutMs > 0: drain stdout on its own thread so the caller can
            // bound the wait. WEDGE EVIDENCE (run 41176270704): proot stays
            // alive while any backgrounded tracee lives, so stdout never
            // EOFs for fire-and-forget guest launches; an on-thread drain
            // then blocks the caller for the whole JUnit timeout (nautilus
            // pid 13954 ran 06:43:17-06:57:36, the full blocked window).
            val outThread: Thread? = if (timeoutMs > 0) kotlin.concurrent.thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine {
                        outSb.append(it).append('\n')
                        onLine?.invoke(it)
                    }
                } catch (t: Throwable) {
                    // See the stderr drain above: bounded-wait teardown closes
                    // this stream and signals interrupt blocking reads; an
                    // uncaught drain exception kills the caller's process.
                    Log.w(TAG, "proot stdout drain ended: " + t.javaClass.simpleName + ": " + t.message)
                }
            } else null
            val code: Int
            if (timeoutMs > 0) {
                val exited = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (exited) {
                    code = proc.exitValue()
                } else {
                    // SIGKILL proot directly: backgrounded tracees are
                    // orphaned (kept alive) rather than torn down with it -
                    // intentional for fire-and-forget guest launches; the
                    // Android phantom-process reaper bounds them at app
                    // death. A SIGTERM-first path risks proot taking the
                    // launched app down and hiding that it ever started.
                    proc.destroyForcibly()
                    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    code = EXIT_TIMED_OUT
                    errSb.append("TIMED_OUT after ").append(timeoutMs).append(" ms; proot killed, backgrounded tracees orphaned\n")
                }
                outThread!!.join(10_000)
                if (outThread.isAlive) {
                    // An unredirected backgrounded child inherited the pipe,
                    // so SIGKILL-ing proot did not EOF stdout: close our end
                    // to unblock the drain thread instead of leaking it.
                    try { proc.inputStream.close() } catch (_: Exception) {}
                    outThread.join(2_000)
                }
            } else {
                proc.inputStream.bufferedReader().forEachLine {
                    outSb.append(it).append('\n')
                    onLine?.invoke(it)
                }
                code = proc.waitFor()
            }
            errThread.join(10_000)
            if (errThread.isAlive) {
                try { proc.errorStream.close() } catch (_: Exception) {}
                errThread.join(2_000)
            }
            val out = outSb.toString()
            val err = errSb.toString()
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i(TAG, "exec ${argv.first()} exit=$code ${ms}ms")
            Result(code, out, err, ms)
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed", t)
            Result(-1, "", t.stackTraceToString(), (System.nanoTime() - start) / 1_000_000)
        }
    }
}

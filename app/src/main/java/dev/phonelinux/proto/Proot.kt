package dev.phonelinux.proto

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Runs the Termux-built proot binary shipped under assets/proot/<abi>/.
 *
 * The payload is extracted to filesDir/proot/<abi>/ on first use and chmod'd
 * executable (the Termux delivery model: apps may exec their own private
 * files, CI-verified on API 34). Assets delivery (instead of jniLibs
 * lib*.so + extractNativeLibs) keeps modern APK packaging, which the
 * vendored :lorie X server requires to dlopen libXlorie from inside the APK.
 */
object Proot {
    private const val TAG = "PhoneLinuxSpike"
    private const val PAYLOAD_VERSION = "5.1.107.101-termux1"

    private val FILES = listOf("proot", "proot-loader", "proot-loader32", "libtalloc.so", "libandroid-shmem.so")

    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val wallMs: Long)

    private fun abi(): String = Build.SUPPORTED_ABIS.first { it == "arm64-v8a" || it == "x86_64" }

    fun binDir(ctx: Context): File = File(ctx.filesDir, "proot/${abi()}")

    /** Idempotent extract of the proot payload to private storage with exec bits. */
    @Synchronized
    fun ensureExtracted(ctx: Context): File {
        val dir = binDir(ctx)
        val marker = File(dir, ".version")
        if (!marker.isFile || marker.readText().trim() != PAYLOAD_VERSION || !FILES.all { File(dir, it).canExecute() }) {
            dir.mkdirs()
            for (name in FILES) {
                ctx.assets.open("proot/${abi()}/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
                if (!File(dir, name).setExecutable(true, true)) error("chmod failed for $name")
            }
            marker.writeText(PAYLOAD_VERSION + "\n")
            Log.i(TAG, "proot payload extracted to $dir")
        }
        return dir
    }

    fun binariesPresent(ctx: Context): Boolean =
        FILES.all { File(ensureExtracted(ctx), it).canExecute() }

    fun selfTest(ctx: Context): Result = runHost(ctx, listOf("--version"))

    /** Run a proot command on the host side (no rootfs). */
    fun runHost(ctx: Context, args: List<String>): Result =
        exec(ctx, listOf(File(ensureExtracted(ctx), "proot").absolutePath) + args)

    /** Run a command inside the installed guest rootfs. */
    fun runGuest(ctx: Context, rootfs: File, guestCmd: List<String>, extraBinds: List<Pair<String, String>> = emptyList(), extraEnv: Map<String, String> = emptyMap()): Result {
        val args = buildList {
            add(File(ensureExtracted(ctx), "proot").absolutePath)
            add("--link2symlink")
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

    private fun exec(ctx: Context, argv: List<String>, extraEnv: Map<String, String> = emptyMap()): Result {
        val dir = ensureExtracted(ctx)
        val env = mutableMapOf(
            "PROOT_LOADER" to File(dir, "proot-loader").absolutePath,
            "PROOT_LOADER_32" to File(dir, "proot-loader32").absolutePath,
            "LD_LIBRARY_PATH" to dir.absolutePath,
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
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

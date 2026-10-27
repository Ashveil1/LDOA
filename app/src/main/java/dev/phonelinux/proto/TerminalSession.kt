package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A persistent guest shell: one proot process running /bin/sh inside the
 * installed rootfs, stdout+stderr merged into a scrollback buffer.
 * Lines written via [send] go to the shell's stdin.
 */
class TerminalSession(
    private val ctx: Context,
    private val rootfs: File,
    private val onUpdate: () -> Unit
) {
    private val TAG = "PhoneLinuxTerm"
    private var proc: Process? = null
    private val buf = StringBuilder()
    private val running = AtomicBoolean(false)
    private val lock = Any()

    fun start(): Boolean {
        if (running.get()) return true
        val nd = Proot.ensureExtracted(ctx)
        val argv = listOf(
            File(nd, "proot").absolutePath,
            "--link2symlink",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/bin/sh"
        )
        return try {
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            pb.environment().putAll(mapOf(
                "PROOT_LOADER" to File(nd, "proot-loader").absolutePath,
                "PROOT_LOADER_32" to File(nd, "proot-loader32").absolutePath,
                "LD_LIBRARY_PATH" to nd.absolutePath,
                "HOME" to "/root",
                "TERM" to "xterm-256color",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "PS1" to "\\$ "
            ))
            pb.directory(File(ctx.filesDir, "work").apply { mkdirs() })
            val p = pb.start()
            proc = p
            running.set(true)
            thread(isDaemon = true, name = "term-reader") {
                try {
                    val input = p.inputStream
                    val chunk = ByteArray(4096)
                    while (running.get()) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        synchronized(lock) { buf.append(String(chunk, 0, n, Charsets.UTF_8)) }
                        onUpdate()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "reader ended: ${t.message}")
                } finally {
                    running.set(false); onUpdate()
                }
            }
            Log.i(TAG, "session started pid=${p}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            synchronized(lock) { buf.append("[session failed: ${t.message}]\n") }
            false
        }
    }

    fun send(line: String) {
        val p = proc ?: return
        if (!running.get()) return
        try {
            p.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.message}")
        }
    }

    fun snapshot(): String = synchronized(lock) { buf.toString() }

    fun isRunning(): Boolean = running.get()

    fun close() {
        running.set(false)
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
    }
}

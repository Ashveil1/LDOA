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
    private val sessionLog by lazy { File(ctx.filesDir, "terminal-session.log") }
    private val running = AtomicBoolean(false)
    private val lock = Any()
    // Bounded UI scrollback: unbounded growth made every snapshot() + Compose
    // re-render quadratic in total output (measured host-side). The full
    // stream still lands in sessionLog; the UI buffer keeps a tail only.
    private val maxBufBytes = 512 * 1024
    private val trimToBytes = 384 * 1024
    // Callback throttle: coalesce per-chunk onUpdate storms (one per 4KB read)
    // to at most one per 100ms, with a trailing fire so the last chunk of a
    // burst is never lost.
    private val notifyLock = Any()
    private var notifyPending = false
    private var lastNotify = 0L
    // Buffered session log: appendText per 4KB chunk is an open/write/close
    // syscall storm; batch to 16KB flushes, flushed again on reader end/close.
    private val logBuf = StringBuilder()

    // Extracted for testability: append + bounded-tail trim, one lock hold.
    private fun appendToBuf(text: String) {
        synchronized(lock) {
            buf.append(text)
            if (buf.length > maxBufBytes) {
                var cut = buf.indexOf('\n', buf.length - trimToBytes)
                cut = if (cut < 0) buf.length - trimToBytes else cut + 1
                buf.delete(0, cut)
                buf.insert(0, "[...older output truncated - full stream in terminal-session.log...]\n")
            }
        }
    }

    private fun notifyUpdate() {
        val now = System.currentTimeMillis()
        synchronized(notifyLock) {
            if (now - lastNotify < 100) {
                if (notifyPending) return
                notifyPending = true
                thread(isDaemon = true, name = "term-notify") {
                    Thread.sleep(100)
                    synchronized(notifyLock) {
                        notifyPending = false
                        lastNotify = System.currentTimeMillis()
                    }
                    try { onUpdate() } catch (_: Throwable) {}
                }
                return
            }
            lastNotify = now
        }
        try { onUpdate() } catch (_: Throwable) {}
    }

    private fun logAppend(text: String) {
        synchronized(lock) {
            logBuf.append(text)
            if (logBuf.length >= 16 * 1024) {
                try { sessionLog.appendText(logBuf.toString()) } catch (_: Throwable) {}
                logBuf.setLength(0)
            }
        }
    }

    private fun logFlush() {
        synchronized(lock) {
            if (logBuf.isNotEmpty()) {
                try { sessionLog.appendText(logBuf.toString()) } catch (_: Throwable) {}
                logBuf.setLength(0)
            }
        }
    }

    fun start(): Boolean {
        if (running.get()) return true
        val nd = ctx.applicationInfo.nativeLibraryDir
        val argv = listOf(
            File(nd, "libproot.so").absolutePath,
            "--link2symlink",
            // Fake root, aligned with provisioning and the desktop session
            // (both already run -0 on this rootfs): apt/dpkg refuse a
            // non-root euid, and this shell is the user's package-install
            // surface. Field evidence 2026-10-30: without it, apt downloads
            // then dpkg denies with "requested operation requires superuser
            // privilege" (real uid = the app uid).
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            // -i: no pty is available through ProcessBuilder, and a
            // non-interactive busybox sh never prints a prompt, leaving the
            // terminal looking dead. -i gives prompt + echo; it prints one
            // "can't access tty" job-control notice, which is authentic.
            "/bin/sh", "-i"
        )
        return try {
            val tmp = File(ctx.filesDir, "usr/tmp").apply { mkdirs() }
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            pb.environment().putAll(mapOf(
                "PROOT_TMP_DIR" to tmp.absolutePath,
                "TMPDIR" to tmp.absolutePath,
                "PROOT_LOADER" to File(nd, "libproot_loader.so").absolutePath,
                "PROOT_LOADER_32" to File(nd, "libproot_loader32.so").absolutePath,
                "LD_LIBRARY_PATH" to nd,
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
                        appendToBuf(String(chunk, 0, n, Charsets.UTF_8))
                        logAppend("READ " + n + " buf=" + buf.length + "\n")
                        notifyUpdate()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "reader ended: ${t.message}")
            sessionLog.appendText("READER ENDED: ${t.message}\n")
                } finally {
                    logFlush()
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
            logAppend("SENT: " + line + "\n")
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
        logFlush()
    }
}

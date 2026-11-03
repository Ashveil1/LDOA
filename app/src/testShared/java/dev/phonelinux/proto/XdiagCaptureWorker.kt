package dev.phonelinux.proto

/** Diagnostic-only lifecycle seams. A timed-out daemon is NOT declared dead. */
internal object XdiagCaptureWorker {
    fun start(capture: XdiagByteCapture, body: () -> Unit,
        starter: (() -> Unit) -> Thread = { task -> Thread(task).apply { isDaemon = true; start() } },
        cleanup: () -> Unit): Thread {
        try { return starter(body) }
        catch (primary: Throwable) {
            capture.wrapperFailure(primary)
            try { cleanup() } catch (t: Throwable) { if (t !== primary) primary.addSuppressed(t) }
            capture.joined(null, false)
            throw primary
        }
    }

    /** Stop/reap is attempted BEFORE close and cannot be blocked by close.
     * Close runs on a daemon, bounded caller join; errors/live state returned.
     * No claim that either close or drain worker died after the join timeout. */
    fun cleanupAndClose(stop: () -> Unit, close: () -> Unit, joinMs: Long = 100): String {
        require(joinMs > 0) { "close join must be positive; zero means indefinite" }
        val notes = StringBuilder()
        try { stop() } catch (t: Throwable) { notes.append("stop_error: ").append(t.javaClass.name).append('\n') }
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        try {
            val worker = Thread {
                try { close() } catch (t: Throwable) { error.set(t) }
            }.apply { isDaemon = true; start() }
            worker.join(joinMs)
            notes.append("close_worker_alive: ").append(worker.isAlive).append('\n')
            error.get()?.let { notes.append("close_error: ").append(it.javaClass.name).append('\n') }
        } catch (t: Throwable) { notes.append("close_start_or_join_error: ").append(t.javaClass.name).append('\n') }
        return notes.toString()
    }
}

package dev.phonelinux.proto

import java.io.InputStream

/** Opt-in inventory transport. Merged stdout/stderr is raw bytes, never lines.
 * Budget is coverage, not an assumed guest DB maximum: overflow is UNKNOWN. */
internal class XdiagByteCapture(
    private val deadlineNs: Long,
    private val clock: () -> Long = System::nanoTime,
    private val cap: Int = CAP_BYTES
) {
    companion object { const val CAP_BYTES = 256 * 1024 }
    private val raw = java.io.ByteArrayOutputStream()
    private var eof = false
    private var overflow = false
    private var deadline = false
    private var error: String? = null
    private var joined = false
    private var live = false
    private var rc: Int? = null
    private var frozen: Snapshot? = null
    data class Snapshot(val bytes: ByteArray, val metadata: String, val complete: Boolean)

    init { require(cap > 0) }
    fun drain(input: InputStream) {
        val buf = ByteArray(4096)
        try {
            while (true) {
                synchronized(this) {
                    if (frozen != null) return
                    if (clock() >= deadlineNs) { deadline = true; return }
                }
                // Freeze can race this read: at most one in-flight read may
                // finish afterward; its bytes/errors are ignored when frozen.
                val n = input.read(buf)
                synchronized(this) {
                    if (frozen != null) return
                    if (clock() >= deadlineNs) deadline = true
                    if (n < 0) { eof = true; return }
                    if (n == 0) { error = "zero-progress read - UNKNOWN"; return }
                    val keep = minOf(n, cap - raw.size())
                    if (keep > 0) raw.write(buf, 0, keep)
                    if (keep < n) overflow = true
                    // Stop IO immediately; caller polls failure and executes
                    // its existing bounded process stop/reap ladder.
                    if (deadline || overflow) return
                }
            }
        } catch (t: Throwable) {
            synchronized(this) { if (frozen == null) error = t.javaClass.name + ": " + (t.message ?: "").take(160) }
        }
    }
    @Synchronized fun joined(processRc: Int?, drainAlive: Boolean) {
        rc = processRc; joined = true; live = drainAlive
        frozen = snapshotNow()
    }
    @Synchronized fun failed(): Boolean = overflow || deadline || error != null
    private fun snapshotNow(): Snapshot = Snapshot(raw.toByteArray(),
        "stream: merged stdout+stderr raw bytes\ncap_bytes: $cap\nretained_bytes: ${raw.size()}\n" +
        "eof: $eof\noverflow: $overflow\ndeadline: $deadline\nerror: ${error ?: "none"}\n" +
        "joined: $joined\ndrain_alive_at_join: $live\nprocess_rc: ${rc ?: "unavailable"}\n",
        joined && !live && eof && !overflow && !deadline && error == null)
    @Synchronized fun snapshot(): Snapshot = (frozen ?: snapshotNow()).let { it.copy(bytes = it.bytes.copyOf()) }
    fun decode(): String {
        requireComplete()
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(snapshot().bytes)).toString()
    }
    @Synchronized fun wrapperFailure(t: Throwable) {
        if (frozen == null && error == null) error = "wrapper: " + t.javaClass.name
    }
    fun bytes(): ByteArray = snapshot().bytes
    fun metadata(): String = snapshot().metadata
    fun requireComplete() {
        val s = snapshot()
        require(s.complete) { "inventory byte capture INCOMPLETE - UNKNOWN\n" + s.metadata }
    }
}

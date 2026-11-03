package dev.phonelinux.proto

import java.io.File

/**
 * Pre-spawn filesystem preconditions for the xdiag diagnostic proxy.
 *
 * The proot bind sources must exist before the diagnostic spawn: proot
 * cannot create missing bind sources and drops them with a warning only,
 * after which the in-guest bind target is absent. Diagnostic-only: no
 * deletion, no chmod, existing entries untouched and recorded.
 *
 * Fail-closed rule: the safety verdict (collision check) always runs on the
 * COMPLETE entry listing; an unreadable listing is an error, never "no
 * entries". Evidence display is bounded separately and says so.
 */
object XdiagSpawnPrep {

    /** Result of a socket-dir inspection. */
    class SocketDirCheck(
        /** Complete sorted entry list - the only basis for the verdict. */
        val entries: List<String>,
        /** :0 listen-socket collisions found in the COMPLETE list. */
        val collisions: List<String>,
        /** Bounded prefix of [entries] for evidence display only. */
        val shown: List<String>,
        /** True when [shown] is a strict prefix (display truncated). */
        val truncated: Boolean)

    /**
     * Ensure [dir] exists as a directory, creating missing parents.
     *
     * @return null on success; a short failure description otherwise
     *     (never deletes or re-permissions anything, fail-closed).
     */
    fun ensureDirectoryChecked(dir: File): String? {
        if (dir.isDirectory) return null
        if (dir.exists()) return "exists but is not a directory: " + dir.absolutePath
        if (!dir.mkdirs()) return "mkdirs failed: " + dir.absolutePath
        return if (dir.isDirectory) null
            else "post-mkdirs probe: still not a directory: " + dir.absolutePath
    }

    /**
     * Read and evaluate the entries of [dir].
     *
     * @return null when the listing cannot be read (unreadable is an ERROR,
     *     never an empty listing); otherwise the complete sorted entries,
     *     the :0 collisions computed over ALL of them, and a bounded display
     *     prefix with its truncation flag.
     */
    fun inspectEntries(dir: File, cap: Int = 100): SocketDirCheck? {
        val all = dir.list() ?: return null
        val sorted = all.sorted()
        val shown = sorted.take(cap)
        return SocketDirCheck(
            entries = sorted,
            collisions = unownedProxyCollisions(sorted),
            shown = shown,
            truncated = shown.size < sorted.size)
    }

    /**
     * Names in [entries] that would collide with the diagnostic proxy's own
     * :0 listen socket (X0, X0-lock, .X0*). A preexisting collision means a
     * server or proxy WAS here; its owner is unprovable, so the caller must
     * refuse rather than let anything delete or shadow it (same rule as the
     * E1 comparison test's stale-socket check).
     */
    fun unownedProxyCollisions(entries: List<String>): List<String> =
        entries.filter { it == "X0" || it == "X0-lock" || it.startsWith(".X0") }
}

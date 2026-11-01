package dev.phonelinux.proto

import java.io.File

/** Shared helpers for the VirGL spike tests (VirglServerSmokeTest,
 *  VirglProbeTest). Host-side /proc identity checks mirror the audited
 *  XServer contract: pid+starttime+uid, zombies count as gone. */
internal object VirglTestCommon {

    fun childrenOf(pid: Int): List<XServer.PIdent> =
        try {
            File("/proc/" + pid + "/task/" + pid + "/children").readText()
                .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull() }.mapNotNull { identOf(it) }
        } catch (t: Throwable) { emptyList() }

    fun identOf(pid: Int): XServer.PIdent? {
        val stat = try { File("/proc/" + pid + "/stat").readText() } catch (t: Throwable) { return null }
        val fields = try { stat.substringAfterLast(')').trim().split(Regex("\\s+")) } catch (t: Throwable) { return null }
        if (fields.size < 20 || fields[0] == "Z") return null
        val uid = try { android.system.Os.stat("/proc/" + pid).st_uid } catch (t: Throwable) { return null }
        if (uid != android.os.Process.myUid()) return null
        return XServer.PIdent(pid, fields[19], uid)
    }

    /** Guest presence WITHOUT host-following: a guest-ABSOLUTE symlink
     *  (-> /usr/lib/...) would resolve against the ANDROID host fs and
     *  falsely pass/fail; the link itself counts as a present entry. */
    fun guestPresent(f: File): Boolean =
        java.nio.file.Files.isSymbolicLink(f.toPath()) || f.isFile

    /** Resolve a guest symlink chain WITHOUT escaping the rootfs:
     *  absolute targets rebase under rootfs; relative targets resolve
     *  beside the link; bounded at 8 hops. */
    fun resolveGuest(rootfs: File, f: File): File {
        val rootCanon = rootfs.canonicalPath
        var cur = f; var depth = 0
        while (depth++ < 8) {
            val target = try {
                java.nio.file.Files.readSymbolicLink(cur.toPath())
            } catch (e: Exception) { null } ?: break
            val next = if (target.isAbsolute) File(rootfs, target.toString().removePrefix("/"))
                       else File(cur.parentFile, target.toString())
            // Never follow a link OUT of the rootfs: a relative target
            // with enough ".." segments could escape onto the host.
            if (!next.canonicalPath.startsWith(rootCanon + File.separator)) break
            cur = next
        }
        return cur
    }

    fun aliveAs(id: XServer.PIdent): Boolean {
        val cur = identOf(id.pid) ?: return false
        return cur.start == id.start && cur.uid == id.uid
    }

    /** Full-evidence probe report: the exact command, env, binds, exit
     *  code, wall time, and COMPLETE stdout+stderr. Written to app files
     *  so the lane can `run-as cat` it - nothing lives only in an
     *  assertion message or a logcat line. */
    fun writeProbeReport(file: File, title: String, command: List<String>,
                         env: Map<String, String>, binds: List<Pair<String, String>>,
                         result: Proot.Result?, extra: String = "") {
        val sb = StringBuilder()
        sb.append("== ").append(title).append(" ==\n")
        sb.append("command: ").append(command.joinToString(" ")).append('\n')
        sb.append("env: ").append(env).append('\n')
        sb.append("binds: ").append(binds).append('\n')
        if (result != null) {
            sb.append("exitCode: ").append(result.exitCode).append(" wallMs: ").append(result.wallMs).append('\n')
            sb.append("--- stdout ---\n").append(result.stdout)
            sb.append("--- stderr ---\n").append(result.stderr)
        } else {
            sb.append("runGuest returned no result (threw)\n")
        }
        if (extra.isNotEmpty()) sb.append("--- extra ---\n").append(extra).append('\n')
        file.writeText(sb.toString())
    }

    fun rendererOf(report: Proot.Result): String? =
        report.stdout.lineSequence().firstOrNull { it.startsWith("GL-RENDERER:") }
            ?.substringAfter("GL-RENDERER:")?.trim()
}

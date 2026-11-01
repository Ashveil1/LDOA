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

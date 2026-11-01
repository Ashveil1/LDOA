package dev.phonelinux.proto

import android.os.Process
import java.io.File

/**
 * SessionCensus v2 - READ-ONLY startup-definition census for the M3
 * phantom-diet investigation (authorized bounded instrumentation).
 *
 * Survival design: the file is written INCREMENTALLY, section by
 * section, BEFORE session launch; a mid-census failure leaves the
 * partial file with an explicit TRUNCATED marker (partial evidence is
 * labeled, never lost). The post-launch ancestry snapshot lands seconds
 * after spawn (observed trim: 27.3s post-launch, n=1 - descriptive).
 *
 * Resolution honesty: for every session name the census lists ALL
 * candidate files in the documented XDG search order and marks the
 * first existing one "effective-by-documented-first-hit" per the noble
 * gnome-session(1) manpage - that is a DOCUMENTED rule, not observed
 * proof of which file gnome-session actually loaded. The post-launch
 * ancestry snapshot separately records the gnome-session-binary pid's
 * OBSERVED XDG environ when readable, which can contradict the assumed
 * search order (both are preserved; a contradiction is reported, never
 * silently resolved).
 *
 * Read failures are evidence: every candidate location records EXISTS
 * (verbatim content), ABSENT, or READ-FAILED(with error). Enumeration
 * failures and uid-read failures in the ancestry scan are COUNTED as
 * UNKNOWN buckets, never silently skipped.
 *
 * It MUTATES NOTHING: no component removal, no autostart masking, no
 * session override, no settings writes. Pure helpers are mirror-tested
 * (kotlinc unavailable locally - MIRROR-ONLY until CI compiles them).
 */
object SessionCensus {

    /** The .session search dirs in documented order (noble
     *  gnome-session(1)): $XDG_CONFIG_HOME/gnome-session/sessions, each
     *  $XDG_CONFIG_DIRS entry, each $XDG_DATA_DIRS entry. null/blank
     *  variables take their spec defaults (config home "$home/.config",
     *  config dirs "/etc/xdg", data dirs "/usr/local/share:/usr/share"). */
    fun sessionSearchDirs(home: String?, configHome: String?, configDirs: String?, dataDirs: String?): List<String> {
        val ch = if (configHome.isNullOrBlank()) (home ?: "/root") + "/.config" else configHome
        val cd = if (configDirs.isNullOrBlank()) "/etc/xdg" else configDirs
        val dd = if (dataDirs.isNullOrBlank()) "/usr/local/share:/usr/share" else dataDirs
        val out = ArrayList<String>()
        out.add(ch + "/gnome-session/sessions")
        for (d in cd.split(':')) if (d.isNotBlank()) out.add(d.trim() + "/gnome-session/sessions")
        for (d in dd.split(':')) if (d.isNotBlank()) out.add(d.trim() + "/gnome-session/sessions")
        return out
    }

    /** INI-ish group-scoped key reader (see v1 contract): keys inside
     *  [group] only; later duplicates win and are COUNTED. */
    fun groupKeys(content: String, group: String, keys: Set<String>): Pair<Map<String, String>, Int> {
        val found = LinkedHashMap<String, String>()
        var dupes = 0
        var inGroup = false
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inGroup = line == "[" + group + "]"
                continue
            }
            if (!inGroup || line.startsWith("#") || line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim()
            if (k in keys) {
                if (found.containsKey(k)) dupes++
                found[k] = line.substring(eq + 1).trim()
            }
        }
        return Pair(found, dupes)
    }

    fun requiredComponents(sessionContent: String): Pair<List<String>?, Int> {
        val (m, dupes) = groupKeys(sessionContent, "GNOME Session", setOf("RequiredComponents"))
        val v = m["RequiredComponents"] ?: return Pair(null, dupes)
        return Pair(v.split(';').map { it.trim() }.filter { it.isNotEmpty() }, dupes)
    }

    private fun section(out: File, title: String, body: (StringBuilder) -> Unit) {
        val sb = StringBuilder()
        sb.append("\n--- ").append(title).append(" ---\n")
        try {
            body(sb)
        } catch (t: Throwable) {
            sb.append("!! SECTION TRUNCATED by ").append(t.javaClass.simpleName)
                .append(": ").append(t.message ?: "?")
                .append(" - content above is PARTIAL, preserved as evidence\n")
        }
        out.appendText(sb.toString())
    }

    /** Full pre-launch census, written incrementally to [out] (which the
     *  caller has already deleted, so no stale census survives). [shell]
     *  runs ONE adb-shell command (null = unavailable). [launchedSession]
     *  is the session name the product launch script passes (currently
     *  "ubuntu" per GuestUbuntu.kt) - recorded as a STATIC-code claim,
     *  not an observed invocation. */
    fun writeCensus(rootfs: File, out: File, launchedSession: String, shell: (String) -> String?) {
        out.writeText("=== LDOA session census v2 (READ-ONLY, incremental) wall=" +
            System.currentTimeMillis() + " ===\n" +
            "purpose: M3 phantom-diet grounding - INSTALLED session definitions, activation" +
            " paths, phantom-killer config. Candidate files vs proven effective resolution are" +
            " distinguished; read failures are recorded as evidence.\n")

        // Launch env grounding: the product launch script's export line
        // (GuestUbuntu.kt, observed in code review) sets DISPLAY/HOME/
        // XDG_RUNTIME_DIR/XDG_CURRENT_DESKTOP/XDG_SESSION_TYPE/LANG and
        // NO XDG_CONFIG_HOME / XDG_CONFIG_DIRS / XDG_DATA_DIRS, so the
        // spec defaults apply - ASSUMED here, correctable by the
        // post-launch observed environ (ancestry snapshot).
        val assumedDirs = sessionSearchDirs("/root", null, null, null)
        section(out, "assumed XDG session search order (defaults; launch script exports no" +
            " XDG_*_DIRS - STATIC-code observation, corrected by observed environ post-launch)") { sb ->
            for (d in assumedDirs) sb.append("  ").append(d).append('\n')
        }

        // Every session-name candidate across the search order.
        // Tri-state per location: EXISTS / ABSENT (parent dir listed,
        // name not in it) / UNKNOWN (parent unreadable - never asserted
        // as ABSENT).
        val names = LinkedHashSet<String>()
        names.add(launchedSession)
        for (rel in assumedDirs) {
            val dir = File(rootfs, rel)
            val files = try { dir.listFiles { f -> f.isFile && f.name.endsWith(".session") } }
                        catch (t: Throwable) { null }
            if (files == null) continue
            for (f in files) names.add(f.name.removeSuffix(".session"))
        }
        section(out, "session-file candidates per name (search order; first EXISTS is a" +
            " PROVISIONAL-CANDIDATE under the assumed env - NOT confirmed effective until the" +
            " observed gnome-session environ agrees; observed load never directly proven)") { sb ->
            for (n in names) {
                sb.append("## session name: ").append(n).append('\n')
                var candidateMarked = false
                for (rel in assumedDirs) {
                    val parent = File(rootfs, rel)
                    val parentListed = try { parent.list() != null } catch (t: Throwable) { false }
                    val f = File(rootfs, rel + "/" + n + ".session")
                    val state = when {
                        !parentListed -> "UNKNOWN (parent dir unreadable - NOT evidence of absence)"
                        !f.exists() -> "ABSENT (parent listed, name not present)"
                        !f.isFile -> "NOT-A-FILE"
                        else -> "EXISTS"
                    }
                    sb.append("  ").append(rel).append("/").append(n).append(".session: ").append(state)
                    if (state == "EXISTS" && !candidateMarked) {
                        sb.append("  <-- PROVISIONAL-CANDIDATE (documented first-hit under ASSUMED" +
                            " env; pending observed-env agreement)")
                        candidateMarked = true
                    }
                    sb.append('\n')
                    if (state == "EXISTS") {
                        val content = try { f.readText() } catch (t: Throwable) { null }
                        if (content == null) {
                            sb.append("    READ-FAILED (content unavailable)\n")
                        } else {
                            sb.append("    VERBATIM:\n")
                            for (l in content.trimEnd().lines()) sb.append("    | ").append(l).append('\n')
                            val (comps, dupes) = requiredComponents(content)
                            sb.append("    RequiredComponents parsed: ")
                                .append(comps?.joinToString(";") ?: "<key absent>")
                            if (dupes > 0) sb.append(" (DUPLICATE keys: ").append(dupes).append(" - verify by hand)")
                            sb.append('\n')
                        }
                    }
                }
            }
        }

        // Required-component resolution for the launched session's
        // effective file, across component .desktop search locations
        // (same first-hit uncertainty as session files).
        val compDirs = listOf(
            "root/.local/share/applications",
            "root/.config/autostart",
            "usr/share/applications",
            "etc/xdg/autostart",
            "usr/share/gnome/autostart")
        section(out, "required-component resolution for launched session '" + launchedSession +
            "' (PROVISIONAL candidates under assumed env; first-hit documented rule," +
            " observed load NOT proven)") { sb ->
            var effContent: String? = null
            for (rel in assumedDirs) {
                val f = File(rootfs, rel + "/" + launchedSession + ".session")
                if (f.isFile) { effContent = try { f.readText() } catch (t: Throwable) { null }; break }
            }
            if (effContent == null) {
                sb.append("no effective session file for '").append(launchedSession)
                    .append("' (or unreadable) - component set UNKNOWN\n")
            } else {
                val (comps, _) = requiredComponents(effContent)
                if (comps == null) {
                    sb.append("effective file carries NO RequiredComponents key\n")
                } else {
                    val want = setOf("Name", "Exec", "Hidden", "X-GNOME-HiddenUnderSystemd",
                        "X-GNOME-Autostart-Phase", "OnlyShowIn", "TryExec")
                    for (c in comps) {
                        var hit: Pair<String, Map<String, String>>? = null
                        for (rel in compDirs) {
                            val f = File(rootfs, rel + "/" + c + ".desktop")
                            if (f.isFile) {
                                val (m, _) = groupKeys(try { f.readText() } catch (t: Throwable) { "" },
                                    "Desktop Entry", want)
                                hit = Pair(rel + "/" + c + ".desktop", m)
                                break
                            }
                        }
                        if (hit == null) {
                            sb.append("component ").append(c).append(": UNRESOLVED in candidate dirs\n")
                        } else {
                            sb.append("component ").append(c).append(" -> ").append(hit.first)
                                .append(" (first-hit)\n")
                            for ((k, v) in hit.second) sb.append("    ").append(k).append('=').append(v).append('\n')
                        }
                    }
                }
            }
        }

        // Autostart inventories INCLUDING user-level masks.
        section(out, "autostart inventories (system /etc/xdg/autostart + user /root/.config/autostart;" +
            " Hidden=true entries are MASKS)") { sb ->
            for (rel in listOf("etc/xdg/autostart", "root/.config/autostart")) {
                val dir = File(rootfs, rel)
                val files = try { dir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") } }
                            catch (t: Throwable) { null }
                if (files == null) { sb.append(rel).append(": ABSENT-or-UNREADABLE\n"); continue }
                sb.append(rel).append(": count=").append(files.size).append('\n')
                for (a in files.sortedBy { it.name }) {
                    val (m, _) = groupKeys(try { a.readText() } catch (t: Throwable) { "" }, "Desktop Entry",
                        setOf("Name", "Exec", "Hidden", "X-GNOME-Autostart-Phase", "OnlyShowIn",
                            "X-GNOME-HiddenUnderSystemd", "NoDisplay"))
                    sb.append("  ").append(a.name).append(" | name=").append(m["Name"] ?: "?")
                        .append(" exec=").append((m["Exec"] ?: "?").take(100))
                        .append(" phase=").append(m["X-GNOME-Autostart-Phase"] ?: "-")
                        .append(" hidden=").append(m["Hidden"] ?: "-")
                        .append(" onlyshowin=").append(m["OnlyShowIn"] ?: "-")
                        .append(" systemd-hidden=").append(m["X-GNOME-HiddenUnderSystemd"] ?: "-")
                        .append('\n')
                }
            }
        }

        // Session-bus + SYSTEM-bus service activation definitions.
        section(out, "D-BUS SESSION services (usr/share/dbus-1/services;" +
            " org.gnome.*, org.gtk.*, org.freedesktop.portal.*)") { sb ->
            dumpServices(rootfs, "usr/share/dbus-1/services",
                setOf("org.gnome.", "org.gtk.", "org.freedesktop.portal."), sb)
        }
        section(out, "D-BUS SYSTEM services (usr/share/dbus-1/system-services;" +
            " UPower + portal/gvfs-relevant) - upowerd and friends activate HERE, on the SYSTEM bus") { sb ->
            dumpServices(rootfs, "usr/share/dbus-1/system-services",
                setOf("org.freedesktop.UPower", "org.gnome.", "org.gtk.", "org.freedesktop.portal."), sb)
        }

        // Portal definitions.
        section(out, "xdg-desktop-portal portal definitions (usr/share/xdg-desktop-portal/portals)") { sb ->
            val dir = File(rootfs, "usr/share/xdg-desktop-portal/portals")
            val files = try { dir.listFiles { f -> f.isFile && f.name.endsWith(".portal") } }
                        catch (t: Throwable) { null }
            if (files == null) sb.append("ABSENT-or-UNREADABLE\n")
            else for (f in files.sortedBy { it.name }) {
                val content = try { f.readText() } catch (t: Throwable) { null }
                sb.append(f.name).append(if (content == null) ": READ-FAILED" else
                    ": " + content.lineSequence().filter { it.contains('=') && !it.trimStart().startsWith("#") }
                        .joinToString(" ") { it.trim() }.take(200)).append('\n')
            }
        }

        // systemd user-unit PRESENCE.
        section(out, "systemd user units (PRESENCE only - observed guest runs gnome-session's" +
            " classic non-systemd path; units are inert unless something starts systemd --user)") { sb ->
            val sysDir = File(rootfs, "usr/lib/systemd/user")
            val units = try { sysDir.listFiles { f -> f.isFile &&
                (f.name.contains("gnome") || f.name.contains("org.gnome")) } }
                        catch (t: Throwable) { null }
            if (units == null) sb.append("ABSENT-or-UNREADABLE\n")
            else {
                sb.append("gnome-related count=").append(units.size).append('\n')
                for (u in units.sortedBy { it.name }) sb.append("  ").append(u.name).append('\n')
            }
        }

        // Android phantom-killer CONFIG.
        section(out, "Android phantom-killer config (shell-uid reads; UNSET says nothing by" +
            " itself about the image default)") { sb ->
            for (cmd in listOf(
                "device_config get activity_manager max_phantom_processes",
                "device_config get activity_manager settings_enable_monitor_phantom_procs",
                "settings get global settings_enable_monitor_phantom_procs")) {
                val v = try { shell(cmd) } catch (t: Throwable) { null }
                sb.append(cmd).append(" => ")
                    .append(if (v.isNullOrBlank()) "UNSET/UNREADABLE" else v.trim()).append('\n')
            }
        }

        out.appendText("\n=== census-end (complete unless a TRUNCATED marker appears above) ===\n")
    }

    private fun dumpServices(rootfs: File, rel: String, prefixes: Set<String>, sb: StringBuilder) {
        val dir = File(rootfs, rel)
        val files = try { dir.listFiles { f -> f.isFile && f.name.endsWith(".service") &&
            prefixes.any { f.name.startsWith(it) } } } catch (t: Throwable) { null }
        if (files == null) { sb.append(rel).append(": ABSENT-or-UNREADABLE\n"); return }
        sb.append(rel).append(": count=").append(files.size).append('\n')
        for (sv in files.sortedBy { it.name }) {
            val (m, _) = groupKeys(try { sv.readText() } catch (t: Throwable) { "" }, "D-BUS Service",
                setOf("Name", "Exec", "SystemdService", "User"))
            sb.append("  ").append(sv.name).append(" | name=").append(m["Name"] ?: "?")
                .append(" exec=").append((m["Exec"] ?: "-").take(100))
                .append(" systemd-service=").append(m["SystemdService"] ?: "-")
                .append('\n')
        }
    }

    /** gnome-session-binary identification. /proc stat comm is truncated
     *  to 15 chars (TASK_COMM_LEN-1); the full name is 20 chars, so comm
     *  can only ever be a PREFIX of it - an exact comm equality check
     *  would NEVER match. "cmdline-match" = the cmdline basename equals
     *  the full name (strong); "truncated-comm-candidate" = only the
     *  truncated comm prefix matched (candidate, not proof); else
     *  "no-match". */
    fun gnomeSessionMatch(comm: String, cmdline: String): String {
        val full = "gnome-session-binary"
        val base = cmdline.substringBefore(' ').substringAfterLast('/')
        return when {
            base == full -> "cmdline-match"
            comm == full.take(15) -> "truncated-comm-candidate"
            else -> "no-match"
        }
    }

    /** NON-ATOMIC point-in-time /proc scan of processes owned by THIS
     *  app uid (pids can exit mid-scan; a ppid can point at an already-
     *  exited parent; chains are assembled from per-pid reads, never a
     *  kernel-consistent snapshot). UNKNOWN buckets are COUNTED:
     *  enumeration failure, stat read failure, uid read failure (rows
     *  with unreadable uid are INCLUDED, labeled uid=UNKNOWN - neither
     *  counted as ours nor silently skipped as foreign). Rows with
     *  comm=gnome-session-binary also carry the pid's OBSERVED XDG/HOME/
     *  DISPLAY environ (bounded key set) - runtime evidence that can
     *  contradict the census's assumed search order. */
    fun appendAncestrySnapshot(out: File, label: String) {
        val myUid = Process.myUid()
        val rows = ArrayList<String>()
        var statUnreadable = 0
        var uidUnknown = 0
        var cmdUnreadable = 0
        val proc = File("/proc")
        val dirs = try { proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } }
                   catch (t: Throwable) { null }
        out.appendText("=== ancestry[" + label + "] wall=" + System.currentTimeMillis() +
            " uid=" + myUid + " NON-ATOMIC point-in-time scan (per-pid reads; exit-mid-scan" +
            " possible; ppid links can dangle; NOT a kernel-consistent snapshot) ===\n")
        if (dirs == null) {
            out.appendText("!! /proc ENUMERATION FAILED - the whole scan is UNKNOWN, not empty\n")
            out.appendText("=== ancestry-end[" + label + "] ===\n")
            return
        }
        val envKeys = listOf("XDG_CONFIG_HOME=", "XDG_CONFIG_DIRS=", "XDG_DATA_DIRS=",
            "XDG_DATA_HOME=", "HOME=", "DISPLAY=", "XDG_SESSION_TYPE=", "XDG_CURRENT_DESKTOP=")
        for (d in dirs) {
            val stat = try { File(d, "stat").readText() } catch (t: Throwable) { null }
            if (stat == null) { statUnreadable++; continue }
            val close = stat.lastIndexOf(')')
            if (close < 0 || close + 2 >= stat.length) { statUnreadable++; continue }
            val comm = stat.substring(stat.indexOf('(') + 1, close)
            val f = stat.substring(close + 2).split(' ')
            if (f.size < 20) { statUnreadable++; continue }
            val statusUid = try {
                File(d, "status").readText().lineSequence()
                    .firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)
            } catch (t: Throwable) { null }
            val uid = statusUid?.toIntOrNull()
            if (uid == null) uidUnknown++
            if (uid != null && uid != myUid) continue
            val cmd = try {
                File(d, "cmdline").readBytes().toString(Charsets.UTF_8)
                    .replace(0.toChar(), ' ').trim().take(140)
            } catch (t: Throwable) { cmdUnreadable++; "<cmdline-unreadable>" }
            var row = "pid=" + d.name + " ppid=" + f[1] + " state=" + f[0] +
                " start=" + f[19] + " uid=" + (uid?.toString() ?: "UNKNOWN") +
                " comm=" + comm + " args=" + cmd
            when (gnomeSessionMatch(comm, cmd)) {
                "cmdline-match" -> {
                    // Bounded env capture with IDENTITY REVALIDATION: the
                    // pid+starttime pair must be unchanged AFTER the
                    // environ read, else an exit+reuse could attribute a
                    // stranger's env (same discipline as the shell
                    // environ reads in VirglGnomeSessionTest).
                    val env = try {
                        File(d, "environ").readBytes().toString(Charsets.UTF_8).split(0.toChar())
                            .filter { e -> envKeys.any { e.startsWith(it) } }
                    } catch (t: Throwable) { null }
                    val still = try {
                        val st2 = File(d, "stat").readText()
                        val c2 = st2.lastIndexOf(')')
                        c2 > 0 && st2.substring(c2 + 2).split(' ').getOrNull(19) == f[19]
                    } catch (t: Throwable) { false }
                    row += " | observed-env=" + when {
                        env == null -> "<unreadable>"
                        !still -> "<DISCARDED: pid identity changed around environ read (churn)>"
                        else -> env.joinToString(" ")
                    }
                }
                "truncated-comm-candidate" ->
                    row += " | gnome-session CANDIDATE (truncated comm only; cmdline did not" +
                        " confirm - env NOT captured for a candidate)"
                else -> { }
            }
            rows.add(row)
        }
        rows.sortBy { it.substringAfter(" start=").substringBefore(" ").toLongOrNull() ?: Long.MAX_VALUE }
        out.appendText("procs(ours-or-uid-unknown)=" + rows.size +
            " stat-unreadable=" + statUnreadable + " uid-UNKNOWN=" + uidUnknown +
            " cmdline-unreadable=" + cmdUnreadable + "\n")
        for (r in rows) out.appendText(r + "\n")
        out.appendText("=== ancestry-end[" + label + "] ===\n")
    }
}

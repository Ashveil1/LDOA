package dev.phonelinux.proto

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Diagnostic ZIP export: every app-owned evidence stream for one support
 *  bundle, shared as a file (the clipboard route capped the guest log at
 *  256 KiB and dropped everything else). App-private sources only; no
 *  logcat, no guest filesystem contents, no screen/input/clipboard data. */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val MAX_TOTAL_BYTES = 24L * 1024 * 1024 // all logs combined; rotation keeps current+prev pairs ~8 MiB, so normal exports are complete
    private const val MAX_KEPT_ZIPS = 3                   // bounded export retention

    /** Secret material is replaced with [redacted] in every exported
     *  text, in every common shape: Authorization/Bearer headers, JSON
     *  fields ("token": "x"), URL query values (?token=x), Cookie header
     *  values, CLI flags (--token x / --token=x), and shell assignments
     *  (PASSWORD=x, password = x). Rule order matters: the more specific
     *  shapes run before the generic assignment rule. App-owned evidence
     *  is NOT redacted: ICEAUTHORITY=/XAUTHORITY= file paths and the
     *  unix:path=...,guid=... session bus address carry no auth data. */
    private val SCRUB_RULES: List<Pair<Regex, String>> = listOf(
        Regex("(?i)(Authorization\\s*:\\s*Bearer)\\s+\\S+") to "${'$'}1 [redacted]",
        Regex("(?i)\\b(Bearer)\\s+[A-Za-z0-9._~+/=-]{8,}") to "${'$'}1 [redacted]",
        Regex("""(?i)("(?:password|passwd|secret|token|api[_-]?key|authorization|cookie)"\s*:\s*")[^"]*(")""") to "${'$'}1[redacted]${'$'}2",
        Regex("""(?i)([?&](?:token|key|password|secret|sig|signature)=)[^\s&"']+""") to "${'$'}1[redacted]",
        Regex("(?i)(Cookie\\s*:\\s*)[^\\r\\n]+") to "${'$'}1[redacted]",
        Regex("(?i)(--?(?:password|passwd|token|secret|api[_-]?key|cookie)(?:=|\\s+))\\S+") to "${'$'}1[redacted]",
        Regex("(?i)((?:password|passwd|secret|token|api[_-]?key|authorization|cookie)\\s*[:=]\\s*)\\S+") to "${'$'}1[redacted]"
    )

    internal fun scrub(text: String): String {
        var t = text
        for ((re, rep) in SCRUB_RULES) t = re.replace(t, rep)
        return t
    }

    /** Test-only total-budget override; null in app builds. */
    internal var maxTotalBytesForTests: Long? = null

    private val LOG_NAMES = listOf(
        "guest-gnome.log", "guest-gnome.prev.log", "xserver.log", "xserver.prev.log",
        "guest-desktop.log", "guest-gui.log", "terminal-session.log",
        "guest-xev.log", "guest-xclock.log"
    )

    data class Result(val zip: File, val entries: Int, val bytes: Long, val omitted: List<String>)

    /** Blocking - call from a background thread. */
    fun buildZip(ctx: Context): Result {
        val dir = File(ctx.cacheDir, "diagnostics")
        dir.mkdirs()
        dir.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
            var excess = files.size - (MAX_KEPT_ZIPS - 1)
            val it = files.iterator()
            while (excess > 0 && it.hasNext()) { it.next().delete(); excess-- }
        }
        val out = File(dir, "ldoa-diagnostics-" + System.currentTimeMillis() + ".zip")
        val omitted = ArrayList<String>()
        var entries = 0
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
                entries++
            }
            put("manifest.txt", buildManifest(ctx))
            put("session-snapshot.txt", XServer.diagnosticSnapshot(ctx))
            put("coverage.txt", COVERAGE)
            // Full retained logs by default: rotation already bounds each
            // current+prev pair, so the usual export is complete. Only a
            // combined total over MAX_TOTAL_BYTES forces a cut, and every
            // file always gets an explicit logs-manifest.txt row.
            var remaining = maxTotalBytesForTests ?: MAX_TOTAL_BYTES
            val logManifest = StringBuilder()
            logManifest.append("name\toriginal_bytes_pre_redaction\texported_bytes_post_redaction\tdropped_from_start_bytes\tfile_mtime_iso\n")
            // zip leaf name -> host file. LOG_NAMES live flat in filesDir;
            // the GNOME session sampler writes guest-side /tmp/ldoa-probes.log
            // which is the ubuntu rootfs' tmp dir from the host. Absent before
            // the first GNOME launch and on non-ubuntu distros - expected,
            // not a failure.
            val specs = ArrayList<Pair<String, File>>()
            for (n in LOG_NAMES) specs.add(n to File(ctx.filesDir, n))
            specs.add("ldoa-probes.log" to File(ctx.filesDir, "distros/ubuntu/tmp/ldoa-probes.log"))
            // One rotated generation of the sampler file (the sampler rotates
            // a full previous session aside so a new session is never starved).
            specs.add("ldoa-probes.prev.log" to File(ctx.filesDir, "distros/ubuntu/tmp/ldoa-probes.prev.log"))
            for ((name, f) in specs) {
                if (!f.isFile) continue
                val len = f.length()
                val mtime = isoTime(f.lastModified())
                if (remaining <= 0) {
                    omitted.add(name + " (export budget exhausted)")
                    logManifest.append(name).append('\t').append(len).append("\t0\t").append(len).append('\t').append(mtime).append('\n')
                    continue
                }
                val skip = if (len > remaining) len - remaining else 0L
                val raw = f.inputStream().use { ins ->
                    var r = skip
                    while (r > 0) {
                        val n = ins.skip(r)
                        if (n <= 0) break
                        r -= n
                    }
                    ins.readBytes()
                }
                val text = scrub(String(raw, Charsets.UTF_8))
                val payload = (if (skip > 0)
                    "[truncated for export budget: oldest " + skip + " of " + len + " bytes omitted; newest kept]\n" else "") + text
                val bytes = payload.toByteArray(Charsets.UTF_8)
                z.putNextEntry(ZipEntry("logs/" + name))
                z.write(bytes)
                z.closeEntry()
                entries++
                remaining -= bytes.size
                logManifest.append(name).append('\t').append(len).append('\t').append(bytes.size).append('\t').append(skip).append('\t').append(mtime).append('\n')
            }
            put("logs-manifest.txt", logManifest.toString())
        }
        Log.i(TAG, "diagnostics zip built: " + out.name + " entries=" + entries + " bytes=" + out.length())
        return Result(out, entries, out.length(), omitted)
    }

    private fun buildManifest(ctx: Context): String = StringBuilder().apply {
        append("LDOA diagnostic export\n")
        append("exported_wall=").append(isoTime(System.currentTimeMillis())).append('\n')
        append("exported_mono_ms=").append(SystemClock.elapsedRealtime()).append('\n')
        append("app_package=").append(ctx.packageName).append('\n')
        append("build_version=").append(BuildConfig.VERSION_NAME).append('\n')
        append("build_git=").append(BuildConfig.GIT_SHA).append('\n')
        append("device_model=").append(Build.MODEL).append('\n')
        append("device_product=").append(Build.PRODUCT).append('\n')
        append("device_hardware=").append(Build.HARDWARE).append('\n')
        append("android_sdk=").append(Build.VERSION.SDK_INT).append('\n')
        append("android_release=").append(Build.VERSION.RELEASE).append('\n')
        append("security_patch=").append(Build.VERSION.SECURITY_PATCH).append('\n')
    }.toString()

    private fun isoTime(wallMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(wallMs))

    private const val COVERAGE =
        "What this bundle contains:\n" +
        "- logs/: this app's own log files, complete unless the file's row in\n" +
        "  logs-manifest.txt shows dropped_from_start_bytes > 0 (only the\n" +
        "  oldest bytes are ever omitted, and only when the combined export\n" +
        "  exceeds 24 MiB; a normal session export is complete).\n" +
        "- session-snapshot.txt: session/ownership state + the readiness-gate\n" +
        "  decision timeline (monotonic + wall clock, matched per launch).\n" +
        "- logs/ldoa-probes.log (GNOME sessions only): bounded session-state\n" +
        "  samples, one every 15s plus probe time (actual spacing ~15-25s,\n" +
        "  span ~10-15 min; each sample stamps its own UTC time and seconds\n" +
        "  since launch, so actual spacing is measured, not assumed). If a\n" +
        "  previous session already filled the file it is rotated to\n" +
        "  ldoa-probes.prev.log (one generation kept) so the newest session\n" +
        "  always has room; a 'stopped: size cap' line is the explicit record\n" +
        "  when a session's samples stopped early. Samples record:\n" +
        "  gnome-shell pid, org.gnome.Shell bus owner (2s-timeout queries),\n" +
        "  X server pid+starttime (the SAME starttime at two points means the\n" +
        "  same server instance; a changed one means a restart), managed-\n" +
        "  window ids with map state/geometry/WM_CLASS (window titles are\n" +
        "  excluded at collection; each sample is that moment's state, not\n" +
        "  proof a window ever appeared), and the GL/DRI modules the shell\n" +
        "  process had loaded (evidence, NOT proof of the active renderer).\n" +
        "  The freshest sample can be ~15s older than the export itself.\n" +
        "- manifest.txt: app build, device model, Android version.\n" +
        "\n" +
        "Deliberately NOT included:\n" +
        "- logcat beyond this app's own logs (can carry other apps' data)\n" +
        "- Ubuntu guest filesystem contents (your files stay private)\n" +
        "- screen contents, typed input, clipboard\n" +
        "- credentials (none are written to these logs; any secret-shaped\n" +
        "  password=/token=/key= values are additionally replaced with\n" +
        "  [redacted] in every exported text file)\n" +
        "- stable device identifiers (identity is limited to device\n" +
        "  model/product/hardware and Android version/security patch)\n" +
        "\n" +
        "Known gaps in this build:\n" +
        "- frame-presentation and input-event cadence counters are still NOT\n" +
        "  instrumented: this bundle does not diagnose drag stutter\n" +
        "  numerically. Guest font state (fc-match/fc-list) is not probed.\n" +
        "- verbose logging is NARROW by design: gnome-session's own debug\n" +
        "  plus mutter backend/dbus/stack/sm/startup topics (verified topic\n" +
        "  names; no gvfs/gtk/gio document domains, no per-frame render\n" +
        "  topics). Individual lines can still contain window titles or\n" +
        "  guest paths - the scrubber removes secret-shaped values only.\n" +
        "  The 4 MiB rotation + 24 MiB export budget still cap size, so the\n" +
        "  OLDEST lines can rotate out of chatty sessions. Verbose logging\n" +
        "  costs some CPU in the measured path - timings in this bundle are\n" +
        "  diagnostic-mode timings.\n" +
        "- the export reads app-owned files only; a guest process that\n" +
        "  stopped writing leaves its log silent at that point (that silence\n" +
        "  is itself evidence, read with the gate timeline and the sampler's\n" +
        "  per-sample timestamps).\n"
}

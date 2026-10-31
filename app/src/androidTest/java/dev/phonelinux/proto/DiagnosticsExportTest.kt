package dev.phonelinux.proto

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Diagnostic ZIP export coverage (2026-10-31): full retained logs under
 * budget, explicit oldest-first truncation + manifest when over budget,
 * secret redaction in every common shape with app-owned evidence paths
 * kept, pre/post-redaction byte columns distinct, bounded ZIP retention,
 * and a readable content:// share URI. No GNOME/rootfs needed; the export
 * reads only app-private files the test writes itself (existing files are
 * backed up and restored). Bounded: a few seconds.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsExportTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val logNames = listOf("guest-gnome.log", "xserver.log")
    private val backups = HashMap<String, ByteArray>()

    private fun writeLogs(contents: Map<String, String>) {
        for (name in contents.keys + logNames) {
            val f = File(ctx.filesDir, name)
            if (!backups.containsKey(name) && f.isFile) backups[name] = f.readBytes()
        }
        for ((name, text) in contents) File(ctx.filesDir, name).writeText(text)
    }

    private fun entries(zip: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) { out[e.name] = z.readBytes(); z.closeEntry(); e = z.nextEntry }
        }
        return out
    }

    private fun manifestRows(ent: Map<String, ByteArray>): Map<String, List<String>> {
        val text = String(ent.getValue("logs-manifest.txt"), Charsets.UTF_8)
        val lines = text.trim().split("\n")
        assertTrue("manifest header names pre/post-redaction columns",
            lines[0].contains("original_bytes_pre_redaction") && lines[0].contains("exported_bytes_post_redaction"))
        return lines.drop(1).associate { line -> val c = line.split("\t"); c[0] to c }
    }

    @After
    fun cleanup() {
        Diagnostics.maxTotalBytesForTests = null
        File(ctx.cacheDir, "diagnostics").deleteRecursively()
        for (name in backups.keys + logNames) {
            val f = File(ctx.filesDir, name)
            val b = backups[name]
            if (b != null) f.writeBytes(b) else f.delete()
        }
        backups.clear()
    }

    @Test
    fun fullLogsWithinBudgetAreExportedComplete() {
        val gnome = (1..200).joinToString("\n") { "gnome log line " + it + " with some payload" }
        val xs = (1..120).joinToString("\n") { "xserver line " + it }
        writeLogs(mapOf("guest-gnome.log" to gnome, "xserver.log" to xs))
        val res = Diagnostics.buildZip(ctx)
        val ent = entries(res.zip)
        assertEquals(gnome, String(ent.getValue("logs/guest-gnome.log"), Charsets.UTF_8))
        assertEquals(xs, String(ent.getValue("logs/xserver.log"), Charsets.UTF_8))
        val rows = manifestRows(ent)
        val g = rows.getValue("guest-gnome.log")
        assertEquals(gnome.toByteArray().size.toString(), g[1])   // original pre-redaction
        assertEquals(g[1], g[2])                                  // exported == original (no secrets)
        assertEquals("0", g[3])                                   // nothing dropped
        assertTrue(res.omitted.isEmpty())
        assertNotNull(ent["manifest.txt"]); assertNotNull(ent["session-snapshot.txt"]); assertNotNull(ent["coverage.txt"])
    }

    @Test
    fun overBudgetTruncatesOldestFirstAndManifestSaysSo() {
        Diagnostics.maxTotalBytesForTests = 4_000
        val gnome = "A".repeat(3_000)
        val xs = "B".repeat(3_000)
        writeLogs(mapOf("guest-gnome.log" to gnome, "xserver.log" to xs))
        val res = Diagnostics.buildZip(ctx)
        val ent = entries(res.zip)
        assertEquals(gnome, String(ent.getValue("logs/guest-gnome.log"), Charsets.UTF_8))
        val xsOut = String(ent.getValue("logs/xserver.log"), Charsets.UTF_8)
        assertTrue("truncated file must carry the explicit header", xsOut.startsWith("[truncated for export budget:"))
        assertTrue("newest bytes kept", xsOut.endsWith("B".repeat(200)))
        assertFalse(xsOut.contains("B".repeat(3_000)))
        val rows = manifestRows(ent)
        val r = rows.getValue("xserver.log")
        assertEquals("3000", r[1])
        assertEquals("2000", r[3])                                // dropped from start
        assertTrue("exported counts post-redaction bytes", r[2].toLong() < 3000L)
        assertTrue("coverage must point at logs-manifest for truncation state",
            String(ent.getValue("coverage.txt"), Charsets.UTF_8).contains("logs-manifest.txt"))
    }

    @Test
    fun secretsAreRedactedInEveryShapeEvidencePathsKept() {
        val body = listOf(
            "Authorization: Bearer abcdef123456SECRETtoken",
            "pre {\"token\": \"jsonsecret123\"} post",
            "PASSWORD = spacedsecret",
            "launch --password cliSecret99 --verbose",
            "open https://example.internal/x?token=urlsecret77&ok=1",
            "Cookie: sessionid=cookiesecret55; other=1",
            "ICEAUTHORITY=/tmp/xdg/ICEauthority",
            "gnome-launch: session bus unix:path=/tmp/bus-AAA,guid=AAA",
            "plain line with no secrets"
        ).joinToString("\n")
        writeLogs(mapOf("guest-gnome.log" to body))
        val res = Diagnostics.buildZip(ctx)
        val ent = entries(res.zip)
        val out = String(ent.getValue("logs/guest-gnome.log"), Charsets.UTF_8)
        for (secret in listOf("abcdef123456SECRETtoken", "jsonsecret123", "spacedsecret",
                              "cliSecret99", "urlsecret77", "cookiesecret55")) {
            assertFalse("leaked: " + secret, out.contains(secret))
        }
        assertTrue(out.contains("[redacted]"))
        // App-owned evidence is not redacted: no auth data in these values.
        assertTrue("ICEauthority path kept", out.contains("ICEAUTHORITY=/tmp/xdg/ICEauthority"))
        assertTrue("bus address kept", out.contains("unix:path=/tmp/bus-AAA,guid=AAA"))
        assertTrue("innocent line kept", out.contains("plain line with no secrets"))
        // Pre/post-redaction byte columns are distinct when redaction ran.
        val g = manifestRows(ent).getValue("guest-gnome.log")
        assertFalse(g[1] == g[2])
        // The share surface: a content URI the receiving app can actually read.
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", res.zip)
        assertEquals("content", uri.scheme)
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            assertEquals('P'.code, ins.read())   // ZIP magic "PK"
            assertEquals('K'.code, ins.read())
        }
    }

    @Test
    fun zipRetentionStaysBounded() {
        val dir = File(ctx.cacheDir, "diagnostics").apply { mkdirs() }
        for (i in 1..5) {
            File(dir, "ldoa-diagnostics-old-" + i + ".zip").apply {
                writeBytes(byteArrayOf(1, 2, 3)); setLastModified(1_000_000L + i)
            }
        }
        writeLogs(mapOf("guest-gnome.log" to "x"))
        Diagnostics.buildZip(ctx)
        val kept = dir.listFiles()?.count { it.name.endsWith(".zip") } ?: 0
        assertTrue("old exports must be reaped to the retention bound, kept=" + kept, kept <= 3)
    }
}

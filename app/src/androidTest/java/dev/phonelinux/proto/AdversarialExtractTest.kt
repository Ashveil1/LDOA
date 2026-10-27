package dev.phonelinux.proto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * An attacker-crafted archive must never write outside the staging root,
 * even using symlink parents created by earlier entries, relative .. links,
 * hardlinks, or plain traversal names. Legit guest entries (incl. absolute
 * symlink targets like /bin/busybox used under proot) must still extract.
 */
@RunWith(AndroidJUnit4::class)
class AdversarialExtractTest {

    private fun tarGz(build: (TarArchiveOutputStream) -> Unit): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(ctx.cacheDir, "adv-${System.nanoTime()}.tar.gz")
        val baos = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(baos)).use { tos ->
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            build(tos)
        }
        f.writeBytes(baos.toByteArray())
        return f
    }

    private fun file(tos: TarArchiveOutputStream, name: String, content: String, mode: Int = 0b110100100) {
        val e = TarArchiveEntry(name)
        e.size = content.toByteArray().size.toLong()
        e.mode = mode
        tos.putArchiveEntry(e)
        tos.write(content.toByteArray())
        tos.closeArchiveEntry()
    }

    private fun symlink(tos: TarArchiveOutputStream, name: String, target: String) {
        val e = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
        e.linkName = target
        tos.putArchiveEntry(e)
        tos.closeArchiveEntry()
    }

    private fun hardlink(tos: TarArchiveOutputStream, name: String, target: String) {
        val e = TarArchiveEntry(name, TarArchiveEntry.LF_LINK)
        e.linkName = target
        tos.putArchiveEntry(e)
        tos.closeArchiveEntry()
    }

    @Test
    fun maliciousArchiveCannotEscapeStaging() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val base = ctx.cacheDir
        val staging = File(base, "adv-root").apply { deleteRecursively(); mkdirs() }
        val outside = File(base, "adv-outside").apply { deleteRecursively(); mkdirs() }
        val canary = File(outside, "canary.txt").apply { writeText("untouched") }

        val tgz = tarGz { tos ->
            // absolute symlink pointing OUTSIDE staging, then write through it
            symlink(tos, "link-abs", outside.absolutePath)
            file(tos, "link-abs/pwned1.txt", "PWNED")
            // relative symlink escaping staging, then write through it
            val e = TarArchiveEntry("dir/"); e.mode = 0b111101101; tos.putArchiveEntry(e); tos.closeArchiveEntry()
            symlink(tos, "dir/rel", "../../adv-outside")
            file(tos, "dir/rel/pwned2.txt", "PWNED")
            // plain traversal name
            file(tos, "../pwned3.txt", "PWNED")
            // hardlink to an outside file
            hardlink(tos, "hard", "../adv-outside/canary.txt")
            // symlink whose target is an absolute path INSIDE staging is fine,
            // and guest-style absolute targets must be preserved as links
            symlink(tos, "good/sh", "/bin/busybox")
            file(tos, "good/file.txt", "hello")
        }

        // extraction must FAIL CLOSED on the first blocked entry...
        try {
            DistroInstaller(ctx).extractTarGz(tgz, staging)
            throw AssertionError("malicious archive was not rejected")
        } catch (expected: SecurityException) {
            // good
        }

        // ...and nothing escaped before the failure: canary intact, no new files outside
        assertEquals("untouched", canary.readText())
        assertEquals(listOf("canary.txt"), outside.list()?.toList() ?: emptyList<String>())
        assertFalse(File(base, "pwned3.txt").exists())
        tgz.delete()
    }

    @Test
    fun benignArchiveExtractsFully() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val staging = File(ctx.cacheDir, "benign-root").apply { deleteRecursively(); mkdirs() }
        val tgz = tarGz { tos ->
            symlink(tos, "good/sh", "/bin/busybox") // guest-style absolute target
            file(tos, "good/file.txt", "hello")
            file(tos, "good/tool", "#!/bin/sh\n", mode = 0b111101101) // executable bit
        }
        DistroInstaller(ctx).extractTarGz(tgz, staging)
        assertEquals("hello", File(staging, "good/file.txt").readText())
        val sh = File(staging, "good/sh")
        assertTrue("guest absolute symlink preserved", sh.exists() || android.system.Os.lstat(sh.absolutePath) != null)
        assertTrue("exec bit preserved", File(staging, "good/tool").canExecute())
        tgz.delete()
    }
}

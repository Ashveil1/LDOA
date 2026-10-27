package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** One installable distro userland. Only pinned, checksummed sources. */
data class Distro(
    val id: String,
    val displayName: String,
    val description: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long
)

object Distros {
    // Pinned per-ABI userlands. x86_64 exists so emulator CI exercises the real
    // flow without ARM translation; arm64 is what ships on devices.
    private const val ALPINE_VER = "3.21.3"
    private const val ALPINE_BASE = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases"

    private val ALPINE_ARM64 = Distro(
        id = "alpine",
        displayName = "Alpine Linux",
        description = "Minimal userland, fast to install. Prototype base.",
        url = "$ALPINE_BASE/aarch64/alpine-minirootfs-$ALPINE_VER-aarch64.tar.gz",
        sha256 = "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea",
        sizeBytes = 3_850_365L
    )
    private val ALPINE_X86_64 = ALPINE_ARM64.copy(
        url = "$ALPINE_BASE/x86_64/alpine-minirootfs-$ALPINE_VER-x86_64.tar.gz",
        sha256 = "1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239",
        sizeBytes = 3_650_000L // approx; real length comes from Content-Length
    )

    /** Distro matching this device's primary ABI. */
    val ALPINE: Distro
        get() = if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") ALPINE_X86_64 else ALPINE_ARM64
}

sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : InstallState
    data object Verifying : InstallState
    data object Extracting : InstallState
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}

class DistroInstaller(private val ctx: Context) {
    private val TAG = "PhoneLinuxInstall"

    fun rootfsDir(d: Distro): File = File(ctx.filesDir, "distros/${d.id}")

    fun isInstalled(d: Distro): Boolean = File(rootfsDir(d), ".install-complete").exists()

    /** Remove a partial/failed install so a retry starts clean. */
    fun reset(d: Distro) {
        rootfsDir(d).deleteRecursively()
        File(ctx.filesDir, "dl/${d.id}.tar.gz").delete()
        File(ctx.filesDir, "dl/${d.id}.tar.gz.tmp").delete()
    }

    suspend fun install(d: Distro, onState: (InstallState) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val dlDir = File(ctx.filesDir, "dl").apply { mkdirs() }
        val tmp = File(dlDir, "${d.id}.tar.gz.tmp")
        val tgz = File(dlDir, "${d.id}.tar.gz")
        try {
            // download with resume
            onState(InstallState.Downloading(tmp.length().coerceAtLeast(0), d.sizeBytes))
            download(d.url, tmp, d.sizeBytes) { done, total -> onState(InstallState.Downloading(done, total)) }

            // verify
            onState(InstallState.Verifying)
            val digest = sha256(tmp)
            if (!digest.equals(d.sha256, ignoreCase = true)) {
                tmp.delete()
                onState(InstallState.Failed("Checksum mismatch (got ${digest.take(12)}…). Try again."))
                return@withContext false
            }
            tmp.renameTo(tgz)

            // extract
            onState(InstallState.Extracting)
            val root = rootfsDir(d)
            root.deleteRecursively()
            root.mkdirs()
            try {
                extractTarGz(tgz, root)
            } catch (t: Throwable) {
                root.deleteRecursively() // never leave a half-extracted rootfs
                throw t
            }
            File(root, ".install-complete").writeText("${d.url}\n${d.sha256}\n")
            onState(InstallState.Installed)
            Log.i(TAG, "installed ${d.id} -> $root")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
            onState(InstallState.Failed(t.message ?: t.javaClass.simpleName))
            false
        }
    }

    private fun download(url: String, dest: File, expected: Long, onProgress: (Long, Long) -> Unit) {
        require(url.startsWith("https://")) { "distro URL must be HTTPS" }
        val existing = if (dest.exists()) dest.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        val code = conn.responseCode
        when {
            code == 416 -> { dest.delete(); throw java.io.IOException("resume rejected (416); retrying clean next attempt") }
            code == 206 -> {
                // validate the returned range actually starts where we asked
                val cr = conn.getHeaderField("Content-Range") ?: ""
                val start = cr.removePrefix("bytes ").substringBefore('-').toLongOrNull()
                if (start != existing) { dest.delete(); throw java.io.IOException("bad Content-Range: $cr") }
            }
            code in 200..299 -> if (existing > 0) dest.delete() // server ignored Range: restart clean
            else -> throw java.io.IOException("HTTP $code downloading distro")
        }
        val append = code == 206
        val total = if (code == 206) existing + conn.contentLengthLong else conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(dest, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = if (append) existing else 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    onProgress(done, if (total > 0) total else expected)
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun extractTarGz(tgz: File, dest: File) {
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(tgz.inputStream()))).use { tar ->
            var e: TarArchiveEntry? = tar.nextTarEntry
            // canonical root WITH trailing separator, so a sibling like
            // /x/staging-evil never matches a /x/staging prefix check.
            // canonicalPath also resolves already-extracted symlink parents,
            // so a later entry writing THROUGH a hostile symlink lands outside
            // and is blocked below. Single-writer, app-private staging: no TOCTOU
            // window from other processes.
            val canonDest = dest.canonicalPath + File.separator
            while (e != null) {
                val out = File(dest, e.name)
                fun blocked(reason: String): Nothing =
                    throw SecurityException("archive entry blocked ($reason): ${e.name}")
                if (!out.canonicalPath.startsWith(canonDest)) blocked("path escapes root")
                // Absolute symlink targets (e.g. -> /bin/busybox) are safe: proot remaps /
                // to this rootfs, and extraction never dereferences them on the host.
                // Relative targets must not escape dest via .. traversal.
                if (e.isSymbolicLink || e.isLink) {
                    val ln = e.linkName
                    if (!java.io.File(ln).isAbsolute) {
                        val resolved = runCatching { File(out.parentFile, ln).canonicalPath }.getOrNull()
                        if (resolved == null || !resolved.startsWith(canonDest)) blocked("link target escapes root")
                    }
                }
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        try { android.system.Os.symlink(e.linkName, out.absolutePath) }
                        catch (t: Throwable) { Log.w(TAG, "symlink ${e.name}: ${t.message}") }
                    }
                    e.isLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        val target = File(dest, e.linkName)
                        if (target.exists()) target.copyTo(out, overwrite = true)
                    }
                    else -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os -> tar.copyTo(os) }
                        if ((e.mode and 0b001_001_001) != 0) out.setExecutable(true, true)
                    }
                }
                e = tar.nextTarEntry
            }
        }
    }
}

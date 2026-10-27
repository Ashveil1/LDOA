package dev.phonelinux.proto

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Provisions xkeyboard-config data for the embedded X server.
 * The vendored lorie server hard-requires XKB_CONFIG_ROOT with real keymap
 * data (upstream gets it from the Termux prefix; we ship none in the APK).
 * Pinned Alpine package, SHA-256 verified, fail-closed.
 */
object XkbData {
    private const val TAG = "PhoneLinuxXkb"
    private const val URL_STR = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/x86_64/xkeyboard-config-2.43-r0.apk"
    private const val SHA256 = "cab4611f7b37fecd2fc9e160585f6fb6c8e9f0fab2da3df6c516649247406db7"

    fun root(ctx: Context): File = File(ctx.filesDir, "usr/share/X11/xkb")

    @Synchronized
    fun ensure(ctx: Context) {
        val dest = root(ctx)
        if (File(dest, "rules/evdev").isFile) return
        Log.i(TAG, "downloading xkeyboard-config 2.43-r0")
        val bytes = URL(URL_STR).readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        check(hex == SHA256) { "xkeyboard-config checksum mismatch: $hex" }
        val tmp = File(ctx.filesDir, "usr/share/X11/xkb.staging")
        tmp.deleteRecursively()
        tmp.mkdirs()
        TarArchiveInputStream(GZIPInputStream(bytes.inputStream())).use { tar ->
            val canonRoot = tmp.canonicalFile
            generateSequence { tar.nextTarEntry }.forEach { e ->
                val name = e.name.removePrefix("./")
                if (!name.startsWith("usr/share/X11/xkb/")) return@forEach
                val out = File(tmp, name.removePrefix("usr/share/X11/xkb/"))
                check(out.canonicalPath.startsWith(canonRoot.path + File.separator)) { "unsafe entry $name" }
                if (e.isDirectory) { out.mkdirs(); return@forEach }
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
            }
        }
        dest.deleteRecursively()
        check(tmp.renameTo(dest)) { "rename staging -> xkb root failed" }
        Log.i(TAG, "xkb data ready at $dest")
    }
}

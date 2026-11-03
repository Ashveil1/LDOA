package dev.phonelinux.proto
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import org.json.JSONObject
/** androidTest only. Explicit caller gating: opted-in disposable CI emulator. */
internal object ManagedIncomingForTest {
    const val PREFIX="opt/ldoa/pcmanfm/1.3.2-4build2-floor1"
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun stage(root:File,incoming:File,pin:String,case:String):String {
        require(pin.matches(Regex("[0-9a-f]{64}")) && case in listOf("original","patched"))
        val source=incoming.toPath()
        check(!Files.isSymbolicLink(source)&&Files.isDirectory(source,NOFOLLOW_LINKS))
        val bytes=Files.newInputStream(source.resolve("manifest.json")).use { it.readNBytes(65537) }
        check(bytes.size<=65536 && hash(bytes)==pin)
        val m=JSONObject(String(bytes,Charsets.UTF_8));check(m.getInt("schema")==1&&m.getString("case")==case&&m.getString("prefix")=="/$PREFIX")
        val files=m.getJSONObject("files");val dirs=m.getJSONArray("directories")
        val names=files.keys().asSequence().toSet();val directoryNames=(0 until dirs.length()).map { dirs.getString(it) }.toSet()
        fun safe(name:String):Boolean {
            val canonical=name.matches(Regex("[A-Za-z0-9_./+-]+"))&&!name.startsWith('/')&&name.split('/').none { it==".."||it=="."||it.isEmpty() }
            // Exact upstream locale modifier, not unrestricted @ filenames.
            return canonical || name in setOf("payload/share/locale/sr@latin","payload/share/locale/sr@latin/LC_MESSAGES","payload/share/locale/sr@latin/LC_MESSAGES/pcmanfm.mo")
        }
        check(names.size in 1..256) { "manifest file-count" }
        check(names.all(::safe)) { "manifest file-name" }
        check(directoryNames.size<=256) { "manifest directory-count" }
        check(directoryNames.all(::safe)) { "manifest directory-name" }
        check(setOf("payload/bin/pcmanfm","payload/COPYING","payload/DEBIAN-COPYRIGHT","payload/SOURCE.txt","source/orig.tar.xz","source/debian.tar.xz","source/default-size.patch","source/build-recipe.sh").all { it in names })
        check("payload/lib/pcmanfm" in directoryNames)
        Files.walk(source).use { stream ->
            val all=stream.iterator().asSequence().take(1025).toList();check(all.size<=1024&&all.none { Files.isSymbolicLink(it) })
            check(all.filter { Files.isRegularFile(it,NOFOLLOW_LINKS) }.map { source.relativize(it).toString() }.toSet()==names+"manifest.json")
            check(all.filter { it!=source&&Files.isDirectory(it,NOFOLLOW_LINKS) }.map { source.relativize(it).toString() }.toSet()==directoryNames)
        }
        var total=0L
        for(name in names) {
            val p=source.resolve(name);val size=Files.size(p)
            check(size<=32*1024*1024&&total+size<=128*1024*1024);total+=size
            check(hash(Files.readAllBytes(p))==files.getString(name))
        }
        check(total==m.getLong("bytes"))
        // Independent known source pins, not just a self-asserted manifest.
        check(files.getString("source/orig.tar.xz")=="14cb7b247493c4cce65fbb5902611e3ad00a7a870fbc1e50adc50428c5140cf7")
        check(files.getString("source/debian.tar.xz")=="ac2538fe14f44bd56ea77f1bba8c4f16349898ae259e2b602465f4d2faec0723")
        check(files.getString("source/default-size.patch")=="056bd20e1914f35cfe8924ab0a6044dd2c5e9f5366eef9104276ed1ccfe8ae63")
        val target=root.toPath().resolve(PREFIX)
        var parent=root.toPath();check(!Files.isSymbolicLink(parent))
        for(part in PREFIX.split('/').dropLast(1)) {
            parent=parent.resolve(part)
            if(Files.exists(parent,NOFOLLOW_LINKS)) check(Files.isDirectory(parent,NOFOLLOW_LINKS)&&!Files.isSymbolicLink(parent))
            else Files.createDirectory(parent)
        }
        Files.createDirectory(target,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        // Complete corresponding sources and license files are copied INSIDE
        // this private prefix, not a separate public binary artifact.
        val translated=directoryNames.map { if(it=="payload") "" else if(it.startsWith("payload/")) it.removePrefix("payload/") else "corresponding-$it" }.filter { it.isNotEmpty() }.sortedBy { it.count { c->c=='/' } }
        for(n in translated) Files.createDirectory(target.resolve(n))
        for(n in names) {
            val to=if(n.startsWith("payload/")) n.removePrefix("payload/") else "corresponding-$n"
            Files.newOutputStream(target.resolve(to),CREATE_NEW).use { out -> Files.newInputStream(source.resolve(n)).use { it.copyTo(out) } }
            check(hash(Files.readAllBytes(target.resolve(to)))==files.getString(n))
        }
        Files.setPosixFilePermissions(target.resolve("bin/pcmanfm"),PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.write(target.resolve("incoming-manifest.json"),bytes,CREATE_NEW)
        return files.getString("payload/inventory.sha256")
    }
}

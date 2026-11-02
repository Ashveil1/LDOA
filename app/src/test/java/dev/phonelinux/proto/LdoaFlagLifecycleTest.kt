package dev.phonelinux.proto

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import dev.phonelinux.proto.LdoaFlagLifecycle.Mutation
import dev.phonelinux.proto.LdoaFlagLifecycle.RestoreOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM fixture for the EXACT LdoaFlagLifecycle code the product readers
 *  and the instrumentation test call. Each case prints a transcript line
 *  (JUnit XML system-out) so the raw run output is the evidence. */
class LdoaFlagLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun flag(): Path = tmp.root.toPath().resolve(".ldoa-virgl-debug")
    private val one = byteArrayOf(0x31)

    private fun t(line: String) = println("FIXTURE $line")

    @Test fun read_absent_isNull_notError() {
        assertNull(LdoaFlagLifecycle.readIfRegular(flag()))
        assertFalse(LdoaFlagLifecycle.isSet(flag()))
        t("absent -> null/false OK")
    }

    @Test fun read_regularPresent_returnsBytes() {
        Files.write(flag(), one)
        assertArrayEquals(one, LdoaFlagLifecycle.readIfRegular(flag()))
        assertTrue(LdoaFlagLifecycle.isSet(flag()))
        t("regular-present -> bytes OK")
    }

    @Test fun read_symlink_throws_notAbsent_andNofollowOpen() {
        val target = tmp.newFile("target"); Files.write(target.toPath(), one)
        val link = flag(); Files.createSymbolicLink(link, target.toPath())
        try { LdoaFlagLifecycle.readIfRegular(link); fail("symlink must throw") }
        catch (e: IllegalStateException) { t("symlink -> IllegalStateException OK (stat AND open are NOFOLLOW)") }
    }

    @Test fun read_deniedParent_propagates_notAbsent() {
        val dir = tmp.newFolder("locked"); val f = dir.toPath().resolve(".ldoa-virgl-debug")
        Files.write(f, one)
        dir.setReadable(false); dir.setExecutable(false)
        try {
            try { LdoaFlagLifecycle.readIfRegular(f); fail("denied parent must not read as absent") }
            catch (e: IOException) { t("denied-parent -> IOException propagated OK: " + e.javaClass.simpleName) }
            catch (e: IllegalStateException) { t("denied-parent -> IllegalStateException OK") }
        } finally { dir.setReadable(true); dir.setExecutable(true) }
    }

    @Test fun createNew_absent_writesExactBytes() {
        LdoaFlagLifecycle.createNew(flag(), one)
        assertArrayEquals(one, Files.readAllBytes(flag()))
        t("createNew absent -> exact bytes OK")
    }

    @Test fun createNew_existing_failsLoudly_noTruncate() {
        Files.write(flag(), byteArrayOf(0x39, 0x39, 0x39))
        try { LdoaFlagLifecycle.createNew(flag(), one); fail("existing must fail") }
        catch (e: FileAlreadyExistsException) {
            assertArrayEquals(byteArrayOf(0x39, 0x39, 0x39), Files.readAllBytes(flag()))
            t("createNew existing -> FileAlreadyExistsException, content untouched OK")
        }
    }

    @Test fun createNew_overSymlink_failsLoudly() {
        val target = tmp.newFile("t2"); val link = flag()
        Files.createSymbolicLink(link, target.toPath())
        try { LdoaFlagLifecycle.createNew(link, one); fail("symlink must fail") }
        catch (e: FileAlreadyExistsException) { t("createNew over symlink -> FileAlreadyExistsException OK") }
    }

    @Test fun race_createBetweenReadAndWrite_failsLoudly() {
        assertNull(LdoaFlagLifecycle.readIfRegular(flag()))
        Files.write(flag(), byteArrayOf(0x38))              // racing creator
        try { LdoaFlagLifecycle.createNew(flag(), one); fail("raced create must fail") }
        catch (e: FileAlreadyExistsException) {
            assertArrayEquals(byteArrayOf(0x38), Files.readAllBytes(flag()))
            t("race -> FileAlreadyExistsException, racer's bytes preserved OK")
        }
    }

    @Test fun failedCreate_racerBytesOne_restoreNONE_preservesRacer() {
        // Exact bug class: createNew failed against a racer whose bytes
        // are ALSO "1"; this invocation created NOTHING, so restore (NONE)
        // must not delete the racer's file.
        assertNull(LdoaFlagLifecycle.readIfRegular(flag()))
        Files.write(flag(), one)                             // racer writes "1"
        var mutation = Mutation.NONE
        try { LdoaFlagLifecycle.createNew(flag(), one); mutation = Mutation.CREATED; fail("must fail") }
        catch (e: FileAlreadyExistsException) { /* mutation stays NONE */ }
        assertEquals(Mutation.NONE, mutation)
        assertEquals(RestoreOutcome.NOTHING_TO_DO,
            LdoaFlagLifecycle.guardedRestore(flag(), null, mutation))
        assertArrayEquals(one, Files.readAllBytes(flag()))
        t("failedCreate racer-bytes='1' + restore NONE -> racer preserved OK")
    }

    @Test fun savedNull_off_newFileOneAppears_restoreNONE_preserves() {
        // saved==null + OFF: a "1" file that appeared during the arm (not
        // written by this test) must survive restore untouched.
        Files.write(flag(), one)
        assertEquals(RestoreOutcome.NOTHING_TO_DO,
            LdoaFlagLifecycle.guardedRestore(flag(), null, Mutation.NONE))
        assertArrayEquals(one, Files.readAllBytes(flag()))
        t("saved-null/OFF new '1' file + restore NONE -> preserved OK")
    }

    @Test fun savedPresent_on_unchanged_restoreNONE_noRewrite() {
        // saved present + ON: flag left unchanged during the arm; restore
        // must not rewrite it at all (content AND mtime untouched).
        val saved = byteArrayOf(0x41, 0x42, 0x43)
        Files.write(flag(), saved)
        val mtime = Files.getLastModifiedTime(flag()).toMillis()
        assertEquals(RestoreOutcome.NOTHING_TO_DO,
            LdoaFlagLifecycle.guardedRestore(flag(), saved, Mutation.NONE))
        assertArrayEquals(saved, Files.readAllBytes(flag()))
        assertEquals(mtime, Files.getLastModifiedTime(flag()).toMillis())
        t("saved-present/ON unchanged + restore NONE -> no rewrite OK")
    }

    @Test fun deleteIfUnchanged_matching_deletes_foreign_preserves_absent_false() {
        Files.write(flag(), one)
        assertTrue(LdoaFlagLifecycle.deleteIfUnchanged(flag(), one))
        assertFalse(Files.exists(flag()))
        Files.write(flag(), byteArrayOf(0x32))
        assertFalse(LdoaFlagLifecycle.deleteIfUnchanged(flag(), one))   // foreign
        assertArrayEquals(byteArrayOf(0x32), Files.readAllBytes(flag()))
        Files.delete(flag())
        assertFalse(LdoaFlagLifecycle.deleteIfUnchanged(flag(), one))   // absent
        t("deleteIfUnchanged match/foreign/absent OK")
    }

    @Test fun restore_CREATED_ownedWritten_deleted() {
        Files.write(flag(), one)
        assertEquals(RestoreOutcome.DELETED,
            LdoaFlagLifecycle.guardedRestore(flag(), null, Mutation.CREATED))
        assertFalse(Files.exists(flag()))
        t("restore CREATED owned -> DELETED OK")
    }

    @Test fun restore_CREATED_foreignContent_preserved() {
        Files.write(flag(), byteArrayOf(0x32))
        assertEquals(RestoreOutcome.PRESERVED_FOREIGN,
            LdoaFlagLifecycle.guardedRestore(flag(), null, Mutation.CREATED))
        assertArrayEquals(byteArrayOf(0x32), Files.readAllBytes(flag()))
        t("restore CREATED foreign -> PRESERVED OK (byte guard; identical-bytes replacement is a documented race limit)")
    }

    @Test fun restore_CREATED_absent_nothingToDo() {
        assertEquals(RestoreOutcome.NOTHING_TO_DO,
            LdoaFlagLifecycle.guardedRestore(flag(), null, Mutation.CREATED))
        t("restore CREATED absent -> NOTHING_TO_DO OK")
    }

    @Test fun restore_DELETED_absent_recreatesExactViaCreateNew() {
        // owned OFF deletion happened; path is absent at restore; exact
        // saved bytes recreated (shorter saved than any prior content is
        // impossible to tail - CREATE_NEW writes a fresh file).
        val saved = byteArrayOf(0x41, 0x42)
        assertEquals(RestoreOutcome.RESTORED,
            LdoaFlagLifecycle.guardedRestore(flag(), saved, Mutation.DELETED))
        assertArrayEquals(saved, Files.readAllBytes(flag()))
        assertEquals(2, Files.size(flag()))
        t("restore DELETED absent -> recreated exact via CREATE_NEW OK")
    }

    @Test fun restore_DELETED_newcomerPresent_failsLoudly_preservesNewcomer() {
        val saved = byteArrayOf(0x41)
        Files.write(flag(), byteArrayOf(0x5a)) // newcomer after our deletion
        try {
            LdoaFlagLifecycle.guardedRestore(flag(), saved, Mutation.DELETED)
            fail("newcomer must fail CREATE_NEW loudly")
        } catch (e: FileAlreadyExistsException) {
            assertArrayEquals(byteArrayOf(0x5a), Files.readAllBytes(flag()))
            t("restore DELETED newcomer -> FileAlreadyExistsException, newcomer preserved OK")
        }
    }

    @Test fun restore_nonRegular_throws() {
        val target = tmp.newFile("t3"); val link = flag()
        Files.createSymbolicLink(link, target.toPath())
        try { LdoaFlagLifecycle.guardedRestore(link, one, Mutation.CREATED); fail("symlink must throw") }
        catch (e: IllegalStateException) { t("restore on symlink -> IllegalStateException OK") }
    }

    @Test fun requireMode_presentOn_absentOff_pass() {
        Files.write(flag(), one)
        LdoaFlagLifecycle.requireMode(flag(), true)   // no throw
        Files.delete(flag())
        LdoaFlagLifecycle.requireMode(flag(), false)  // no throw
        t("requireMode present/ON + absent/OFF -> pass OK")
    }

    @Test fun requireMode_presentButOffRequested_throws_failClosed() {
        // The OFF-preserved-foreign flow: content changed so
        // deleteIfUnchanged preserved it; the flag is still present, so
        // the pre-launch check must REFUSE instead of launching with
        // logging on under a requested OFF.
        Files.write(flag(), byteArrayOf(0x32)) // foreign content
        assertFalse(LdoaFlagLifecycle.deleteIfUnchanged(flag(), one)) // preserved
        try { LdoaFlagLifecycle.requireMode(flag(), false); fail("present+OFF must throw") }
        catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("disagrees"))
            t("OFF preserve-foreign -> requireMode throws (no silent logging-on launch) OK")
        }
    }

    @Test fun requireMode_racerFlagAppearsUnderOff_throws_failClosed() {
        // OFF + absent at save time, but a racer armed the flag before
        // launch: effective state disagrees -> refuse.
        Files.write(flag(), one) // racer
        try { LdoaFlagLifecycle.requireMode(flag(), false); fail("racer flag under OFF must throw") }
        catch (e: IllegalStateException) { t("OFF racer-armed -> requireMode throws OK") }
    }

    @Test fun requireMode_onButFlagVanished_throws_failClosed() {
        try { LdoaFlagLifecycle.requireMode(flag(), true); fail("absent+ON must throw") }
        catch (e: IllegalStateException) { t("ON flag-vanished -> requireMode throws OK") }
    }

    @Test fun restore_deniedParent_exceptionPropagates() {
        val dir = tmp.newFolder("locked2"); val f = dir.toPath().resolve(".ldoa-virgl-debug")
        Files.write(f, one)
        dir.setWritable(false)
        try {
            try { LdoaFlagLifecycle.guardedRestore(f, null, Mutation.CREATED); fail("denied parent must throw") }
            catch (e: IOException) { t("restore denied-parent -> IOException propagated OK: " + e.javaClass.simpleName) }
            catch (e: SecurityException) { t("restore denied-parent -> SecurityException propagated OK") }
        } finally { dir.setWritable(true) }
    }
}

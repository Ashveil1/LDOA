package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XdiagSpawnPrepTest {

    private fun tempDir() = java.nio.file.Files.createTempDirectory("xprep").toFile()

    @Test fun existingDirectory_ok() {
        val d = tempDir()
        try { assertNull(XdiagSpawnPrep.ensureDirectoryChecked(d)) }
        finally { d.deleteRecursively() }
    }

    @Test fun nestedMissingCreated_ok() {
        val base = tempDir()
        try {
            val nested = java.io.File(base, "a/b/c")
            assertNull(XdiagSpawnPrep.ensureDirectoryChecked(nested))
            assertTrue(nested.isDirectory)
        } finally { base.deleteRecursively() }
    }

    @Test fun fileInTheWay_failsClosed_noDeletion() {
        val base = tempDir()
        try {
            val f = java.io.File(base, "notadir")
            f.writeText("content must survive")
            val err = XdiagSpawnPrep.ensureDirectoryChecked(f)
            assertNotNull(err)
            assertTrue(err!!.contains("not a directory"))
            assertTrue(f.isFile && f.readText() == "content must survive")
        } finally { base.deleteRecursively() }
    }

    @Test fun parentIsFile_failsClosed_noDeletion() {
        val base = tempDir()
        try {
            val f = java.io.File(base, "p")
            f.writeText("x")
            val err = XdiagSpawnPrep.ensureDirectoryChecked(java.io.File(f, "child"))
            assertNotNull(err)
            assertTrue(f.isFile)
        } finally { base.deleteRecursively() }
    }

    @Test fun inspectEntries_sortedFull_missingDirNull_fileAsDirNull() {
        val base = tempDir()
        try {
            java.io.File(base, "b").writeText("x")
            java.io.File(base, "a").writeText("x")
            val check = XdiagSpawnPrep.inspectEntries(base)
            assertNotNull(check)
            assertEquals(listOf("a", "b"), check!!.entries)
            assertFalse(check.truncated)
            // Unreadable is an ERROR (null), never an empty listing:
            assertNull(XdiagSpawnPrep.inspectEntries(java.io.File(base, "missing")))
            assertNull(XdiagSpawnPrep.inspectEntries(java.io.File(base, "a")))
        } finally { base.deleteRecursively() }
    }

    @Test fun inspectEntries_collisionBeyondCap_stillRefused() {
        val base = tempDir()
        try {
            // 101 entries that sort lexically EARLIER than "X0" ('A' < 'X'
            // in byte order); a capped verdict would miss the collision -
            // the full-list verdict must not.
            for (i in 0..100) java.io.File(base, "A%04d".format(i)).writeText("x")
            java.io.File(base, "X0").writeText("x")
            val check = XdiagSpawnPrep.inspectEntries(base, cap = 100)
            assertNotNull(check)
            assertEquals(102, check!!.entries.size)
            assertEquals(listOf("X0"), check.collisions)
            assertEquals(100, check.shown.size)
            assertTrue(check.truncated)
            assertFalse(check.shown.contains("X0"))
        } finally { base.deleteRecursively() }
    }

    @Test fun unownedProxyCollisions_onlyZeroDisplayEntries() {
        assertEquals(listOf("X0", "X0-lock", ".X0-lock"),
            XdiagSpawnPrep.unownedProxyCollisions(
                listOf("X0", "X10", "X0-lock", ".X0-lock", ".X10-lock", "unrelated")))
        assertEquals(emptyList<String>(),
            XdiagSpawnPrep.unownedProxyCollisions(listOf("X10", "X10-lock", ".X10-lock")))
        assertEquals(emptyList<String>(), XdiagSpawnPrep.unownedProxyCollisions(emptyList()))
    }
}

package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XdiagAbortEvidenceTest {

    private fun coreArtifacts(): List<Pair<String, () -> String>> = listOf(
        "proxy-abort-stage.txt" to { "anchor-wait\n" },
        "proxy-spawn-argv.txt" to { "/data/app/.../libproot.so\n-r\n" },
        "proxy-spawn-env.txt" to {
            XdiagAbortEvidence.envFacts(
                mapOf("PROOT_TMP_DIR" to "/data/user/0/x/cache/proot-tmp"),
                listOf("ANDROID_DATA", "TOKEN")) },
        "proxy-identity.txt" to { "rootPid=2731\nrootStarttime=445566\n" },
        "proxy-stderr-tail.txt" to { XdiagAbortEvidence.tailText(true, "proot error: some failure\n") },
        "proxy-exit-status.txt" to {
            XdiagAbortEvidence.exitStatus(true, 1, null, false) + "\n" })

    @Test fun exitStatusMappingIsExplicitNeverGuessed() {
        assertEquals("NOT_SPAWNED (abort before pb.start())",
            XdiagAbortEvidence.exitStatus(null, null, null, notSpawned = true))
        assertEquals("EXITED code=137",
            XdiagAbortEvidence.exitStatus(true, 137, null, false))
        assertTrue(XdiagAbortEvidence.exitStatus(false, null, null, false).startsWith("STILL_ALIVE"))
        assertTrue(XdiagAbortEvidence.exitStatus(null, null, "SecurityException", false).startsWith("UNKNOWN"))
        assertTrue(XdiagAbortEvidence.exitStatus(null, null, null, false).startsWith("UNKNOWN"))
    }

    @Test fun tailTextDistinguishesNeverStartedFromEmptyCapture() {
        assertTrue(XdiagAbortEvidence.tailText(false, "").contains("drain never started"))
        assertTrue(XdiagAbortEvidence.tailText(true, "").contains("no output captured"))
        assertEquals("real content\n", XdiagAbortEvidence.tailText(true, "real content\n"))
    }

    @Test fun filterAvcLinesKeepsOnlyAuditLinesAndMostRecent() {
        val real = """
11-03 02:06:06.210  2678  2699 I TestRunner: started: mobileEntryXdiagCapture
11-03 02:06:15.053  2731  2731 W libproot.so: type=1400 audit(0.0:99): avc:  denied  { search } for  name="tests" dev="dm-38"
11-03 02:06:15.053  2731  2731 W libproot.so: type=1400 audit(0.0:100): avc:  denied  { search } for  name="tests" dev="dm-38"
11-03 02:06:25.183  2678  2699 I MobileDesktopEntryTest: m1-evidence: pre-stop mobile-desktop.log ABSENT
""".trimIndent()
        val out = XdiagAbortEvidence.filterAvcLines(real)
        assertEquals(2, out.lines().size)
        assertTrue(out.contains("audit(0.0:99)"))
        assertFalse(out.contains("TestRunner"))
        val many = (1..500).joinToString("\n") { "line$it avc: denied $it" }
        val capped = XdiagAbortEvidence.filterAvcLines(many, 200)
        assertEquals(200, capped.lines().size)
        assertTrue(capped.contains("denied 500"))
        assertFalse(capped.contains("denied 1"))
    }

    @Test fun pathFactsAreLabeledRawProbesNotProof() {
        val out = XdiagAbortEvidence.pathFacts(linkedMapOf("dataLocalTests" to "/data/local/tests"))
        assertTrue(out.contains("rawExists="))
        assertTrue(out.contains("raw File probes only"))
        assertTrue(out.contains("not confirmed absent"))
    }

    @Test fun inheritedEnvValuesAreNeverRecorded() {
        val dir = createTempDir()
        XdiagAbortEvidence.write(dir, coreArtifacts())
        val env = java.io.File(dir, "proxy-spawn-env.txt").readText()
        assertTrue(env.lines().contains("  TOKEN"))   // key name only
        assertFalse(env.contains("=TOKEN"))           // never as a value
        assertTrue(env.contains("PROOT_TMP_DIR=/data/user/0/x/cache/proot-tmp"))
    }

    @Test fun writePersistsEveryArtifactInOrderWithSummaryLast() {
        val dir = createTempDir()
        val order = mutableListOf<String>()
        val arts = coreArtifacts().map { (n, g) -> n to ({ order.add(n); g() }) }
        val outcomes = XdiagAbortEvidence.write(dir, arts)
        for ((name, _) in coreArtifacts()) assertTrue("$name missing", java.io.File(dir, name).isFile)
        assertTrue(java.io.File(dir, "proxy-abort-summary.txt").isFile)
        assertTrue(outcomes.all { it.startsWith("OK") })
        assertEquals(coreArtifacts().map { it.first }, order) // generation order preserved
        assertEquals("anchor-wait", java.io.File(dir, "proxy-abort-stage.txt").readText().trim())
        assertTrue(java.io.File(dir, "proxy-stderr-tail.txt").readText().contains("proot error"))
    }

    @Test fun failedGenerationNeverSuppressesOtherArtifacts() {
        val dir = createTempDir()
        val arts = coreArtifacts().toMutableList()
        arts.add(2, "proxy-exploding.txt" to { throw IllegalStateException("boom") })
        val outcomes = XdiagAbortEvidence.write(dir, arts)
        assertTrue(outcomes.any { it.startsWith("FAILED proxy-exploding.txt (generate)") })
        for ((name, _) in coreArtifacts()) {
            assertTrue("$name missing after sibling generation failure", java.io.File(dir, name).isFile)
        }
        assertTrue(java.io.File(dir, "proxy-abort-summary.txt").readText()
            .contains("FAILED proxy-exploding.txt (generate)"))
    }

    @Test fun failedWriteNeverSuppressesOtherArtifacts() {
        val dir = createTempDir()
        java.io.File(dir, "proxy-stderr-tail.txt").mkdir() // force write failure
        val outcomes = XdiagAbortEvidence.write(dir, coreArtifacts())
        assertTrue(outcomes.any { it.startsWith("FAILED proxy-stderr-tail.txt (write)") })
        for (name in coreArtifacts().map { it.first } - "proxy-stderr-tail.txt") {
            assertTrue("$name missing after sibling write failure", java.io.File(dir, name).isFile)
        }
    }

    @Test fun writeNeverThrowsEvenWhenDirUnusable() {
        val blocker = createTempDir()
        val asFile = java.io.File(blocker, "x"); asFile.writeText("occupied")
        val outcomes = XdiagAbortEvidence.write(java.io.File(asFile, "sub"), coreArtifacts())
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it.startsWith("FAILED") })
    }

    @Test fun boundedReturnsNullOnTimeoutAndValueOnTime() {
        assertEquals("ok", XdiagAbortEvidence.bounded(5_000) { "ok" })
        assertNull(XdiagAbortEvidence.bounded(150) { Thread.sleep(10_000); "late" })
        assertNull(XdiagAbortEvidence.bounded(5_000) { throw RuntimeException("x") })
    }
}

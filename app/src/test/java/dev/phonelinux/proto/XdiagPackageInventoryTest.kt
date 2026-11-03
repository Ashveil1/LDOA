package dev.phonelinux.proto

import org.junit.Assert.*
import org.junit.Test

class XdiagPackageInventoryTest {
    private val base = "jwm\tamd64\tinstall ok installed\t2.4.3-1build2\n" +
        "pcmanfm\tamd64\tinstall ok installed\t1.3.2-4build2\n"
    private fun rejects(raw: String) {
        try { XdiagPackageInventory.report(raw); fail("expected rejection") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun absentOptionalIsExplicit_noInventedVersion() {
        val s = XdiagPackageInventory.report(base)
        assertTrue(s.contains("optional\tlibrandr2\t-\tABSENT_FROM_DATABASE\t-\n"))
        XdiagPackageInventory.requireInstalled(s)
    }
    @Test fun sourceVerifiedCandidatesRetainActualStatusAndVersion() {
        val s = XdiagPackageInventory.report(base + "libfm4t64\tamd64\tinstall ok installed\t1.3.2-4.1build3\n")
        assertTrue(s.contains("libfm4t64\tamd64\tinstall ok installed\t1.3.2-4.1build3"))
        assertTrue(s.contains("libfm4\t-\tABSENT_FROM_DATABASE"))
    }
    @Test fun bareLegacyDatabaseEntryDoesNotMeanInstalled() {
        val s = XdiagPackageInventory.report(base + "libfm4\t\tunknown ok not-installed\t\n")
        assertTrue(s.contains("libfm4\t-\tunknown ok not-installed\t-\n"))
    }
    @Test fun requiredMissingIsRecordedThenRejected() {
        val s = XdiagPackageInventory.report(base.substringBefore("pcmanfm"))
        assertTrue(s.contains("required_missing_or_not_installed: pcmanfm"))
        try { XdiagPackageInventory.requireInstalled(s); fail("missing required") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun requiredNonInstalledIsNotEnough() {
        val s = XdiagPackageInventory.report(base.replace("pcmanfm\tamd64\tinstall ok installed", "pcmanfm\tamd64\tdeinstall ok config-files"))
        assertTrue(s.contains("required_installed: false"))
    }
    @Test fun reinstreqIsNotHealthyInstalled() {
        assertTrue(XdiagPackageInventory.report(base.replace("install ok installed", "install reinstreq installed"))
            .contains("required_installed: false"))
    }
    @Test fun multiarchRowsAreRetained() {
        val s = XdiagPackageInventory.report(base + "libx11-6\tamd64\tinstall ok installed\t1\nlibx11-6\ti386\tinstall ok installed\t2\n")
        assertTrue(s.contains("libx11-6\tamd64")); assertTrue(s.contains("libx11-6\ti386"))
    }
    @Test fun malformedAndTruncatedOutputFailClosed() {
        for (s in listOf("",base.dropLast(1),base+"proot warning\n",base+"bad\trow\n",base+"\n")) rejects(s)
    }
    @Test fun duplicateIdentityFailsClosed() { rejects(base + base.substringBefore("pcmanfm")) }
    @Test fun missingInstalledVersionFailsClosed() { rejects(base.replace("2.4.3-1build2", "")) }
    @Test fun missingInstalledArchitectureFailsClosed() { rejects(base.replace("jwm\tamd64", "jwm\t")) }
    @Test fun malformedStatusNameArchVersionFailClosed() {
        for (s in listOf(base.replace("install ok installed", "bogus"),base.replace("jwm\t", "../jwm\t"),
            base.replace("amd64", "amd 64"),base.replace("2.4.3-1build2", "bad version"))) rejects(s)
    }
    @Test fun queryFormatContainsStatusArchitectureAndVersion_literalWildcard() {
        assertEquals("\u0024{Package}\t\u0024{Architecture}\t\u0024{Status}\t\u0024{Version}\n", XdiagPackageInventory.FORMAT)
        assertEquals(listOf("dpkg-query", "-W", "-f=" + XdiagPackageInventory.FORMAT, "*"),
            XdiagPackageInventory.queryArgs().toList())
        assertEquals(listOf("jwm", "pcmanfm"), XdiagPackageInventory.required)
    }
}

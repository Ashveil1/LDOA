package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM fixture for the EXACT EntrySeamGate predicates XServer.openDesktop
 *  consults. Negative cases first: every shape that must NEVER stack two
 *  sessions on :0. Each case prints a transcript line (JUnit XML
 *  system-out) so the raw run output is the evidence. */
class EntrySeamGateTest {

    private fun t(line: String) = println("SEAM $line")

    @Test fun refuse_otherMarkerSet() {
        assertTrue(EntrySeamGate.refuseForOtherActive(true, null, "apt:MOBILE"))
        assertTrue(EntrySeamGate.refuseForOtherActive(true, "apt:MOBILE", "apt:MOBILE"))
        t("refuse: other durable marker set, live tag irrelevant")
    }

    @Test fun refuse_trackedLiveOtherOwner_markerAbsent() {
        // Mid-start GNOME (marker not yet written) must still block MOBILE.
        assertTrue(EntrySeamGate.refuseForOtherActive(false, "apt:GNOME", "apt:MOBILE"))
        // Mid-start MOBILE must block GNOME.
        assertTrue(EntrySeamGate.refuseForOtherActive(false, "apt:MOBILE", "apt:GNOME"))
        // A live Alpine session blocks both APT modes.
        assertTrue(EntrySeamGate.refuseForOtherActive(false, "apk:alpine", "apt:GNOME"))
        assertTrue(EntrySeamGate.refuseForOtherActive(false, "apk:alpine", "apt:MOBILE"))
        t("refuse: tracked-live other owner with NO marker (mid-start hole)")
    }

    @Test fun proceed_sameOwnerOrNothingLive() {
        assertFalse(EntrySeamGate.refuseForOtherActive(false, "apt:GNOME", "apt:GNOME"))
        assertFalse(EntrySeamGate.refuseForOtherActive(false, "apt:MOBILE", "apt:MOBILE"))
        assertFalse(EntrySeamGate.refuseForOtherActive(false, null, "apt:MOBILE"))
        t("proceed: same owner tag (own reuse path) or nothing tracked-live")
    }

    @Test fun mobile_reuse_onlyConfirmedLive() {
        assertEquals(EntrySeamGate.MobileSameMode.REUSE, EntrySeamGate.mobileSameMode(true, true))
        t("mobile reuse: marker + tracked-live")
    }

    @Test fun mobile_refuse_unconfirmedLive() {
        // Live session without the confirmed marker: mid-start or an
        // attempt that threw - never reuse, never stack.
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNCONFIRMED, EntrySeamGate.mobileSameMode(false, true))
        t("mobile refuse: tracked-live WITHOUT marker")
    }

    @Test fun mobile_staleMarker_clearsAndProceeds() {
        // Session died with the app process: marker stale, nothing live.
        assertEquals(EntrySeamGate.MobileSameMode.CLEAR_STALE_PROCEED, EntrySeamGate.mobileSameMode(true, false))
        t("mobile stale marker: clear + fresh start (data-preserving)")
    }

    @Test fun mobile_coldClean_proceeds() {
        assertEquals(EntrySeamGate.MobileSameMode.PROCEED, EntrySeamGate.mobileSameMode(false, false))
        t("mobile cold clean: proceed")
    }
}

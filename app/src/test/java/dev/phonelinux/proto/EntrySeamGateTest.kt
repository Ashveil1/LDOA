package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM fixture for the EXACT EntrySeamGate predicates XServer.openDesktop
 *  consults. Negative cases first: every shape that must NEVER stack two
 *  sessions on :0, and every uncertainty that must fail closed instead of
 *  being converted to absence. Each case prints a transcript line (JUnit
 *  XML system-out) so the raw run output is the evidence. */
class EntrySeamGateTest {

    private fun t(line: String) = println("SEAM $line")

    @Test fun liveKind_absent_onlyWhenNoHandle() {
        assertEquals(EntrySeamGate.LiveKind.ABSENT, EntrySeamGate.liveKind(false, false, false, false))
        assertEquals(EntrySeamGate.LiveKind.ABSENT, EntrySeamGate.liveKind(false, true, true, true))
        t("ABSENT: no tracked handle, other facts irrelevant")
    }

    @Test fun liveKind_unknown_missingIdentity_deadRoot_reusedPid() {
        assertEquals(EntrySeamGate.LiveKind.UNKNOWN, EntrySeamGate.liveKind(true, false, true, false)) // identity capture missing
        assertEquals(EntrySeamGate.LiveKind.UNKNOWN, EntrySeamGate.liveKind(true, true, false, false)) // root exited: tracees may live
        assertEquals(EntrySeamGate.LiveKind.UNKNOWN, EntrySeamGate.liveKind(true, true, true, false)) // PID reused: our root's fate unknown
        assertEquals(EntrySeamGate.LiveKind.UNKNOWN, EntrySeamGate.liveKind(true, true, false, true)) // dead root even if ident string matches
        t("UNKNOWN: handle present but identity missing / root dead / pid reused")
    }

    @Test fun liveKind_verifiedLive_onlyFullIdentityMatch() {
        assertEquals(EntrySeamGate.LiveKind.VERIFIED_LIVE, EntrySeamGate.liveKind(true, true, true, true))
        t("VERIFIED_LIVE: handle + identity + alive + (starttime,uid) match")
    }

    @Test fun refuse_otherMarkerSet() {
        assertTrue(EntrySeamGate.refuseForOtherActive(true, EntrySeamGate.LiveKind.ABSENT, null, "apt:MOBILE"))
        assertTrue(EntrySeamGate.refuseForOtherActive(true, EntrySeamGate.LiveKind.VERIFIED_LIVE, "apt:MOBILE", "apt:MOBILE"))
        t("refuse: other durable marker set")
    }

    @Test fun refuse_trackedOtherOwner_verifiedOrUnknown() {
        assertTrue(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.VERIFIED_LIVE, "apt:GNOME", "apt:MOBILE"))
        assertTrue(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.UNKNOWN, "apt:GNOME", "apt:MOBILE")) // dead GNOME root, tracees may live
        assertTrue(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.UNKNOWN, "apt:MOBILE", "apt:GNOME"))
        assertTrue(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.VERIFIED_LIVE, "apk:alpine", "apt:MOBILE"))
        t("refuse: other owner VERIFIED_LIVE or UNKNOWN (uncertainty never becomes absence)")
    }

    @Test fun proceed_sameOwnerUnknown_orAbsent() {
        assertFalse(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.UNKNOWN, "apt:GNOME", "apt:GNOME")) // GNOME's own machinery decides (pre-existing)
        assertFalse(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.VERIFIED_LIVE, "apt:GNOME", "apt:GNOME"))
        assertFalse(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.ABSENT, null, "apt:MOBILE"))
        t("proceed: same-owner (own machinery) or genuinely ABSENT")
    }

    @Test fun mobile_reuse_onlyVerifiedLiveConfirmed() {
        assertEquals(EntrySeamGate.MobileSameMode.REUSE, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.VERIFIED_LIVE))
        t("mobile REUSE: marker + VERIFIED_LIVE")
    }

    @Test fun mobile_refuse_unconfirmedVerifiedLive() {
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNCONFIRMED, EntrySeamGate.mobileSameMode(false, EntrySeamGate.LiveKind.VERIFIED_LIVE))
        t("mobile REFUSE_UNCONFIRMED: live, no marker (mid-start/thrown)")
    }

    @Test fun mobile_refuse_unknown_neverAutoClears() {
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.UNKNOWN))
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(false, EntrySeamGate.LiveKind.UNKNOWN))
        // Post-restart stale marker: no handle in this process, but guest
        // tracees from the dead process may live (orphan root cause) -
        // fail closed to Stop-to-reset, never clear-and-launch.
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.ABSENT))
        t("mobile REFUSE_UNKNOWN: UNKNOWN any marker + stale marker with no handle")
    }

    @Test fun mobile_proceed_onlyColdClean() {
        assertEquals(EntrySeamGate.MobileSameMode.PROCEED, EntrySeamGate.mobileSameMode(false, EntrySeamGate.LiveKind.ABSENT))
        t("mobile PROCEED: no marker + nothing tracked")
    }
}

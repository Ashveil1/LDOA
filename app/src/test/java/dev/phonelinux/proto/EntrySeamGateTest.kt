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

    @Test fun refuse_missingOwnerTag_nonAbsent() {
        // A tracked session without an owner tag is never "same owner".
        assertTrue(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.VERIFIED_LIVE, null, "apt:MOBILE"))
        assertTrue(EntrySeamGate.refuseForOtherActive(false, EntrySeamGate.LiveKind.UNKNOWN, null, "apt:GNOME"))
        t("refuse: non-ABSENT with MISSING owner tag (unattributed process)")
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

    @Test fun mobile_reuse_onlyVerifiedLiveConfirmedAndTagged() {
        assertEquals(EntrySeamGate.MobileSameMode.REUSE, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.VERIFIED_LIVE, "apt:MOBILE"))
        t("mobile REUSE: marker + VERIFIED_LIVE + apt:MOBILE tag")
    }

    @Test fun mobile_neverReuse_unattributedOrForeignLive() {
        // A live process not attributed to MOBILE is never reused as one.
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.VERIFIED_LIVE, null))
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.VERIFIED_LIVE, "apt:GNOME"))
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.VERIFIED_LIVE, "apk:alpine"))
        t("mobile REFUSE_UNKNOWN: VERIFIED_LIVE but null/foreign tag (no reuse)")
    }

    @Test fun mobile_refuse_unconfirmedVerifiedLive() {
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNCONFIRMED, EntrySeamGate.mobileSameMode(false, EntrySeamGate.LiveKind.VERIFIED_LIVE, "apt:MOBILE"))
        t("mobile REFUSE_UNCONFIRMED: live, no marker (mid-start/thrown)")
    }

    @Test fun mobile_refuse_unknown_neverAutoClears() {
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.UNKNOWN, null))
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(false, EntrySeamGate.LiveKind.UNKNOWN, "apt:MOBILE"))
        // Post-restart stale marker: no handle in this process, but guest
        // tracees from the dead process may live (orphan root cause) -
        // fail closed to Stop-to-reset, never clear-and-launch.
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN, EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.ABSENT, null))
        t("mobile REFUSE_UNKNOWN: UNKNOWN any marker + stale marker with no handle")
    }

    @Test fun transition_unknownStopUnverified_blockThenVerifiedCleanup_proceed() {
        // 1. Post-restart stale marker, nothing tracked: REFUSE_UNKNOWN.
        assertEquals(EntrySeamGate.MobileSameMode.REFUSE_UNKNOWN,
            EntrySeamGate.mobileSameMode(true, EntrySeamGate.LiveKind.ABSENT, null))
        // 2. User Stops; cleanup UNVERIFIED (no identity -> tree walk
        //    skipped): durable block set for THIS boot. The next open
        //    REFUSES even though Stop cleared the active marker - the
        //    bypass (clear -> PROCEED) stays closed.
        assertTrue(EntrySeamGate.mobileRecoveryBlocked(true, "boot-A", "boot-A"))
        // 3. Missing boot ids fail closed (still blocked).
        assertTrue(EntrySeamGate.mobileRecoveryBlocked(true, null, "boot-A"))
        assertTrue(EntrySeamGate.mobileRecoveryBlocked(true, "boot-A", null))
        // 4. A device reboot lifts the block: every process is dead.
        assertFalse(EntrySeamGate.mobileRecoveryBlocked(true, "boot-A", "boot-B"))
        // 4b. The SAME reboot also voids the boot-stamped active marker:
        //     after the void, the open really proceeds (no second reboot
        //     demanded by a REFUSE_UNKNOWN on the stale marker).
        assertTrue(EntrySeamGate.stalePrefFromPriorBoot(true, "boot-A", "boot-B"))
        assertFalse(EntrySeamGate.stalePrefFromPriorBoot(true, "boot-A", "boot-A"))
        // 4c. Missing stamps fail closed: the pref stays authoritative.
        assertFalse(EntrySeamGate.stalePrefFromPriorBoot(true, null, "boot-B"))
        assertFalse(EntrySeamGate.stalePrefFromPriorBoot(true, "boot-A", null))
        // 5. A VERIFIED owned cleanup clears the block: open proceeds.
        assertFalse(EntrySeamGate.mobileRecoveryBlocked(false, "boot-A", "boot-A"))
        assertEquals(EntrySeamGate.MobileSameMode.PROCEED,
            EntrySeamGate.mobileSameMode(false, EntrySeamGate.LiveKind.ABSENT, null))
        t("transition: unknown->Stop-unverified->REFUSE(blocked); verified-cleanup->PROCEED; reboot lifts")
    }

    @Test fun mobile_proceed_onlyColdClean() {
        assertEquals(EntrySeamGate.MobileSameMode.PROCEED, EntrySeamGate.mobileSameMode(false, EntrySeamGate.LiveKind.ABSENT, null))
        t("mobile PROCEED: no marker + nothing tracked")
    }
}

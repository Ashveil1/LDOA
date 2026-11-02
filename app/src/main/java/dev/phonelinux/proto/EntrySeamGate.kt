package dev.phonelinux.proto

/** Pure decision core of the desktop entry seam (M1): no Android types,
 *  so the JVM fixture (EntrySeamGateTest) exercises the EXACT predicates
 *  XServer.openDesktop consults. Owner tags: "apk:<distroId>",
 *  "apt:GNOME", "apt:MOBILE".
 *
 *  ABSENT-vs-UNKNOWN is explicit: a missing handle, a missing identity
 *  capture, a dead root or a reused PID all mean the owned tree's state
 *  is UNKNOWN - guest tracees may still live (proot can be SIGKILLed
 *  while tracees escape, the known orphan root cause). UNKNOWN is never
 *  converted to absence and never auto-recovered: it fails closed with
 *  a Stop-to-reset instruction. */
internal object EntrySeamGate {

    enum class LiveKind { VERIFIED_LIVE, UNKNOWN, ABSENT }

    /** Classify the tracked session from raw ownership facts. */
    fun liveKind(hasHandle: Boolean, hasIdentity: Boolean, alive: Boolean, identityMatch: Boolean): LiveKind = when {
        !hasHandle -> LiveKind.ABSENT
        hasIdentity && alive && identityMatch -> LiveKind.VERIFIED_LIVE
        else -> LiveKind.UNKNOWN // missing identity, dead root, or PID reused
    }

    /** Cross-desktop mutual exclusion: refuse when the OTHER desktop's
     *  durable marker is set OR a tracked session in ANY non-absent state
     *  has a DIFFERENT or MISSING owner tag (VERIFIED_LIVE other =
     *  active; UNKNOWN other = its tracees may live; a missing tag is
     *  never treated as "same owner"). Same-owner UNKNOWN passes: that
     *  mode's own machinery decides (GNOME: pre-existing fresh launch;
     *  MOBILE: refused in the branch). */
    fun refuseForOtherActive(otherMarkerActive: Boolean, liveKind: LiveKind, liveOwnerTag: String?, myOwnerTag: String): Boolean =
        otherMarkerActive || (liveKind != LiveKind.ABSENT && liveOwnerTag != myOwnerTag)

    /** Boot consistency for any boot-stamped pref: a pref written under
     *  a PRIOR boot refers to processes that certainly died (nothing
     *  survives a reboot), so it is stale on sight. Missing stamps fail
     *  closed (NOT stale: the pref stays authoritative). */
    fun stalePrefFromPriorBoot(prefSet: Boolean, prefBoot: String?, currentBootId: String?): Boolean =
        prefSet && prefBoot != null && currentBootId != null && prefBoot != currentBootId

    /** Durable recovery block: true while an unverified-cleanup block
     *  applies to THIS boot. A device reboot (boot id change) means every
     *  guest process is certainly dead, so the block never survives it.
     *  Missing ids fail closed (still blocked). */
    fun mobileRecoveryBlocked(blocked: Boolean, blockedBootId: String?, currentBootId: String?): Boolean =
        blocked && (blockedBootId == null || currentBootId == null || blockedBootId == currentBootId)

    /** Same-mode decision for a MOBILE open (state after the cross-mode
     *  guard, so a non-absent tracked session here is tagged MOBILE). */
    enum class MobileSameMode { REUSE, REFUSE_UNCONFIRMED, REFUSE_UNKNOWN, PROCEED }

    fun mobileSameMode(sameMarker: Boolean, liveKind: LiveKind, liveOwnerTag: String?): MobileSameMode = when {
        liveKind == LiveKind.UNKNOWN -> MobileSameMode.REFUSE_UNKNOWN
        liveKind == LiveKind.VERIFIED_LIVE && liveOwnerTag != "apt:MOBILE" ->
            MobileSameMode.REFUSE_UNKNOWN // live process not attributed to MOBILE: never reuse it
        liveKind == LiveKind.VERIFIED_LIVE && sameMarker -> MobileSameMode.REUSE
        liveKind == LiveKind.VERIFIED_LIVE -> MobileSameMode.REFUSE_UNCONFIRMED
        sameMarker -> MobileSameMode.REFUSE_UNKNOWN // post-restart stale marker: orphan uncertainty, never auto-clear
        else -> MobileSameMode.PROCEED
    }
}

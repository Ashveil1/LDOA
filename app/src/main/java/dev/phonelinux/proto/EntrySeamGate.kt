package dev.phonelinux.proto

/** Pure decision core of the desktop entry seam (M1): no Android types,
 *  so the JVM fixture (EntrySeamGateTest) exercises the EXACT predicates
 *  XServer.openDesktop consults. Owner tags: "apk:<distroId>",
 *  "apt:GNOME", "apt:MOBILE". */
internal object EntrySeamGate {

    /** Cross-desktop mutual exclusion: refuse when the OTHER desktop's
     *  durable marker is set OR a tracked-live session belongs to a
     *  different owner tag (covers mid-start, marker not yet written). */
    fun refuseForOtherActive(otherMarkerActive: Boolean, liveOwnerTag: String?, myOwnerTag: String): Boolean =
        otherMarkerActive || (liveOwnerTag != null && liveOwnerTag != myOwnerTag)

    /** Same-mode decision for a MOBILE open. */
    enum class MobileSameMode { REUSE, REFUSE_UNCONFIRMED, CLEAR_STALE_PROCEED, PROCEED }

    fun mobileSameMode(sameMarker: Boolean, sameLive: Boolean): MobileSameMode = when {
        sameLive && sameMarker -> MobileSameMode.REUSE
        sameLive -> MobileSameMode.REFUSE_UNCONFIRMED
        sameMarker -> MobileSameMode.CLEAR_STALE_PROCEED
        else -> MobileSameMode.PROCEED
    }
}

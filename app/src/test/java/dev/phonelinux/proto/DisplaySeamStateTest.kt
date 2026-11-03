package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for XServer.DisplaySeamState - the REAL token/attempt
 *  reservation logic (pure, no Android). Caller binding is enforced
 *  start exclusion in a fresh world: the consumer must be exactly the
 *  first start after arming; anything else poisons ownership. */
class DisplaySeamStateTest {
    private fun freshState() = XServer.DisplaySeamState()
    private fun arm(s: XServer.DisplaySeamState, display: String = ":10",
                    atSeq: Long = 4L): Long =
        s.reserve(display, isDebug = true, looksEmulator = true,
            serverAlive = false, attemptInFlight = false, stopBusy = false,
            currentAttemptSeq = atSeq)

    @Test fun reserve_requiresDebugEmulatorFreshWorldAndCanonicalDisplay() {
        assertThrows(IllegalStateException::class.java) {
            freshState().reserve(":10", false, true, false, false, false, 0L) }
        assertThrows(IllegalStateException::class.java) {
            freshState().reserve(":10", true, false, false, false, false, 0L) }
        assertThrows(IllegalStateException::class.java) {
            freshState().reserve(":10", true, true, true, false, false, 0L) }
        assertThrows(IllegalStateException::class.java) {
            freshState().reserve(":10", true, true, false, true, false, 0L) }
        assertThrows(IllegalStateException::class.java) {
            freshState().reserve(":10", true, true, false, false, true, 0L) }
        for (bad in listOf(":0", ":010", ":1000", ":1a", "", ": 1")) {
            assertThrows(IllegalStateException::class.java) {
                freshState().reserve(bad, true, true, false, false, false, 0L) }
        }
        arm(freshState(), ":1"); arm(freshState(), ":999")
    }

    @Test fun reserve_twiceWithoutConsumeOrRelease_throws() {
        val s = freshState(); arm(s)
        assertThrows(IllegalStateException::class.java) {
            s.reserve(":11", true, true, false, false, false, 4L) }
    }

    @Test fun consume_once_onlyAndReturnsDisplay() {
        val s = freshState(); val token = arm(s, atSeq = 6L)
        assertEquals(":10", s.consume(attemptSeq = 7L, isDebug = true, presentedToken = token))
        assertNull(s.consume(attemptSeq = 8L, isDebug = true, presentedToken = token))
        assertEquals(token, s.consumedBy)
        assertEquals(7L, s.consumedAttemptSeq)
    }

    @Test fun consume_notDebug_neverHandsOutDisplay() {
        val s = freshState(); val token = arm(s, atSeq = 1L)
        assertNull(s.consume(attemptSeq = 2L, isDebug = false, presentedToken = token))
        // failed consume must not clear the reservation
        assertEquals(":10", s.consume(attemptSeq = 2L, isDebug = true, presentedToken = token))
    }

    @Test fun consume_outOfSequence_poisonsOwnershipPermanently() {
        val s = freshState(); val token = arm(s, atSeq = 4L)
        // A start was SKIPPED (unintended start consumed the first slot):
        // attempt 6 is not armSeq+1 - consume fails closed and poisons.
        assertNull(s.consume(attemptSeq = 6L, isDebug = true, presentedToken = token))
        assertTrue(s.violated)
        // Nothing can ever be owned under this token again.
        assertFalse(s.ownedBy(token, 6L, true))
        assertFalse(s.ownedBy(token, 5L, true))
        // And no later consume can resurrect attribution.
        assertNull(s.consume(attemptSeq = 7L, isDebug = true, presentedToken = token))
        assertFalse(s.ownedBy(token, 7L, true))
    }

    @Test fun release_foreignTokenThrows_consumedIsNoOp() {
        val s = freshState(); val token = arm(s)
        assertThrows(IllegalStateException::class.java) { s.release(token + 1, false) }
        s.release(token, serverAlive = false)
        assertNull(s.reservation)
        val s2 = freshState(); val t2 = arm(s2, atSeq = 1L)
        s2.consume(2L, true, t2)
        s2.release(t2, serverAlive = true) // no-op, must not throw or clear
        assertEquals(t2, s2.consumedBy)
    }

    @Test fun release_refusesWhileServerAlive() {
        val s = freshState(); val token = arm(s)
        assertThrows(IllegalStateException::class.java) { s.release(token, true) }
    }

    @Test fun ownedBy_onlyLatestConsumingAttemptOwns() {
        val s = freshState(); val token = arm(s, atSeq = 4L)
        s.consume(attemptSeq = 5L, isDebug = true, presentedToken = token)
        assertTrue(s.ownedBy(token, currentAttemptSeq = 5L, serverAlive = true))
        // a NEWER start invalidates the stale attribution
        assertFalse(s.ownedBy(token, currentAttemptSeq = 6L, serverAlive = true))
        assertFalse(s.ownedBy(token, currentAttemptSeq = 5L, serverAlive = false))
        assertFalse(s.ownedBy(token + 9, currentAttemptSeq = 5L, serverAlive = true))
        val s2 = freshState(); val t2 = arm(s2)
        assertFalse(s2.ownedBy(t2, currentAttemptSeq = 1L, serverAlive = true))
    }

    @Test fun hostileCallerWithoutCapability_isRefusedAndPoisons() {
        val s = freshState(); val token = arm(s, atSeq = 2L)
        // Hostile sequence from review: an unintended start (no token)
        // reaches consume as the first start after arming - it is
        // REFUSED and the reservation is poisoned, never consumed.
        assertNull(s.consume(attemptSeq = 3L, isDebug = true, presentedToken = null))
        assertTrue(s.violated)
        assertNull(s.reservation)
        assertNull(s.consumedBy)
        // A later "our" start presenting the real token still gets
        // nothing - misattribution is impossible, the diagnostic aborts.
        assertNull(s.consume(attemptSeq = 4L, isDebug = true, presentedToken = token))
        assertFalse(s.ownedBy(token, currentAttemptSeq = 4L, serverAlive = true))
        assertFalse(s.ownedBy(token, currentAttemptSeq = 3L, serverAlive = true))
    }

    @Test fun wrongCapability_isRefusedLikeNoCapability() {
        val s = freshState(); arm(s, atSeq = 2L)
        assertNull(s.consume(attemptSeq = 3L, isDebug = true, presentedToken = 9999L))
        assertTrue(s.violated)
    }

    @Test fun intendedCallerWithCapability_consumesAndOwns() {
        val s = freshState(); val token = arm(s, atSeq = 2L)
        assertEquals(":10", s.consume(attemptSeq = 3L, isDebug = true, presentedToken = token))
        assertTrue(s.ownedBy(token, currentAttemptSeq = 3L, serverAlive = true))
        // Preparing-reattach does not bump the sequence: following OUR
        // in-flight start keeps attribution. A genuinely NEW start (seq
        // 4) invalidates it.
        assertFalse(s.ownedBy(token, currentAttemptSeq = 4L, serverAlive = true))
    }

    @Test fun interveningStartThenDelete_deleteGuardRefuses() {
        val s = freshState(); val token = arm(s, atSeq = 2L)
        s.consume(attemptSeq = 3L, isDebug = true, presentedToken = token)
        // Intervening newer start bumps the live attempt sequence: the
        // cleanup-time guard must refuse even though token == consumedBy.
        assertEquals(token, s.consumedBy)
        assertFalse(s.ownedBy(token, 4L, true))
    }

    @Test fun reset_clearsEverything() {
        val s = freshState(); val token = arm(s, atSeq = 2L)
        s.consume(3L, true, token)
        s.resetForTest()
        assertNull(s.reservation); assertNull(s.consumedBy); assertNull(s.consumedAttemptSeq)
        assertNull(s.armAttemptSeq); assertFalse(s.violated)
        assertFalse(s.ownedBy(token, 3L, true))
    }
}

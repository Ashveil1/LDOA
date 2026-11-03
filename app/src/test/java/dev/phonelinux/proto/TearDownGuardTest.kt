package dev.phonelinux.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Regression for the early-skip teardown hazard: JUnit4's RunAfters wraps
 *  RunBefores, so @After runs in a finally EVEN WHEN @Before fails an
 *  assumption. MobileDesktopEntryTest.tearDown is guarded on
 *  ::ctx.isInitialized/::device.isInitialized for exactly this reason.
 *  These tests drive a REAL JUnit runner (JUnitCore + RunListener), not a
 *  seam: fixtures run real @Before-assumption + @After lifecycles. */
class TearDownGuardTest {

    /** Mirror of the guarded teardown shape used by MobileDesktopEntryTest.
     *  Directly scanned by Gradle too: assumption-skip -> ignored, green. */
    class GuardedSkipFixture {
        private lateinit var field: String
        @Before fun setUp() {
            assumeTrue("dispatch-gated - skipped", false)
            field = "initialized"
        }
        @After fun tearDown() {
            guardedAfterRan.set(true)
            if (!::field.isInitialized) return
            throw IllegalStateException("unreachable after skip")
        }
        @Test fun noop() { }
    }

    /** Control: WITHOUT the guard the same lifecycle throws on the
     *  uninitialized lateinit. Armed only under JUnitCore via a system
     *  property so the direct Gradle scan (which also sees this class)
     *  stays an ignored, green assumption-skip. */
    class UnguardedSkipFixture {
        private lateinit var field: String
        @Before fun setUp() {
            assumeTrue("dispatch-gated - skipped", false)
            field = "initialized"
        }
        @After fun tearDown() {
            unguardedAfterRan.set(true)
            if (System.getProperty(ARM_PROP) == "1") field.length // UPAE after a skip
        }
        @Test fun noop() { }
    }

    companion object {
        private const val ARM_PROP = "ldoa.teardownGuard.armUnguarded"
        private val guardedAfterRan = AtomicBoolean(false)
        private val unguardedAfterRan = AtomicBoolean(false)

        private class Events : RunListener() {
            val failures = CopyOnWriteArrayList<Failure>()
            val ignored = CopyOnWriteArrayList<String>()
            override fun testFailure(f: Failure) { failures.add(f) }
            override fun testIgnored(d: org.junit.runner.Description?) {
                ignored.add(d?.methodName ?: "?") }
        }

        private fun runObserved(cls: Class<*>): Events {
            val core = JUnitCore()
            val ev = Events()
            core.addListener(ev)
            core.run(cls)
            core.removeListener(ev)
            return ev
        }
    }

    @Test fun afterRunsAfterBeforeAssumption_andGuardPreventsError() {
        guardedAfterRan.set(false)
        val ev = runObserved(GuardedSkipFixture::class.java)
        assertTrue("@After MUST run even though @Before assumption-skipped " +
            "(RunAfters finally) - if this fails, JUnit semantics changed " +
            "and the MobileDesktopEntryTest guard needs re-review",
            guardedAfterRan.get())
        assertEquals("guarded teardown must surface zero failures: " +
            ev.failures.joinToString { it.exception.toString() },
            0, ev.failures.size)
    }

    @Test fun unguardedAfterAfterBeforeAssumption_throwsOnLateinit_provingHazardIsReal() {
        unguardedAfterRan.set(false)
        System.setProperty(ARM_PROP, "1")
        try {
            val ev = runObserved(UnguardedSkipFixture::class.java)
            assertTrue("@After ran despite the @Before assumption", unguardedAfterRan.get())
            assertTrue("unguarded teardown must throw on the uninitialized " +
                "lateinit after an early skip - got: " +
                ev.failures.joinToString { it.exception.toString() },
                ev.failures.any {
                    it.trace.contains("UninitializedPropertyAccessException") })
        } finally {
            System.clearProperty(ARM_PROP)
        }
    }
}

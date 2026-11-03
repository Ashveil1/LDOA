package dev.phonelinux.proto
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class XdiagCaptureWorkerTest {
    @Test fun startFailureCleansAndFreezesUnknown_preservesPrimaryAndCleanup() {
        val c=XdiagByteCapture(Long.MAX_VALUE)
        val primary=OutOfMemoryError("fixture");val cleanup=Exception("cleanup");var attempted=false
        val t=try {
            XdiagCaptureWorker.start(c,{},starter={throw primary},cleanup={attempted=true;throw cleanup});null
        } catch(t:Throwable){t}
        assertSame(primary,t);assertTrue(attempted);assertSame(cleanup,primary.suppressed.single())
        assertFalse(c.snapshot().complete);assertTrue(c.metadata().contains("process_rc: unavailable"))
        assertTrue(c.metadata().contains("wrapper: java.lang.OutOfMemoryError"))
    }
    @Test fun blockedCloseCannotDelayStopOrCallerBeyondJoin() {
        val release=CountDownLatch(1);val started=CountDownLatch(1);val done=CountDownLatch(1)
        var stopped=false
        val begin=System.nanoTime()
        try {
            val note=XdiagCaptureWorker.cleanupAndClose({stopped=true},{
                assertTrue(stopped);started.countDown();release.await();done.countDown()
            },25)
            assertTrue(stopped);assertTrue(started.await(1,TimeUnit.SECONDS))
            assertTrue(note.contains("close_worker_alive: true"))
            assertTrue((System.nanoTime()-begin)/1_000_000<1000)
        } finally { release.countDown() }
        assertTrue(done.await(1,TimeUnit.SECONDS))
    }
    @Test fun zeroOrNegativeJoinRejectedBeforeWork() {
        for (ms in listOf(0L,-1L)) {
            var touched=false
            val t=try {XdiagCaptureWorker.cleanupAndClose({touched=true},{touched=true},ms);null}
                catch(t:IllegalArgumentException){t}
            assertNotNull(t);assertFalse(touched)
        }
    }

    @Test fun cleanupFailureStillAttemptsClose() {
        var closed=false
        val note=XdiagCaptureWorker.cleanupAndClose({throw IllegalStateException("fixture")},{closed=true},1000)
        assertTrue(closed);assertTrue(note.contains("stop_error: java.lang.IllegalStateException"))
    }
}

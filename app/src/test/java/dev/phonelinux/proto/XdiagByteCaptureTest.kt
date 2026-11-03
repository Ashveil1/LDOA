package dev.phonelinux.proto

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class XdiagByteCaptureTest {
    private fun bad(c: XdiagByteCapture) {
        try { c.requireComplete(); fail("expected UNKNOWN") } catch (_: IllegalArgumentException) { }
    }
    @Test fun exactLimitIsCompleteOnlyAfterEofAndJoin() {
        val c = XdiagByteCapture(10, { 0 }, 8)
        c.drain(ByteArrayInputStream("12345678".toByteArray()))
        bad(c); c.joined(0,false); c.requireComplete()
        assertEquals("12345678",c.bytes().toString(Charsets.UTF_8))
    }
    @Test fun oneByteOverNeverValidSubsetSuccess() {
        val c = XdiagByteCapture(10,{0},4)
        c.drain(ByteArrayInputStream("ok!\nx".toByteArray())); c.joined(0,false);bad(c)
        assertEquals("ok!\n",c.bytes().toString(Charsets.UTF_8))
        assertTrue(c.metadata().contains("overflow: true"))
    }
    @Test fun newlineTerminatedRequiredSubsetCannotPassOverflow() {
        val b="jwm\tamd64\tinstall ok installed\t1\npcmanfm\tamd64\tinstall ok installed\t2\n".toByteArray()
        val c=XdiagByteCapture(10,{0},b.size)
        c.drain(ByteArrayInputStream(b+"extra".toByteArray()));c.joined(0,false);bad(c)
        assertTrue(XdiagPackageInventory.report(c.bytes().toString(Charsets.UTF_8)).contains("required_installed: true"))
    }
    @Test fun readErrorIsRetainedAndNotSuccess() {
        val c=XdiagByteCapture(10,{0})
        c.drain(object:InputStream(){ override fun read():Int=throw java.io.IOException("fixture") })
        c.joined(0,false);bad(c);assertTrue(c.metadata().contains("java.io.IOException: fixture"))
    }
    @Test fun deadlineBeforeReadIsDistinct() {
        val c=XdiagByteCapture(10,{10});c.drain(ByteArrayInputStream(byteArrayOf(1)))
        c.joined(0,false);bad(c);assertEquals(0,c.bytes().size);assertTrue(c.metadata().contains("deadline: true"))
    }
    @Test fun lateReadOrLateEofIsNotComplete() {
        var now=0L;val c=XdiagByteCapture(10,{now})
        c.drain(object:InputStream(){override fun read():Int {now=10;return -1}})
        c.joined(0,false);bad(c)
    }
    @Test fun liveDrainAtJoinStaysUnknownEvenWithEof() {
        val c=XdiagByteCapture(10,{0});c.drain(ByteArrayInputStream(byteArrayOf()))
        c.joined(0,true);bad(c)
    }
    @Test fun noNewlineSynthesis() {
        val c=XdiagByteCapture(10,{0});c.drain(ByteArrayInputStream("abc".toByteArray()))
        c.joined(0,false);c.requireComplete();assertEquals("abc",c.bytes().toString(Charsets.UTF_8))
    }
    @Test fun rcIsIndependentFromCaptureCompleteness() {
        val c=XdiagByteCapture(10,{0});c.drain(ByteArrayInputStream(byteArrayOf()))
        c.joined(7,false);c.requireComplete();assertTrue(c.metadata().contains("process_rc: 7"))
    }
    @Test fun overflowStopsReadingPromptly() {
        var reads=0
        val c=XdiagByteCapture(10,{0},4)
        c.drain(object:InputStream(){
            override fun read():Int=throw AssertionError("single read unused")
            override fun read(b:ByteArray,o:Int,n:Int):Int {reads++; b.fill(65);return n}
        })
        assertEquals(1,reads);assertTrue(c.failed());c.joined(-1,false);bad(c)
    }
    @Test fun liveWorkerCannotChangeFrozenSnapshot() {
        val c=XdiagByteCapture(10,{0})
        c.joined(0,true)
        val before=c.snapshot()
        c.drain(ByteArrayInputStream("late\n".toByteArray()))
        assertArrayEquals(before.bytes,c.snapshot().bytes)
        assertEquals(before.metadata,c.snapshot().metadata);bad(c)
    }
    @Test fun retentionNeverExceedsCapAcrossChunks() {
        val c=XdiagByteCapture(10,{0},5000);c.drain(ByteArrayInputStream(ByteArray(100000)))
        c.joined(0,false);bad(c);assertEquals(5000,c.bytes().size)
    }
    @Test fun frozenCaptureDoesNotReadAgain() {
        val c=XdiagByteCapture(10,{0});c.joined(0,true)
        c.drain(object:InputStream(){override fun read():Int=throw AssertionError("must not read")})
        assertFalse(c.metadata().contains("AssertionError"));bad(c)
    }
    @Test fun zeroProgressReadIsUnknown() {
        val c=XdiagByteCapture(10,{0})
        c.drain(object:InputStream(){override fun read():Int=0
            override fun read(b:ByteArray,o:Int,n:Int):Int=0})
        c.joined(0,false);bad(c);assertTrue(c.metadata().contains("zero-progress"))
    }
    @Test fun malformedUtf8FailsDecode() {
        val c=XdiagByteCapture(10,{0});c.drain(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)))
        c.joined(0,false)
        try {c.decode();fail()}catch(_:java.nio.charset.CharacterCodingException){}
    }
    @Test fun wrapperFailureBeforeJoinIsPreservedAndFrozen() {
        val c=XdiagByteCapture(10,{0});c.wrapperFailure(InterruptedException("fixture"));c.joined(null,true)
        bad(c);assertTrue(c.metadata().contains("wrapper: java.lang.InterruptedException"))
        assertTrue(c.metadata().contains("process_rc: unavailable"))
    }

}

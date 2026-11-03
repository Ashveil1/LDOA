package dev.phonelinux.proto
import org.junit.Assert.*
import org.junit.Test
class XdiagInventoryEvidenceTest {
    private fun thrown(body: () -> Unit): Throwable? = try { body(); null } catch(t:Throwable) { t }
    @Test fun primaryAndBothWritesFail_originalIdentityAndOrder() {
        val p=AssertionError("primary");val a=Exception("raw");val b=Exception("meta")
        val t=thrown { XdiagInventoryEvidence.finish(p,{throw a},{throw b}) }
        assertSame(p,t);assertArrayEquals(arrayOf(a,b),requireNotNull(t).suppressed)
    }
    @Test fun firstWriteFails_secondStillAttempted_noPrimaryLoud() {
        val a=Exception("raw");var second=false
        val t=thrown {XdiagInventoryEvidence.finish(null,{throw a},{second=true})}
        assertSame(a,t);assertTrue(second)
    }
    @Test fun selfSuppressionGuard() {
        val p=Exception("same");var second=false
        val t=thrown {XdiagInventoryEvidence.finish(p,{throw p},{second=true})}
        assertSame(p,t);assertTrue(second);assertEquals(0,requireNotNull(t).suppressed.size)
    }
    @Test fun successfulWritesPreserveOriginal() {
        val p=Exception("primary");var n=0
        val t=thrown {XdiagInventoryEvidence.finish(p,{n++},{n++})}
        assertSame(p,t);assertEquals(2,n)
    }
}

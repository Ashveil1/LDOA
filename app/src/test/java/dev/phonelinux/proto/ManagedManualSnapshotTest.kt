package dev.phonelinux.proto
import org.junit.Assert.*
import org.junit.Test
class ManagedManualSnapshotTest {
 private fun values()=MutableList(53){1.0}.also {it[39]=0.0;it[42]=0.0}
 @Test fun copiesImmutableValues(){val v=values();val s=ManagedManualSnapshot(1,2,v,true);v[0]=0.0;assertTrue(s.valid());try{(s.numbers as MutableList<Double>)[0]=2.0;fail()}catch(_:UnsupportedOperationException){}}
 @Test fun missingNonfiniteAndTimeRefuse(){assertFalse(ManagedManualSnapshot(1,2,emptyList(),true).valid());assertFalse(ManagedManualSnapshot(1,2,values().also{it[0]=Double.NaN},true).valid());assertFalse(ManagedManualSnapshot(3,2,values(),true).valid());assertFalse(ManagedManualSnapshot(1,2,values(),false).valid())}
}

package dev.phonelinux.proto
import org.junit.Assert.*
import org.junit.Test
class ManagedComparisonGeometryTest {
 private fun observe(w:Int,h:Int,x:Int,y:Int,dw:Int,dh:Int)=ManagedComparisonGeometry.observe(w,h,ManagedComparisonGeometry.Rect(x,y,dw,dh))
 @Test fun actualT3Partial(){val o=observe(1800,414,0,0,1280,1382);assertEquals(listOf(360,144,1080,124),o.roi.numbers());assertEquals(listOf(360,144,920,124),o.roiIntersection!!.numbers());assertEquals(listOf(0,0,1280,414),o.rootIntersection!!.numbers());assertEquals("PARTIAL",o.coverage);assertFalse(o.fit);assertFalse(o.contained)}
 @Test fun shiftedFullNeverFit(){val o=observe(896,1570,0,48,896,1570);assertEquals("FULL",o.coverage);assertTrue(o.contained);assertFalse(o.fit)}
 @Test fun oddFloorOperands(){val o=observe(803,1387,0,0,803,1387);assertEquals(listOf(160,485,481,416),o.roi.numbers());assertTrue(o.fit)}
 @Test fun disjointAndEdgeTouchNull(){for(x in listOf(800,1000)){val o=observe(800,1800,x,0,800,1800);assertNull(o.rootIntersection);assertNull(o.roiIntersection);assertEquals("NONE",o.coverage)}}
 @Test fun allOriginalLimitsAndRotation(){for(g in listOf(listOf(1080,1949,1280,1949),listOf(896,1570,1280,1570),listOf(800,1382,1280,1382),listOf(1800,414,1280,1382),listOf(1080,1949,1280,1949),listOf(800,1800,800,2000),listOf(800,1800,1280,2000))){val o=observe(g[0],g[1],0,0,g[2],g[3]);assertFalse(o.fit);assertNotNull(o.rootIntersection)}}
 @Test fun negativeOriginAndExact(){assertTrue(observe(800,1800,-1,-1,802,1802).contained);assertFalse(observe(800,1800,-1,-1,802,1802).fit);assertTrue(observe(800,1800,0,0,800,1800).fit)}
 @Test fun rootBandOccluderOutsideDesktopAndOverflow(){val o=observe(1800,414,0,0,1280,1382);val w=ManagedComparisonGeometry.Window(2,true,ManagedComparisonGeometry.Rect(1300,150,10,10));assertEquals(listOf("2"),ManagedComparisonGeometry.occluders(o.roi,1,listOf(w)));assertTrue(ManagedComparisonGeometry.occluders(o.roi,2,listOf(w)).isEmpty());assertTrue(ManagedComparisonGeometry.occluders(o.roi,1,listOf(w.copy(viewable=false))).isEmpty());assertTrue(ManagedComparisonGeometry.occluders(o.roi,1,listOf(w.copy(rect=ManagedComparisonGeometry.Rect(1500,150,10,10)))).isEmpty());assertEquals(64,ManagedComparisonGeometry.occluders(o.roi,1,(2L..65L).map{w.copy(id=it)}).size);try{ManagedComparisonGeometry.occluders(o.roi,1,(2L..66L).map{w.copy(id=it)});fail("overflow")}catch(_:IllegalStateException){}}
}

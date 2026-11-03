package dev.phonelinux.proto
import org.junit.Assert.*
import org.junit.Test
class ManagedXtermLifecycleTest {
 private val expected=listOf(1 to "11",2 to "22",3 to "33")
 private fun failRun(f:()->Unit){try{f();fail("must refuse")}catch(_:IllegalStateException){}}
 @Test fun failedMinimizeArmsSingleRestorePreservesPrimary(){val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{ManagedXtermLifecycle.MapState.IsUnMapped},{},{1});failRun{a.minimize{error("partial")}};assertTrue(a.restoreObligation);val primary=IllegalStateException("original");var restores=0;a.finish(primary){restores++;error("restore")};a.finish(primary){restores++};assertEquals(1,restores);assertEquals(1,primary.suppressed.size);assertEquals("original",primary.message)}
 @Test fun failedExplicitRestoreNeverRetried(){val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{ManagedXtermLifecycle.MapState.IsUnMapped},{},{1});a.minimize{};var n=0;failRun{a.restore{n++;error("restore")}};a.finish(IllegalStateException()){n++};assertEquals(1,n)}
 @Test fun zeroMultipleWmRefusal(){for(owners in listOf(emptyList(),listOf(Triple(1,"11","jwm"),Triple(2,"22","openbox")))){val rows=mutableListOf<String>();failRun{ManagedXtermLifecycle.selectWm(owners){rows.add(it)}};assertEquals("LIFE_WM count=${owners.size}",rows.single())}}
 @Test fun transientAndSingleMinimize(){
  val states=ArrayDeque(listOf(ManagedXtermLifecycle.MapState.IsViewable,ManagedXtermLifecycle.MapState.IsUnviewable,ManagedXtermLifecycle.MapState.IsUnMapped,ManagedXtermLifecycle.MapState.IsUnMapped,ManagedXtermLifecycle.MapState.IsUnMapped))
  var commands=0;val rows=mutableListOf<String>();val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{states.removeFirst()},{rows.add(it)},{1})
  a.prepare(1,100,{commands++},{})
  assertEquals(1,commands);assertEquals(5,rows.size)
 }
 @Test fun alreadyUnmappedNoCommand(){var commands=0;val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{ManagedXtermLifecycle.MapState.IsUnMapped},{},{1});a.prepare(0,100,{commands++},{});assertEquals(0,commands)}
 @Test fun identityDoesNotShortCircuitAndNeverQueries(){for(bad in listOf<String?>(null,"999")){var reads=0;var queries=0;val rows=mutableListOf<String>();val a=ManagedXtermLifecycle(expected,{reads++;if(it==1)bad else expected[it-1].second},{queries++;ManagedXtermLifecycle.MapState.IsViewable},{rows.add(it)},{1});failRun{a.observe(1,"PRE_CAPTURE")};assertEquals(6,reads);assertEquals(0,queries);assertTrue(rows.single().contains("map=NOT_ATTEMPTED"))}}
 @Test fun changedAfterQueryFails(){var reads=0;val rows=mutableListOf<String>();val a=ManagedXtermLifecycle(expected,{reads++;if(reads>3&&it==2)"99" else expected[it-1].second},{ManagedXtermLifecycle.MapState.IsUnMapped},{rows.add(it)},{1});failRun{a.observe(0,"PRE_CAPTURE")};assertTrue(rows.single().contains("post1=99"))}
 @Test fun wmChangedFails(){val a=ManagedXtermLifecycle(expected,{if(it==3)"44" else expected[it-1].second},{error("no query")},{},{1});failRun{a.observe(1,"PREPARE")}}
 @Test fun parseUnknownNotNotAttempted(){val rows=mutableListOf<String>();val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{ManagedXtermLifecycle.MapState.UNKNOWN},{rows.add(it)},{1});failRun{a.observe(1,"PRE_CAPTURE")};assertTrue(rows.single().endsWith("map=UNKNOWN"))}
 @Test fun capNeverRepeatedMinimize(){var n=0;var command=0;val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{n++;ManagedXtermLifecycle.MapState.IsViewable},{},{1});failRun{a.prepare(1,100,{command++},{})};assertEquals(24,n);assertEquals(1,command)}
 @Test fun deadlineRejectsWithoutQuery(){var queries=0;val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{queries++;ManagedXtermLifecycle.MapState.IsUnMapped},{},{100});failRun{a.prepare(0,100,{},{})};assertEquals(0,queries)}
 @Test fun postCaptureDoesNotRepair(){val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{ManagedXtermLifecycle.MapState.IsViewable},{},{1});failRun{a.requireUnmapped(1,"POST_CAPTURE")}}
 @Test fun actualWrapperWritesBeforeFailure(){val rows=mutableListOf<String>();val a=ManagedXtermLifecycle(expected,{expected[it-1].second},{ManagedXtermLifecycle.MapState.IsUnMapped},{rows.add(it)},{1});for(q in listOf(ManagedXtermLifecycle.Query("private text",1,true,false,false,true,true),ManagedXtermLifecycle.Query("",0,false,false,false,false,true),ManagedXtermLifecycle.Query("",0,true,true,false,false,true),ManagedXtermLifecycle.Query("",0,true,false,true,false,true),ManagedXtermLifecycle.Query("",0,true,false,false,true,false))){rows.clear();failRun{a.query(1,"MAP"){q}};assertEquals(1,rows.size);assertFalse(rows.single().contains("private"))}}
 @Test fun throwingWrapperRetainsUnknownAndMapUnknown(){val rows=mutableListOf<String>();lateinit var a:ManagedXtermLifecycle;a=ManagedXtermLifecycle(expected,{expected[it-1].second},{a.query(1,"MAP"){error("raw private")};ManagedXtermLifecycle.MapState.IsUnMapped},{rows.add(it)},{1});failRun{a.observe(1,"POST_CAPTURE")};assertTrue(rows[0].contains("rc=-2 eof=-1"));assertTrue(rows[1].endsWith("map=UNKNOWN"));assertFalse(rows.joinToString().contains("private"))}
}

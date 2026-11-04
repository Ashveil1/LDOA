package dev.phonelinux.handshakefixture;
import android.content.Context;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
/** One finite observation. Not called by failclosed instrumentation until package complete. */
final class CaseRunner {
 static final class Observation {
  String result="UNAVAILABLE",reason="UNAVAILABLE",cleanup="UNKNOWN",failureStage="NONE",capability="UNKNOWN",fileCleanup="NOT_ATTEMPTED";
  Terminal terminal=Terminal.NONE;Integer exitCode=null;
  int seen=0,published=0,accepted=0;boolean uncertain=false;
  long elapsed,guestUid=-1,guestOwner=-1;byte[] snapshot=null;
 }
 static Observation run(Context context,StagingDriver.Staged staged,HandshakeInstrumentation.Session session,String name) throws Exception {
  HandshakeInstrumentation.Deadline deadline=new HandshakeInstrumentation.Deadline();
  long started=android.os.SystemClock.elapsedRealtime();Observation out=new Observation();
  Process process=null;BoundedPipe stdout=null,stderr=null;OwnedTree.Id anchor=null;
  Map<Integer,OwnedTree.Id>tracked=new HashMap<>();Terminal terminal=Terminal.NONE;
  try {
   CasePreparation.Prepared prep=CasePreparation.prepare(session,name);
   if(prep.unsupported){out.result="UNSUPPORTED";out.reason="UNAVAILABLE";out.cleanup="NOT_LAUNCHED";out.failureStage="UNSUPPORTED";return out;}
   Map<Integer,OwnedTree.Id>before=OwnedTree.descendants(android.os.Process.myPid());
   process=ProducerCommand.make(context,staged,session,prep.producerMode).start();deadline.remaining();
   Map<Integer,OwnedTree.Id>after=OwnedTree.descendants(android.os.Process.myPid());
   anchor=OwnedTree.newDirectChild(before,after,android.os.Process.myPid());tracked.putAll(OwnedTree.descendants(anchor.pid));
   stdout=new BoundedPipe(process.getInputStream());stderr=new BoundedPipe(process.getErrorStream());
   String ready=stdout.next(deadline.remaining());
   if(name.equals("monitor_disabled")&&ready.equals("HSREADY1 UNAVAILABLE MONITOR")){
    if(!process.waitFor(deadline.remaining(),java.util.concurrent.TimeUnit.MILLISECONDS))throw new CaseTimeout();
    stdout.requireCleanEnd(deadline.remaining());stderr.requireCleanEnd(deadline.remaining());
    out.result="UNAVAILABLE";out.reason="UNAVAILABLE";out.failureStage="MONITOR";
    out.exitCode=process.exitValue();terminal=Terminal.classify(ready,out.exitCode);
    if(terminal==Terminal.MONITOR_UNAVAILABLE)out.capability="UNAVAILABLE_MONITOR";
    if(terminal==Terminal.NONE)out.failureStage="VALIDATION";
    return out;
   }
   if(!Snapshot.ready(ready))throw new IllegalStateException("READY_INVALID");
   out.capability="AVAILABLE";String[] readyFields=ready.split(" ");out.guestUid=Long.parseLong(readyFields[2].substring(4));out.guestOwner=Long.parseLong(readyFields[4].substring(6));
   CasePreparation.publish(session,prep,name);
   String line=stdout.next(deadline.remaining());
   if(line.matches("HSREADY1 SNAPSHOT 1 uncertain=[01]")){
    out.published=1;out.uncertain=line.endsWith("=1");
    if(name.equals("second_request")){
     byte[] first=session.snapshot();if(Snapshot.accepts(first,session.token,session.pin))out.accepted=1;
     SafeFiles.remove(session.dir,"request",session.uid);
     SafeFiles.createFile(session.dir,"request.tmp",("HSREQ1 "+session.token+" "+session.pin+" 2\n").getBytes(StandardCharsets.US_ASCII),session.uid);
     SafeFiles.publish(session.dir,"request.tmp","request",session.uid);
    }
    line=stdout.next(deadline.remaining());
   }
   if(!line.matches("HSREADY1 END [0-2] [0-1] uncertain=[01]"))throw new IllegalStateException("END_INVALID");
   String[] f=line.split(" ");out.seen=Integer.parseInt(f[2]);out.published=Integer.parseInt(f[3]);out.uncertain|=f[4].endsWith("1");
   if(!process.waitFor(deadline.remaining(),java.util.concurrent.TimeUnit.MILLISECONDS))throw new CaseTimeout();
   stdout.requireCleanEnd(deadline.remaining());stderr.requireCleanEnd(deadline.remaining());
   if(process.exitValue()!=0||stdout.failed||stderr.failed)throw new IllegalStateException("PRODUCER_EXIT");out.exitCode=process.exitValue();terminal=Terminal.classify(line,out.exitCode);
   try {out.snapshot=session.snapshot();out.reason=Snapshot.accepts(out.snapshot,session.token,session.pin)?"COMPLETE":"PARTIAL";if(out.reason.equals("COMPLETE"))out.accepted=1;}
   catch(SnapshotReadException e){out.reason=e.reason();}
   deadline.remaining();
   out.result=CaseVerdict.result(name,out.reason,out.uncertain,out.accepted,out.seen,out.published);
   if(out.uncertain)out.reason="POST_RENAME_FSYNC_FAILED";
   else if(out.reason.equals("UNAVAILABLE"))out.failureStage="VALIDATION";

  }catch(UnsupportedOperationException e){out.result="UNAVAILABLE";out.reason="UNAVAILABLE";out.failureStage="UNSUPPORTED";}
  catch(CaseTimeout e){out.result="UNAVAILABLE";out.reason="UNAVAILABLE";out.failureStage="CASE_TIMEOUT";}
  catch(Exception e){out.result="UNAVAILABLE";out.reason="UNAVAILABLE";out.failureStage="VALIDATION";}
  finally {
   out.terminal=terminal;
   if(process!=null&&!process.isAlive())out.exitCode=process.exitValue();
   out.elapsed=android.os.SystemClock.elapsedRealtime()-started;
   if(process!=null){
    if(anchor!=null){try{out.cleanup=OwnedTree.cleanup(process,anchor,tracked,terminal)?"KNOWN_OWNED_REAPED":"UNKNOWN";}catch(Exception e){out.cleanup="UNKNOWN";}}
    else {HandshakeInstrumentation.stopOwned(process);out.cleanup="UNKNOWN";}
   }
   if(stdout!=null)try{stdout.close();}catch(Exception e){out.cleanup="UNKNOWN";}
   if(stderr!=null)try{stderr.close();}catch(Exception e){out.cleanup="UNKNOWN";}
   // Observation interval excludes protected cleanup. Recorded separately by final serializer.
   if(out.elapsed==0)out.elapsed=Math.min(5001,android.os.SystemClock.elapsedRealtime()-started);
  }
  return out;
 }
}

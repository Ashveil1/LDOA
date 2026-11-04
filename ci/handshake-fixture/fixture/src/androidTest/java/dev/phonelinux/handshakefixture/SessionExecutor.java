package dev.phonelinux.handshakefixture;
import android.content.Context;
import java.io.File;
/** Suite gate. Unstopped worker latches abort and forbids file removal/reuse. Not wired onStart. */
final class SessionExecutor {
 private boolean aborted=false;final SuiteState state=new SuiteState();
 synchronized CaseRunner.Observation execute(Context context,StagingDriver.Staged staged,HandshakeInstrumentation.Session session,String name)throws InterruptedException {
  if(aborted)throw new IllegalStateException("SUITE_ABORTED");
  aborted=true; // conservative retention if supervising thread itself is interrupted
  CaseWatchdog.Result result=CaseWatchdog.execute(()->{
   CaseRunner.Observation o=CaseRunner.run(context,staged,session,name);
   if(o.cleanup.equals("KNOWN_OWNED_REAPED")||o.cleanup.equals("NOT_LAUNCHED"))o.fileCleanup=SessionCleanup.remove(session)?"REMOVED":"FAILED";
   return o;
  });
  state.record(result.observation,result.workerStopped);
  if(!result.workerStopped||!(result.observation.cleanup.equals("KNOWN_OWNED_REAPED")||result.observation.cleanup.equals("NOT_LAUNCHED"))){aborted=true;return result.observation;}
  aborted=false;return result.observation;
 }
 boolean aborted(){return aborted;}
}

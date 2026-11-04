package dev.phonelinux.handshakefixture;
import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import java.io.File;
/** Complete dormant integration source. onStart never calls this pending execution review. */
final class DormantSuite {
 private static final String[]NAMES={"positive","wrong_token","wrong_pin","request_symlink","snapshot_symlink","request_nonregular","snapshot_nonregular","bad_mode","foreign_owner_if_constructible","partial_tmp_only","published_partial","oversized_snapshot","zero_records","second_request","preexisting_final","monitor_disabled","no_request"};
 private static final String[]EXPECTED={"ACCEPTED","REJECTED","REJECTED","REJECTED","REJECTED","REJECTED","REJECTED","REJECTED","UNSUPPORTED","UNAVAILABLE","INCONCLUSIVE","INCONCLUSIVE","INCONCLUSIVE","REJECTED","REJECTED","UNAVAILABLE","UNAVAILABLE"};
 static final class Pins {
  final String source,nativePin,guest,zip;
  Pins(String s,String n,String g,String z){for(String v:new String[]{s,n,g,z})if(v==null||!v.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("PIN");source=s;nativePin=n;guest=g;zip=z;}
 }
 static byte[] prepareAndRun(Instrumentation instrumentation,Bundle arguments,Pins pins)throws Exception {
  Context context=instrumentation.getTargetContext();ApkPins apks=ApkPins.verify(instrumentation,arguments);
  // Android-owned noBackupFilesDir is retained/nofollow opened and0700checked by native staging.
  File parent=context.getNoBackupFilesDir();SessionExecutor executor=new SessionExecutor();StagingDriver.Staged staged=null;boolean retainStaging=false;
  ResultWriter writer=new ResultWriter(pins.source,pins.nativePin,pins.guest,apks.apk,apks.testApk);
  try {
   staged=StagingDriver.prepare(context,parent,pins.guest,pins.zip);
   for(int i=0;i<NAMES.length;i++){
    HandshakeInstrumentation.Session session=new HandshakeInstrumentation.Session(staged.sessions,pins.source);
    CaseRunner.Observation o;
    try{
     o=executor.execute(context,staged,session,NAMES[i]);
     writer.add(NAMES[i],EXPECTED[i],o,session,NAMES[i].equals("monitor_disabled")?"DISABLED":o.capability.equals("AVAILABLE")?"INOTIFY":"UNAVAILABLE");
    }finally{
     // Retain descriptors if worker may be using them. Never close/remove under live worker.
     if(!executor.aborted())session.close();
    }
    if(executor.aborted())throw new IllegalStateException("SUITE_ABORTED_WORKER_OR_PROCESS_UNKNOWN");
   }
   SuiteState.CleanupPermit permit=executor.state.finish();
   StagingCleanupGate.Result cleanup=StagingCleanupGate.remove(staged,permit);retainStaging=!cleanup.stopped;
   if(!cleanup.cleaned)throw new IllegalStateException(cleanup.stopped?"STAGING_FILE_CLEANUP_FAILED":"STAGING_CLEANUP_WORKER_UNKNOWN");
   return writer.finish();
  }finally{
   if(staged!=null&&!executor.aborted()&&!retainStaging)staged.close();
   // UNKNOWN worker/process paths deliberately retain owned files, never fake removal success.
  }
 }
}

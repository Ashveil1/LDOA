package dev.phonelinux.handshakefixture;
import android.app.Instrumentation;
import android.os.Bundle;
/** Generated-pins binding and private output source only, never called by active onStart. */
final class DormantEntry {
 static void execute(Instrumentation instrumentation,Bundle arguments)throws Exception {
  byte[]json=DormantSuite.prepareAndRun(instrumentation,arguments,new DormantSuite.Pins(FixedInputs.SOURCE,FixedInputs.NATIVE,FixedInputs.GUEST,FixedInputs.RUNTIME_ZIP));
  byte[]frame=ResultFrame.encode(json);int uid=android.os.Process.myUid();
  int dir=SafeFiles.openDirectory(instrumentation.getTargetContext().getFilesDir().getAbsolutePath(),uid);
  try{
   // Fixed private result, O_EXCL and no-replace. A stale result blocks rather than overwrites evidence.
   SafeFiles.createFile(dir,"result.tmp",frame,uid);SafeFiles.publish(dir,"result.tmp","result.hsf1",uid);
  }finally{SafeFiles.close(dir);}
 }
}

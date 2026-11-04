package dev.phonelinux.handshakefixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
public final class StagingCleanupTest {
 public static void main(String[]args)throws Exception {
  Path p=Files.createTempDirectory("fixture-clean-unit-");Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));int uid=((Number)Files.getAttribute(p,"unix:uid")).intValue(),parent=SafeFiles.openDirectory(p.toString(),uid);
  Map<String,RuntimeStage.Entry>m=Map.of("producer",new RuntimeStage.Entry(3,"a".repeat(64)),"usr/lib/a.so",new RuntimeStage.Entry(3,"b".repeat(64)));
  SuiteState state=new SuiteState();for(int i=0;i<17;i++){CaseRunner.Observation o=new CaseRunner.Observation();o.cleanup="NOT_LAUNCHED";state.record(o,true);}SuiteState.CleanupPermit permit=state.finish();
  try{
   if(!StagingCleanup.afterSuite(permit,parent,"missing",uid,m))throw new AssertionError();
   int root=SafeFiles.makeDirectory(parent,"root",uid);SafeFiles.createFile(root,"producer",new byte[]{1},uid);int usr=SafeFiles.makeDirectory(root,"usr",uid),lib=SafeFiles.makeDirectory(usr,"lib",uid);SafeFiles.createFile(lib,"a.so",new byte[]{2},uid);SafeFiles.close(lib);SafeFiles.close(usr);SafeFiles.close(root);
   if(!StagingCleanup.afterSuite(permit,parent,"root",uid,m)||Files.exists(p.resolve("root")))throw new AssertionError();
   root=SafeFiles.makeDirectory(parent,"root",uid);SafeFiles.createFile(root,"unknown",new byte[]{1},uid);SafeFiles.close(root);
   if(StagingCleanup.afterSuite(permit,parent,"root",uid,m)||!Files.exists(p.resolve("root/unknown")))throw new AssertionError();
   root=SafeFiles.openChildDirectory(parent,"root",uid);SafeFiles.removeFixed(root,"unknown",uid);SafeFiles.close(root);SafeFiles.removeDirectory(parent,"root",uid);
   System.out.println("ACTUAL_FIXED_STAGING_CLEANUP_3_CHECKS_PASS_UNKNOWN_PRESERVED");
  }finally{SafeFiles.close(parent);Files.delete(p);}
 }
}

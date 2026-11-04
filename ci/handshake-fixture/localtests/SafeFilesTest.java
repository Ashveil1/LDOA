import dev.phonelinux.handshakefixture.SafeFiles;
import dev.phonelinux.handshakefixture.SnapshotReadException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
public final class SafeFilesTest {
 static void reason(int fd,int uid,String wanted){try{SafeFiles.readFile(fd,"snapshot",uid,524288);throw new AssertionError("accepted");}catch(SnapshotReadException e){if(!e.reason().equals(wanted))throw new AssertionError(e.reason());}}
 public static void main(String[]args)throws Exception {
  Path p=Files.createTempDirectory("fixture-jni-unit-");Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));
  int uid=((Number)Files.getAttribute(p,"unix:uid")).intValue(),fd=SafeFiles.openDirectory(p.toString(),uid);
  try{
   reason(fd,uid,"NOT_PUBLISHED");
   if(SafeFiles.removeFixed(fd,"snapshot",uid)!=1)throw new AssertionError();
   SafeFiles.createFile(fd,"snapshot",new byte[]{1,2,3},uid);if(SafeFiles.readFile(fd,"snapshot",uid,524288).length!=3)throw new AssertionError();
   if(SafeFiles.removeFixed(fd,"snapshot",uid)!=0)throw new AssertionError();
   SafeFiles.createAsset(fd,"snapshot",new byte[524289],uid);reason(fd,uid,"CAP_EXCEEDED");SafeFiles.removeFixed(fd,"snapshot",uid);
   SafeFiles.negative(fd,2,uid);reason(fd,uid,"REJECTED");SafeFiles.removeFixed(fd,"snapshot",uid);
   SafeFiles.negative(fd,4,uid);reason(fd,uid,"REJECTED");SafeFiles.removeFixed(fd,"snapshot",uid);
   SafeFiles.createFile(fd,"request.tmp",new byte[]{1},uid);SafeFiles.publish(fd,"request.tmp","request",uid);
   SafeFiles.createFile(fd,"request.tmp",new byte[]{2},uid);try{SafeFiles.publish(fd,"request.tmp","request",uid);throw new AssertionError();}catch(Exception e){if(!e.getClass().getName().equals("java.io.IOException"))throw e;}
   if(SafeFiles.readFile(fd,"request",uid,10)[0]!=1)throw new AssertionError();SafeFiles.removeFixed(fd,"request",uid);SafeFiles.removeFixed(fd,"request.tmp",uid);
   System.out.println("HOST_JNI_FILE_REASON_NOREPLACE_CLEANUP_11_CHECKS_PASS");
  }finally{SafeFiles.close(fd);Files.delete(p);}
 }
}

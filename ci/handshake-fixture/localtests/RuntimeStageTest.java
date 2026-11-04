package dev.phonelinux.handshakefixture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Map;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
public final class RuntimeStageTest {
 static byte[]zip(String name,byte[]raw)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){z.putNextEntry(new ZipEntry(name));z.write(raw);z.closeEntry();}return b.toByteArray();}
 static void stage(byte[]zip,Map<String,RuntimeStage.Entry>pins,boolean pass)throws Exception {
  Path p=Files.createTempDirectory("fixture-stage-unit-");Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));int uid=((Number)Files.getAttribute(p,"unix:uid")).intValue(),fd=SafeFiles.openDirectory(p.toString(),uid);
  try{try{RuntimeStage.stage(new ByteArrayInputStream(zip),fd,uid,pins);if(!pass)throw new AssertionError("accepted");if(!Files.isDirectory(p.resolve("run/ldoa-handshake"))||!Files.isExecutable(p.resolve("producer")))throw new AssertionError();}catch(IllegalStateException e){if(pass)throw e;}}
  finally{SafeFiles.close(fd);try(java.util.stream.Stream<Path>paths=Files.walk(p)){for(Path q:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(q);}}
 }
 public static void main(String[]args)throws Exception {
  byte[]raw={1,2,3};String hash=RuntimeStage.hex(MessageDigest.getInstance("SHA-256").digest(raw));Map<String,RuntimeStage.Entry>pins=Map.of("producer",new RuntimeStage.Entry(3,hash));
  stage(zip("producer",raw),pins,true);stage(zip("unknown",raw),pins,false);stage(zip("../producer",raw),pins,false);stage(zip("producer",new byte[]{1,2,3,4}),pins,false);stage(zip("producer",new byte[]{1,2}),pins,false);stage(zip("producer",new byte[]{3,2,1}),pins,false);
  System.out.println("HOST_JNI_ACTUAL_RUNTIME_STAGE_6_CHECKS_PASS_NO_TARGET_EXECUTION");
 }
}

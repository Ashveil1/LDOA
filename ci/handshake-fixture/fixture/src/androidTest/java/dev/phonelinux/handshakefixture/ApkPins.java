package dev.phonelinux.handshakefixture;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
/** Installed bytes must match externally supplied build artifacts, never guessed/self-embedded APK hashes. */
final class ApkPins {
 final String apk,testApk;
 private ApkPins(String a,String t){apk=a;testApk=t;}
 static String hash(String path)throws Exception {
  MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;byte[]buffer=new byte[8192];int n;
  try(InputStream in=Files.newInputStream(new File(path).toPath())){while((n=in.read(buffer))!=-1){count+=n;if(count>64*1024*1024)throw new IllegalStateException("APK_CAP");digest.update(buffer,0,n);}}
  return RuntimeStage.hex(digest.digest());
 }
 static ApkPins verify(Instrumentation i,Bundle args)throws Exception {
  String a=args.getString("apk_sha256"),t=args.getString("test_apk_sha256");
  if(a==null||t==null||!a.matches("[0-9a-f]{64}")||!t.matches("[0-9a-f]{64}"))throw new IllegalStateException("APK_PIN_REQUIRED");
  if(!i.getTargetContext().getPackageName().equals("dev.phonelinux.handshakefixture")||!i.getContext().getPackageName().equals("dev.phonelinux.handshakefixture.test"))throw new IllegalStateException("PACKAGE_SCOPE");
  if(!hash(i.getTargetContext().getApplicationInfo().sourceDir).equals(a)||!hash(i.getContext().getApplicationInfo().sourceDir).equals(t))throw new IllegalStateException("INSTALLED_APK_PIN");
  return new ApkPins(a,t);
 }
}

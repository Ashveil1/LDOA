package dev.phonelinux.handshakefixture;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.json.JSONObject;
public final class RuntimeManifestTest {
 static void reject(byte[]raw,String hash)throws Exception{try{RuntimeManifest.read(new ByteArrayInputStream(raw),hash);throw new AssertionError("accepted");}catch(IllegalStateException e){/* expected */}}
 public static void main(String[]args)throws Exception{
  byte[]raw=Files.readAllBytes(Path.of(args[0]));String hash=RuntimeStage.hex(MessageDigest.getInstance("SHA-256").digest(raw));if(RuntimeManifest.read(new ByteArrayInputStream(raw),hash).size()!=15)throw new AssertionError();
  reject(raw,"0".repeat(64));
  for(String field:new String[]{"name","sha256","bytes","mode"}){
   JSONObject d=new JSONObject(new String(raw,java.nio.charset.StandardCharsets.UTF_8));JSONObject row=d.getJSONArray("entries").getJSONObject(0);row.put(field,field.equals("name")?"../escape":field.equals("sha256")?"x":0);byte[]changed=d.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);reject(changed,RuntimeStage.hex(MessageDigest.getInstance("SHA-256").digest(changed)));
  }
  System.out.println("ACTUAL_RUNTIME_MANIFEST_6_CHECKS_PASS");
 }
}

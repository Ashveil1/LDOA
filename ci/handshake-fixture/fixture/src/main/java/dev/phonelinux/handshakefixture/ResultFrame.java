package dev.phonelinux.handshakefixture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
/** Fixed single ASCII line transport, no logs or raw protocol strings. */
public final class ResultFrame {
 private ResultFrame(){}
 public static byte[] encode(byte[]json)throws Exception {
  if(json.length<1||json.length>65536)throw new IllegalArgumentException("RESULT_CAP");
  StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(json))digest.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
  byte[]frame=("HSF1 "+json.length+" "+digest+" "+Base64.getEncoder().encodeToString(json)+"\n").getBytes(StandardCharsets.US_ASCII);
  if(frame.length>87500)throw new IllegalStateException("FRAME_CAP");return frame;
 }
}

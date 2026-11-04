package dev.phonelinux.handshakefixture;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
/** Strict synthetic snapshot parser. Malformed evidence is INCONCLUSIVE, not valid success. */
public final class Snapshot {
 private Snapshot(){}
 public static boolean accepts(byte[] bytes,String token,String pin){
  if(bytes.length==0||bytes.length>524288)return false;
  for(byte b:bytes)if((b<32&&b!='\n')||b>126)return false;
  String expected="HSSNAP1 "+token+" "+pin+" 1 1\n1 0 1\n";
  return Arrays.equals(bytes,expected.getBytes(StandardCharsets.US_ASCII));
 }
 public static boolean ready(String line){return line.matches("HSREADY1 READY uid=[0-9]{1,10} euid=[0-9]{1,10} owner=[0-9]{1,10} mode=448 backend=INOTIFY");}
}

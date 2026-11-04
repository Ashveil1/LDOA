import dev.phonelinux.handshakefixture.Snapshot;
import java.nio.charset.StandardCharsets;
public final class SnapshotTest {
 public static void main(String[] args){String token="a".repeat(32),pin="b".repeat(64);byte[]good=("HSSNAP1 "+token+" "+pin+" 1 1\n1 0 1\n").getBytes(StandardCharsets.US_ASCII);
  if(!Snapshot.accepts(good,token,pin))throw new AssertionError("positive");
  for(byte[] bad:new byte[][]{new byte[0],new byte[524289],"x".getBytes(StandardCharsets.US_ASCII),(new String(good,StandardCharsets.US_ASCII)+"x").getBytes(StandardCharsets.US_ASCII),new String(good,StandardCharsets.US_ASCII).replace(" 1 1\n"," 1 0\n").getBytes(StandardCharsets.US_ASCII)})if(Snapshot.accepts(bad,token,pin))throw new AssertionError("negative");
  if(Snapshot.accepts(good,"c".repeat(32),pin))throw new AssertionError("token");
  if(Snapshot.accepts(good,token,"c".repeat(64)))throw new AssertionError("pin");
  if(!Snapshot.ready("HSREADY1 READY uid=0 euid=0 owner=0 mode=448 backend=INOTIFY"))throw new AssertionError("ready");
  if(Snapshot.ready("HSREADY1 READY uid=0 euid=0 owner=0 mode=448 backend=POLL"))throw new AssertionError("poll");
  System.out.println("SNAPSHOT_PARSER_9_CHECKS_PASS");
 }
}

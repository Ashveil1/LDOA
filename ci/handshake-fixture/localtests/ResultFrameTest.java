import dev.phonelinux.handshakefixture.ResultFrame;
import java.nio.file.Files;
import java.nio.file.Path;
public final class ResultFrameTest {
 public static void main(String[]args)throws Exception {
  byte[]raw=Files.readAllBytes(Path.of(args[0]));byte[]frame=ResultFrame.encode(raw);Files.write(Path.of(args[1]),frame);
  for(byte[]bad:new byte[][]{new byte[0],new byte[65537]}){try{ResultFrame.encode(bad);throw new AssertionError();}catch(IllegalArgumentException e){}}
  if(ResultFrame.encode(new byte[65536]).length>87500)throw new AssertionError();System.out.println("ACTUAL_JAVA_FRAME_4_CHECKS_PASS");
 }
}

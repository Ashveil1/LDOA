import dev.phonelinux.handshakefixture.Terminal;
public final class TerminalTest {
 public static void main(String[]args){
  if(Terminal.classify("HSREADY1 UNAVAILABLE MONITOR",73)!=Terminal.MONITOR_UNAVAILABLE)throw new AssertionError();
  if(Terminal.classify("HSREADY1 UNAVAILABLE MONITOR",0)!=Terminal.NONE)throw new AssertionError();
  if(Terminal.classify("HSREADY1 END 1 1 uncertain=0",0)!=Terminal.NORMAL_END)throw new AssertionError();
  for(String line:new String[]{"","HSREADY1 UNAVAILABLE OTHER","HSREADY1 UNAVAILABLE MONITOR ","HSREADY1 END 9 9 uncertain=0"})if(Terminal.classify(line,73)!=Terminal.NONE)throw new AssertionError();
  if(Terminal.classify("HSREADY1 END 1 1 uncertain=0",137)!=Terminal.NONE)throw new AssertionError();
  System.out.println("TERMINAL_CLASSIFICATION_8_CHECKS_PASS");
 }
}

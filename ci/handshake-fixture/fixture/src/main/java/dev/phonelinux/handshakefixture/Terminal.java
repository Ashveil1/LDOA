package dev.phonelinux.handshakefixture;
/** Classified guest terminal receipt, not causal-completion or universal absence. */
public enum Terminal {
 NONE, NORMAL_END, MONITOR_UNAVAILABLE;
 public static Terminal classify(String receipt,int exit){
  if(exit==0&&receipt.matches("HSREADY1 END [0-2] [0-1] uncertain=[01]"))return NORMAL_END;
  if(exit==73&&receipt.equals("HSREADY1 UNAVAILABLE MONITOR"))return MONITOR_UNAVAILABLE;
  return NONE;
 }
}

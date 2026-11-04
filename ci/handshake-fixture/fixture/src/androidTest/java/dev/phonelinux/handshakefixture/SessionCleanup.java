package dev.phonelinux.handshakefixture;
/** Delete only fixed fixture names relative to held owned dirfds. Never recurse/list paths. */
final class SessionCleanup {
 static boolean remove(HandshakeInstrumentation.Session s){
  boolean ok=true;
  for(String name:new String[]{"request","request.tmp","snapshot","snapshot.tmp","session"}){
   // Missing is explicit return1, removed0; refusal throws.
   try{SafeFiles.removeFixed(s.dir,name,s.uid);}catch(Exception e){ok=false;}
  }
  try{SafeFiles.removeDirectory(s.parent,s.child,s.uid);}catch(Exception e){ok=false;}
  return ok;
 }
}

package dev.phonelinux.handshakefixture;
import java.nio.charset.StandardCharsets;
/** Fixed case setup, no arbitrary path content. Not yet driven by instrumentation. */
final class CasePreparation {
 static final class Prepared {
  final boolean publishRequest;final String producerMode;final boolean unsupported;
  Prepared(boolean request,String mode,boolean u){publishRequest=request;producerMode=mode;unsupported=u;}
 }
 static Prepared prepare(HandshakeInstrumentation.Session s,String name) {
  String valid="HSREQ1 "+s.token+" "+s.pin+" 1\n";
  String request=valid;
  if(name.equals("wrong_token"))request="HSREQ1 "+(s.token.charAt(0)=='0'?"1":"0")+s.token.substring(1)+" "+s.pin+" 1\n";
  if(name.equals("wrong_pin"))request="HSREQ1 "+s.token+" "+(s.pin.charAt(0)=='0'?"1":"0")+s.pin.substring(1)+" 1\n";
  if(name.equals("request_symlink")){SafeFiles.negative(s.dir,1,s.uid);return new Prepared(false,"normal",false);}
  if(name.equals("request_nonregular")){SafeFiles.negative(s.dir,3,s.uid);return new Prepared(false,"normal",false);}
  if(name.equals("snapshot_symlink"))SafeFiles.negative(s.dir,2,s.uid);
  if(name.equals("snapshot_nonregular"))SafeFiles.negative(s.dir,4,s.uid);
  if(name.equals("preexisting_final"))SafeFiles.createFile(s.dir,"snapshot","PREEXISTING\n".getBytes(StandardCharsets.US_ASCII),s.uid);
  if(name.equals("published_partial"))SafeFiles.createFile(s.dir,"snapshot","HSSNAP1 partial\n".getBytes(StandardCharsets.US_ASCII),s.uid);
  if(name.equals("zero_records"))SafeFiles.createFile(s.dir,"snapshot",("HSSNAP1 "+s.token+" "+s.pin+" 1 0\n").getBytes(StandardCharsets.US_ASCII),s.uid);
  if(name.equals("oversized_snapshot"))SafeFiles.createAsset(s.dir,"snapshot",new byte[524289],s.uid);
  if(name.equals("monitor_disabled"))return new Prepared(false,"monitor_disabled",false);
  if(name.equals("no_request"))return new Prepared(false,"normal",false);
  SafeFiles.createFile(s.dir,"request.tmp",request.getBytes(StandardCharsets.US_ASCII),s.uid);
  if(name.equals("partial_tmp_only"))return new Prepared(false,"normal",false);
  if(name.equals("bad_mode"))SafeFiles.negative(s.dir,5,s.uid);
  if(name.equals("foreign_owner_if_constructible")){
   try{SafeFiles.negative(s.dir,6,s.uid);
   }catch(UnsupportedOperationException e){return new Prepared(false,"normal",true);}
  }
  return new Prepared(true,"normal",false);
 }
 static void publish(HandshakeInstrumentation.Session s,Prepared p,String name){if(name.equals("bad_mode")||name.equals("foreign_owner_if_constructible")){SafeFiles.negative(s.dir,7,s.uid);return;}if(p.publishRequest)SafeFiles.publish(s.dir,"request.tmp","request",s.uid);}
}

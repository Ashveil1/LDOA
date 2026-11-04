package dev.phonelinux.handshakefixture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;
/** Numeric/enumerated JSON only. Caller cannot add raw errors/paths/output. */
final class ResultWriter {
 private final JSONObject root=new JSONObject();private final JSONArray cases=new JSONArray();
 private boolean cleanupGood=true;
 ResultWriter(String source,String nativePin,String guest,String apk,String testApk)throws Exception {
  JSONObject pins=new JSONObject();String[]names={"source","native","guest","apk","test_apk"},values={source,nativePin,guest,apk,testApk};
  for(int i=0;i<names.length;i++){if(!values[i].matches("[0-9a-f]{64}"))throw new IllegalArgumentException("PIN");pins.put(names[i],values[i]);}
  root.put("schema",1).put("fixture_only",true).put("scope","HANDSHAKE_FIXTURE_ONLY").put("pins",pins).put("cases",cases);
 }
 void add(String name,String expected,CaseRunner.Observation o,HandshakeInstrumentation.Session session,String monitor)throws Exception {
  long[]id=SafeFiles.identity(session.dir,session.uid,true);addObserved(name,expected,o,session.uid,id[2],id[4],session.token,monitor);
 }
 void addObserved(String name,String expected,CaseRunner.Observation o,int uid,long owner,long mode,String token,String monitor)throws Exception {
  if(cases.length()>=17)throw new IllegalStateException("CASE_CAP");
  JSONObject row=new JSONObject();
  row.put("case",name).put("expected",expected).put("result",o.result).put("elapsed_ms",o.elapsed);
  row.put("android_uid",uid).put("android_owner",owner).put("guest_uid",o.guestUid<0?JSONObject.NULL:o.guestUid).put("guest_owner",o.guestOwner<0?JSONObject.NULL:o.guestOwner).put("guest_identity_reason",o.guestUid>=0?"READY_OBSERVED":o.cleanup.equals("NOT_LAUNCHED")?"NOT_LAUNCHED":o.failureStage.equals("MONITOR")?"MONITOR_UNAVAILABLE":"READY_UNAVAILABLE").put("mode",mode);
  row.put("request_seen",o.seen).put("published",o.published).put("accepted",o.accepted);
  row.put("snapshot_bytes",o.snapshot==null?0:o.snapshot.length).put("snapshot_sha256",o.snapshot==null?JSONObject.NULL:RuntimeStage.hex(MessageDigest.getInstance("SHA-256").digest(o.snapshot)));
  row.put("snapshot_reason",o.reason).put("failure_stage",o.failureStage).put("token_sha256",RuntimeStage.hex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))));
  row.put("terminal",o.terminal.name()).put("exit_code",o.exitCode==null?JSONObject.NULL:o.exitCode).put("capability",o.capability).put("file_cleanup",o.fileCleanup);
  row.put("monitor",monitor).put("cleanup",o.cleanup);cleanupGood&=o.cleanup.equals("KNOWN_OWNED_REAPED")||o.cleanup.equals("NOT_LAUNCHED");cases.put(row);
 }
 byte[] finish()throws Exception {
  if(cases.length()!=17)throw new IllegalStateException("CASE_INVENTORY");
  root.put("cleanup",cleanupGood?"KNOWN_OWNED_REAPED":"UNKNOWN");byte[]bytes=root.toString().getBytes(StandardCharsets.US_ASCII);
  if(bytes.length>65536)throw new IllegalStateException("RESULT_CAP");return bytes;
 }
}

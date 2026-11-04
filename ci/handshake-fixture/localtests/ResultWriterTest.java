package dev.phonelinux.handshakefixture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
/** Executes actual serializer, synthetic Observation inputs, not Android runner. */
public final class ResultWriterTest {
 public static void main(String[]args)throws Exception {
  JSONArray inventory=new JSONArray(Files.readString(Path.of(args[0])));String pin="f".repeat(64);
  ResultWriter writer=new ResultWriter(pin,pin,pin,pin,pin);
  for(int i=0;i<inventory.length();i++){
   JSONObject spec=inventory.getJSONObject(i);String name=spec.getString("case");CaseRunner.Observation o=new CaseRunner.Observation();o.result=spec.getString("expected");o.elapsed=100;o.fileCleanup="REMOVED";
   String monitor="INOTIFY";
   if(name.equals("monitor_disabled")){o.reason="UNAVAILABLE";o.failureStage="MONITOR";o.capability="UNAVAILABLE_MONITOR";o.exitCode=73;o.terminal=Terminal.MONITOR_UNAVAILABLE;o.cleanup="KNOWN_OWNED_REAPED";monitor="DISABLED";}
   else if(name.equals("foreign_owner_if_constructible")){o.failureStage="UNSUPPORTED";o.cleanup="NOT_LAUNCHED";}
   else {
    o.cleanup="KNOWN_OWNED_REAPED";o.capability="AVAILABLE";o.exitCode=0;o.terminal=Terminal.NORMAL_END;o.guestUid=0;o.guestOwner=0;o.seen=1;
    if(name.equals("positive")||name.equals("second_request")){o.seen=name.equals("second_request")?2:1;o.published=1;o.accepted=1;o.snapshot=("HSSNAP1 "+"a".repeat(32)+" "+pin+" 1 1\n1 0 1\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);o.reason="COMPLETE";}
    else if(name.equals("published_partial")||name.equals("zero_records")){o.reason="PARTIAL";o.snapshot=new byte[]{1};}
    else if(name.equals("oversized_snapshot")){o.reason="CAP_EXCEEDED";}
    else{o.reason="NOT_PUBLISHED";}
   }
   writer.addObserved(name,spec.getString("expected"),o,10000,10000,448,"a".repeat(32),monitor);
  }
  byte[]result=writer.finish();Files.write(Path.of(args[1]),result);
  JSONObject parsed=new JSONObject(new String(result,java.nio.charset.StandardCharsets.US_ASCII));
  if(parsed.getJSONArray("cases").getJSONObject(15).get("guest_uid")!=JSONObject.NULL)throw new AssertionError();
  System.out.println("ACTUAL_SERIALIZER_SYNTHETIC_17_OBSERVATIONS_WRITTEN");
 }
}

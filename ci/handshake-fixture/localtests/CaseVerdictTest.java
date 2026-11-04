package dev.phonelinux.handshakefixture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
public final class CaseVerdictTest {
 static void check(String n,String reason,boolean uncertain,int accepted,int seen,int published,String expected){String actual=CaseVerdict.result(n,reason,uncertain,accepted,seen,published);if(!actual.equals(expected))throw new AssertionError(n+":"+actual);}
 public static void main(String[]args)throws Exception{
  JSONArray cases=new JSONArray(Files.readString(Path.of(args[0])));int count=0;
  for(int i=0;i<cases.length();i++){
   JSONObject row=cases.getJSONObject(i);String n=row.getString("case");
   if(n.equals("monitor_disabled")||n.equals("foreign_owner_if_constructible"))continue;
   boolean positive=n.equals("positive"),second=n.equals("second_request");String reason=positive||second?"COMPLETE":n.equals("published_partial")||n.equals("zero_records")||n.equals("preexisting_final")?"PARTIAL":n.equals("oversized_snapshot")?"CAP_EXCEEDED":n.equals("snapshot_symlink")||n.equals("snapshot_nonregular")?"REJECTED":"NOT_PUBLISHED";
   check(n,reason,false,positive||second?1:0,second?2:1,positive||second?1:0,row.getString("expected"));count++;
  }
  check("second_request","COMPLETE",false,1,1,1,"ACCEPTED"); // incomplete second request must not claim one-shot rejection
  check("foreign_owner_if_constructible","COMPLETE",false,1,1,1,"ACCEPTED"); // genuine negative acceptance preserved
  check("positive","COMPLETE",true,1,1,1,"INCONCLUSIVE");
  check("wrong_token","UNAVAILABLE",false,0,0,0,"UNAVAILABLE");
  System.out.println("ACTUAL_CASE_VERDICT_"+(count+4)+"_CHECKS_PASS_SYNTHETIC_INPUTS_NOT_RUNNER");
 }
}

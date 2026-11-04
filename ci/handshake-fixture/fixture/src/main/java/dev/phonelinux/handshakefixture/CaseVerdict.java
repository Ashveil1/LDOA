package dev.phonelinux.handshakefixture;
/** Pure terminal/snapshot classification used by runner. No process/capability/cleanup inference. */
public final class CaseVerdict {
 private CaseVerdict(){}
 public static String result(String name,String reason,boolean uncertain,int accepted,int seen,int published){
  if(uncertain)return "INCONCLUSIVE";
  if(accepted==1)return name.equals("second_request")&&seen==2&&published==1?"REJECTED":"ACCEPTED";
  if(name.equals("preexisting_final")&&reason.equals("PARTIAL"))return "REJECTED";
  if(reason.equals("PARTIAL")||reason.equals("CAP_EXCEEDED"))return "INCONCLUSIVE";
  if(reason.equals("UNAVAILABLE"))return "UNAVAILABLE";
  if(name.equals("no_request")||name.equals("partial_tmp_only"))return "UNAVAILABLE";
  return "REJECTED";
 }
}

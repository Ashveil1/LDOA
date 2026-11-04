package dev.phonelinux.handshakefixture;
public final class SuiteStateTest {
 static void bad(SuiteState s){try{s.finish();throw new AssertionError("permit");}catch(IllegalStateException e){}}
 public static void main(String[]args){
  SuiteState s=new SuiteState();bad(s);
  for(int i=0;i<17;i++){CaseRunner.Observation o=new CaseRunner.Observation();o.cleanup="NOT_LAUNCHED";s.record(o,true);}if(s.finish()==null)throw new AssertionError();
  s=new SuiteState();for(int i=0;i<17;i++){CaseRunner.Observation o=new CaseRunner.Observation();o.cleanup=i==3?"UNKNOWN":"KNOWN_OWNED_REAPED";s.record(o,true);}bad(s);
  s=new SuiteState();for(int i=0;i<17;i++){CaseRunner.Observation o=new CaseRunner.Observation();o.cleanup="KNOWN_OWNED_REAPED";s.record(o,i!=5);}bad(s);
  System.out.println("SUITE_CLEANUP_PERMIT_4_CHECKS_PASS");
 }
}

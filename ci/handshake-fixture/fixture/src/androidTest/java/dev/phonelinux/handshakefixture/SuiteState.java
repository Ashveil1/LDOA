package dev.phonelinux.handshakefixture;
/** A cleanup permit can only be minted by complete, stopped, known-owned suite state. */
final class SuiteState {
 static final class CleanupPermit {private CleanupPermit(){}}
 private int completed;private boolean unsafe=false;
 void record(CaseRunner.Observation o,boolean stopped){
  if(!stopped||!(o.cleanup.equals("KNOWN_OWNED_REAPED")||o.cleanup.equals("NOT_LAUNCHED")))unsafe=true;
  completed++;
 }
 CleanupPermit finish(){if(unsafe||completed!=17)throw new IllegalStateException("SUITE_CLEANUP_NOT_PROVEN");return new CleanupPermit();}
}

package dev.phonelinux.handshakefixture;
import java.util.concurrent.atomic.AtomicBoolean;
/** Finite observer, not a kernel-call cancellation claim. A live worker owns retained descriptors. */
final class StagingCleanupGate {
 static final class Result {final boolean stopped,cleaned;Result(boolean s,boolean c){stopped=s;cleaned=c;}}
 static Result remove(StagingDriver.Staged staged,SuiteState.CleanupPermit permit) {
  AtomicBoolean ok=new AtomicBoolean(false);
  Thread worker=new Thread(()->{try{ok.set(staged.removeAfterKnownCleanup(permit));}catch(Throwable ignored){}} ,"fixture-staging-cleanup");
  worker.setDaemon(true);worker.start();
  try{worker.join(5000);if(worker.isAlive()){worker.interrupt();worker.join(100);return new Result(!worker.isAlive(),false);}}
  catch(InterruptedException e){worker.interrupt();Thread.currentThread().interrupt();return new Result(false,false);}
  return new Result(true,ok.get());
 }
}

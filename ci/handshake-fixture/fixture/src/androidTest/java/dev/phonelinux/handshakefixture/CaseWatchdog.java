package dev.phonelinux.handshakefixture;
import java.util.concurrent.atomic.AtomicReference;
/** Outer wall-clock gate. A blocked worker never becomes PASS or permits another session. */
final class CaseWatchdog {
 interface Work {CaseRunner.Observation run() throws Exception;}
 static final class Result {
  final CaseRunner.Observation observation;final boolean workerStopped;
  Result(CaseRunner.Observation o,boolean stopped){observation=o;workerStopped=stopped;}
 }
 static Result execute(Work work) throws InterruptedException {
  AtomicReference<CaseRunner.Observation>result=new AtomicReference<>();
  AtomicReference<Throwable>failure=new AtomicReference<>();
  Thread worker=new Thread(()->{try{result.set(work.run());}catch(Throwable t){failure.set(t);}},"fixture-case-worker");
  worker.setDaemon(true);long start=android.os.SystemClock.elapsedRealtime();worker.start();worker.join(5000);
  if(worker.isAlive()){
   worker.interrupt();
   // No Thread.stop, no unanchored process killing. Protected cleanup has a separate4s allowance.
   worker.join(4500);
   CaseRunner.Observation timed=new CaseRunner.Observation();timed.result="UNAVAILABLE";timed.reason="UNAVAILABLE";timed.failureStage="CASE_TIMEOUT";
   timed.elapsed=android.os.SystemClock.elapsedRealtime()-start;timed.cleanup="UNKNOWN";
   // Even if the worker later returns, timeout evidence cannot be replaced with success.
   return new Result(timed,!worker.isAlive());
  }
  if(failure.get()!=null||result.get()==null){CaseRunner.Observation bad=new CaseRunner.Observation();bad.failureStage="VALIDATION";bad.elapsed=android.os.SystemClock.elapsedRealtime()-start;return new Result(bad,true);}
  return new Result(result.get(),true);
 }
}

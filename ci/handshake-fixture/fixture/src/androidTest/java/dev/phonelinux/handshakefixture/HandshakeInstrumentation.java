package dev.phonelinux.handshakefixture;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/** Authoring checkpoint. Intentionally reports failure, never synthetic fixture success. */
public final class HandshakeInstrumentation extends Instrumentation {
    private static final long CASE_MS=5000;
    private static final long CLEANUP_MS=2000;
    private static final String[] CASES={"positive","wrong_token","wrong_pin","request_symlink","snapshot_symlink","request_nonregular","snapshot_nonregular","bad_mode","foreign_owner_if_constructible","partial_tmp_only","published_partial","oversized_snapshot","zero_records","second_request","preexisting_final","monitor_disabled","no_request"};
    private Bundle launchArguments;
    @Override public void onCreate(Bundle arguments) {launchArguments=arguments==null?new Bundle():new Bundle(arguments);super.onCreate(arguments);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        if (FixedScopeGate.accepts(launchArguments)) {
            try {DormantEntry.execute(this,launchArguments);result.putString("stage","RESULT_PRIVATE");finish(0,result);}
            catch(Throwable e){result.putString("stage","FIXTURE_FAILED_OR_UNKNOWN");finish(1,result);}
            return;
        }
        // Build/run stubs remain closed. No guest process starts until complete lock,
        // staging, injection and snapshot parser are reviewed and wired together.
        result.putString("failure_stage","PREIMPLEMENTATION_BLOCKED");
        result.putInt("case_count",CASES.length);
        finish(1,result);
    }
    static final class Deadline {
        final long end=SystemClock.elapsedRealtime()+CASE_MS;
        long remaining(){long n=end-SystemClock.elapsedRealtime();if(n<=0)throw new CaseTimeout();return n;}
    }
    static final class Session implements AutoCloseable {
        final int uid=android.os.Process.myUid();
        final String child,token,pin;
        final File privateParent;final long parentDevice,parentInode;
        final int parent,dir;
        final long device,inode;
        Session(File privateParent,String expectedPin) throws Exception {
            if(!expectedPin.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("PIN");
            pin=expectedPin;child=randomHex();token=randomHex();
            // Caller must create/authenticate privateParent by descriptor. No listing.
            parent=SafeFiles.openDirectory(privateParent.getCanonicalPath(),uid);
            int opened=-1;
            try {opened=SafeFiles.makeDirectory(parent,child,uid);} catch(Throwable t){SafeFiles.close(parent);throw t;}
            dir=opened;
            long[] identity=SafeFiles.identity(dir,uid,true);
            device=identity[0];inode=identity[1];
            SafeFiles.createFile(dir,"session",("HSSESSION1 "+child+" "+token+" "+pin+"\n").getBytes(StandardCharsets.US_ASCII),uid);
        }
        void verifyPaths(){
            int checked=SafeFiles.openDirectory(privateParent.getAbsolutePath(),uid);
            try {long[]id=SafeFiles.identity(checked,uid,true);if(id[0]!=parentDevice||id[1]!=parentInode)throw new IllegalStateException("PARENT_IDENTITY");
                int held=SafeFiles.openChildDirectory(checked,child,uid);try{long[]v=SafeFiles.identity(held,uid,true);if(v[0]!=device||v[1]!=inode)throw new IllegalStateException("SESSION_IDENTITY");}finally{SafeFiles.close(held);}
            }finally{SafeFiles.close(checked);}
        }
        void request() {SafeFiles.createFile(dir,"request.tmp",("HSREQ1 "+token+" "+pin+" 1\n").getBytes(StandardCharsets.US_ASCII),uid);SafeFiles.publish(dir,"request.tmp","request",uid);}
        byte[] snapshot(){return SafeFiles.readFile(dir,"snapshot",uid,524288);}
        @Override public void close(){SafeFiles.close(dir);SafeFiles.close(parent);}
    }
    static String randomHex(){byte[] b=new byte[16];new SecureRandom().nextBytes(b);StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
    static boolean stopOwned(Process p) throws InterruptedException {
        if(p==null)return true;
        p.destroy();if(p.waitFor(CLEANUP_MS,TimeUnit.MILLISECONDS))return true;
        p.destroyForcibly();return p.waitFor(CLEANUP_MS,TimeUnit.MILLISECONDS);
    }
}

package dev.phonelinux.handshakefixture;
import android.content.Context;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
/** Explicit private staging source. Not called by instrumentation. No runtime process launch. */
final class StagingDriver {
 static final class Unlaunched {private Unlaunched(){}}
 static final class Staged implements AutoCloseable {
  final File root,tmp,sessions,privateParent;final int parent,rootFd,tmpFd,sessionsFd;
  final long[][] identities;final Map<String,RuntimeStage.Entry> manifest;
  Staged(File r,File t,File s,int p,int rf,int tf,int sf,Map<String,RuntimeStage.Entry>m){manifest=m;root=r;tmp=t;sessions=s;privateParent=r.getParentFile();parent=p;rootFd=rf;tmpFd=tf;sessionsFd=sf;int uid=android.os.Process.myUid();identities=new long[][]{SafeFiles.identity(p,uid,true),SafeFiles.identity(rf,uid,true),SafeFiles.identity(tf,uid,true),SafeFiles.identity(sf,uid,true)};}
  void verifyPaths(){
   int uid=android.os.Process.myUid();File[]paths={privateParent,root,tmp,sessions};
   for(int i=0;i<paths.length;i++){int fd=SafeFiles.openDirectory(paths[i].getAbsolutePath(),uid);try{long[]id=SafeFiles.identity(fd,uid,true);if(id[0]!=identities[i][0]||id[1]!=identities[i][1])throw new IllegalStateException("STAGING_IDENTITY");}finally{SafeFiles.close(fd);}}
  }
  boolean removeAfterKnownCleanup(SuiteState.CleanupPermit permit){
   if(permit==null)throw new IllegalArgumentException("CLEANUP_PERMIT_REQUIRED");
   try{verifyPaths();boolean ok=StagingCleanup.afterSuite(permit,parent,root.getName(),android.os.Process.myUid(),manifest);
    try{SafeFiles.removeDirectory(parent,tmp.getName(),android.os.Process.myUid());}catch(Exception e){ok=false;}
    try{SafeFiles.removeDirectory(parent,sessions.getName(),android.os.Process.myUid());}catch(Exception e){ok=false;}
    return ok;
   }catch(Exception e){return false;}
  }
  @Override public void close(){SafeFiles.close(sessionsFd);SafeFiles.close(tmpFd);SafeFiles.close(rootFd);SafeFiles.close(parent);}
 }
 static Staged prepare(Context context,File privateParent,String manifestPin,String zipPin)throws Exception {
  if(!zipPin.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("ZIP_PIN");
  Map<String,RuntimeStage.Entry> expected=RuntimeManifest.read(context.getAssets().open("runtime.manifest.json"),manifestPin);
  MessageDigest hash=MessageDigest.getInstance("SHA-256");long count=0;byte[]buf=new byte[8192];int n;
  try(InputStream in=context.getAssets().open("runtime.zip")){while((n=in.read(buf))!=-1){count+=n;if(count>32*1024*1024)throw new IllegalStateException("ZIP_CAP");hash.update(buf,0,n);}}
  if(!RuntimeStage.hex(hash.digest()).equals(zipPin))throw new IllegalStateException("ZIP_HASH");
  int uid=android.os.Process.myUid(),parent=SafeFiles.openDirectory(privateParent.getAbsolutePath(),uid),root=-1,tmp=-1,sessions=-1;
  String rn=HandshakeInstrumentation.randomHex(),tn=HandshakeInstrumentation.randomHex(),sn=HandshakeInstrumentation.randomHex();
  try {
   root=SafeFiles.makeDirectory(parent,rn,uid);tmp=SafeFiles.makeDirectory(parent,tn,uid);sessions=SafeFiles.makeDirectory(parent,sn,uid);
   RuntimeStage.stage(context.getAssets().open("runtime.zip"),root,uid,expected);
   return new Staged(new File(privateParent,rn),new File(privateParent,tn),new File(privateParent,sn),parent,root,tmp,sessions,expected);
  }catch(Exception e){
   boolean cleanup=StagingCleanup.duringUnlaunchedStaging(new Unlaunched(),parent,rn,uid,expected);
   try{SafeFiles.removeFixed(parent,tn,uid);}catch(Exception ignored){cleanup=false;}
   try{SafeFiles.removeFixed(parent,sn,uid);}catch(Exception ignored){cleanup=false;}
   if(!cleanup)e.addSuppressed(new IllegalStateException("STAGING_FILE_CLEANUP_FAILED"));
   if(sessions>=0)SafeFiles.close(sessions);if(tmp>=0)SafeFiles.close(tmp);if(root>=0)SafeFiles.close(root);SafeFiles.close(parent);throw e;}
 }
}

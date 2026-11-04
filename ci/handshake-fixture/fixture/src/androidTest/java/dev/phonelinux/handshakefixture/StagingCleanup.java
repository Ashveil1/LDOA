package dev.phonelinux.handshakefixture;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
/** Manifest-fixed cleanup only. Must be called only after all case workers/processes are classified gone. */
final class StagingCleanup {
 static boolean afterSuite(SuiteState.CleanupPermit permit,int parent,String name,int uid,Map<String,RuntimeStage.Entry>manifest){if(permit==null)throw new IllegalArgumentException("CLEANUP_PERMIT_REQUIRED");return tree(parent,name,uid,manifest);}
 static boolean duringUnlaunchedStaging(StagingDriver.Unlaunched permit,int parent,String name,int uid,Map<String,RuntimeStage.Entry>manifest){if(permit==null)throw new IllegalArgumentException("UNLAUNCHED_REQUIRED");return tree(parent,name,uid,manifest);}
 private static boolean tree(int parent,String rootName,int uid,Map<String,RuntimeStage.Entry>manifest){
  int root=-1;boolean good=true;
  try{
   root=SafeFiles.openChildOrMissing(parent,rootName,uid);if(root==-2)return true;
   Set<String>dirs=new TreeSet<>((a,b)->{int c=Integer.compare(b.length(),a.length());return c==0?a.compareTo(b):c;});
   dirs.addAll(java.util.Arrays.asList("dev","proc","sys","tmp","run","run/ldoa-handshake","usr","usr/lib","usr/lib/x86_64-linux-gnu","lib64","empty-gio-modules"));
   for(String name:manifest.keySet()){
    String[]parts=name.split("/");int fd=root;java.util.ArrayList<Integer>held=new java.util.ArrayList<>();String path="";
    try{
     for(int i=0;i<parts.length-1;i++){path=path.isEmpty()?parts[i]:path+"/"+parts[i];dirs.add(path);int child=SafeFiles.openChildOrMissing(fd,parts[i],uid);if(child==-2){fd=-2;break;}held.add(child);fd=child;}
     if(fd!=-2)SafeFiles.removeFixed(fd,parts[parts.length-1],uid);
    }catch(Exception e){good=false;}finally{for(int h:held)SafeFiles.close(h);}
   }
   for(String path:dirs){
    String[]parts=path.split("/");int fd=root;java.util.ArrayList<Integer>held=new java.util.ArrayList<>();
    try{
     for(int i=0;i<parts.length-1;i++){int child=SafeFiles.openChildOrMissing(fd,parts[i],uid);if(child==-2){fd=-2;break;}held.add(child);fd=child;}
     if(fd!=-2)SafeFiles.removeFixed(fd,parts[parts.length-1],uid);
    }catch(Exception e){good=false;}finally{for(int h:held)SafeFiles.close(h);}
   }
   // Unknown names prevent rmdir. Never enumerate or delete them.
   if(good)SafeFiles.removeDirectory(parent,rootName,uid);
  }catch(Exception e){good=false;}finally{if(root>=0)SafeFiles.close(root);}
  return good;
 }
}

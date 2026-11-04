package dev.phonelinux.handshakefixture;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Host /proc identities only. Never guest PID translation, process-name lookup or broad pkill. */
final class OwnedTree {
 static final class Id {
  final int pid,ppid;final long start;
  Id(int p,int parent,long s){pid=p;ppid=parent;start=s;}
 }
 static Id read(int pid) throws Exception {
  byte[] b=Files.readAllBytes(new File("/proc/"+pid+"/stat").toPath());
  if(b.length>8192)throw new IllegalStateException("STAT_CAP");
  String s=new String(b,StandardCharsets.US_ASCII);int end=s.lastIndexOf(')');
  if(end<0)throw new IllegalStateException("STAT_FORMAT");
  String[] f=s.substring(end+1).trim().split(" +");
  if(f.length<20)throw new IllegalStateException("STAT_FIELDS");
  return new Id(pid,Integer.parseInt(f[1]),Long.parseLong(f[19]));
 }
 static Map<Integer,Id> descendants(int root) throws Exception {
  // Scope is fixture's OWN app uid. A failed listing/read is UNKNOWN, never empty-success.
  String[] names=new File("/proc").list();if(names==null)throw new IllegalStateException("PROC_LIST");
  if(names.length>65536)throw new IllegalStateException("PROC_CAP");
  Map<Integer,Id> all=new HashMap<>();
  for(String n:names) {
   if(!n.matches("[0-9]+"))continue;
   int pid=Integer.parseInt(n);
   // Only app-owned entries. lstat UID does not authenticate guest identities.
   android.system.StructStat st;
   try {st=android.system.Os.stat("/proc/"+pid);}catch(android.system.ErrnoException e){if(e.errno==android.system.OsConstants.ENOENT)continue;throw e;}
   if(st.st_uid!=android.os.Process.myUid())continue;
   try {all.put(pid,read(pid));}catch(java.nio.file.NoSuchFileException e){/* exited between observed stat/read */}
  }
  Set<Integer>owned=new HashSet<>();owned.add(root);boolean changed=true;
  while(changed){changed=false;for(Id i:all.values())if(owned.contains(i.ppid)&&owned.add(i.pid))changed=true;}
  Map<Integer,Id> result=new HashMap<>();for(int pid:owned)if(all.containsKey(pid))result.put(pid,all.get(pid));
  return result;
 }
 static Id newDirectChild(Map<Integer,Id> before,Map<Integer,Id> after,int parent){
  Id found=null;
  for(Id i:after.values())if(i.ppid==parent&&!before.containsKey(i.pid)){
   if(found!=null)throw new IllegalStateException("AMBIGUOUS_OWNED_CHILD");found=i;
  }
  if(found==null)throw new IllegalStateException("NO_OWNED_CHILD");return found;
 }
 static boolean same(Id expected) throws Exception {
  try{return read(expected.pid).start==expected.start;}catch(java.nio.file.NoSuchFileException e){return false;}
 }
 static void signal(Id id,int sig) throws Exception {
  if(!same(id))return;
  // Host pid anchored immediately before signal; race remains a documented platform limit.
  android.system.Os.kill(id.pid,sig);
 }
 static boolean cleanup(Process process,Id root,Map<Integer,Id> tracked,Terminal terminal) throws Exception {
  if(terminal!=Terminal.NONE && !process.isAlive()){
   if(same(root))return false;
   for(Id i:tracked.values())if(same(i))return false;
   return true; // Only captured identities plus anchored proot, never universal absence.
  }
  long deadline=SystemClock.elapsedRealtime()+4000;
  if(same(root))tracked.putAll(descendants(root.pid));
  for(Id i:tracked.values())signal(i,android.system.OsConstants.SIGTERM);
  process.destroy();
  while(SystemClock.elapsedRealtime()<deadline-2000){boolean alive=false;for(Id i:tracked.values())alive|=same(i);if(!alive)break;Thread.sleep(10);}
  for(Id i:tracked.values())signal(i,android.system.OsConstants.SIGKILL);
  process.destroyForcibly();
  if(!process.waitFor(Math.max(1,deadline-SystemClock.elapsedRealtime()),java.util.concurrent.TimeUnit.MILLISECONDS))return false;
  for(Id i:tracked.values())if(same(i))return false;
  // Detached unknown grandchildren cannot be excluded by this snapshot alone.
  return false; // Fail closed until detached-tracee exclusion is established.
 }
}

package dev.phonelinux.handshakefixture;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
/** Fixed protocol line reader; no logging of raw guest stdout. */
final class BoundedPipe implements AutoCloseable {
 private final InputStream input;
 private final ArrayBlockingQueue<String> lines=new ArrayBlockingQueue<>(4);
 private final Thread thread;
 volatile boolean complete=false,failed=false;
 BoundedPipe(InputStream source){input=source;thread=new Thread(()->{
  try{
   byte[]line=new byte[256];int used=0,total=0,ch;
   while((ch=input.read())!=-1){
    if(++total>1024||used==line.length||ch>126||(ch<32&&ch!='\n'))throw new IllegalStateException("PROTOCOL_CAP");
    if(ch=='\n'){if(!lines.offer(new String(line,0,used,StandardCharsets.US_ASCII)))throw new IllegalStateException("PROTOCOL_QUEUE");used=0;}else line[used++]=(byte)ch;
   }
   if(used!=0)throw new IllegalStateException("PARTIAL_LINE");complete=true;
  }catch(Exception e){failed=true;}
 },"fixture-bounded-protocol");thread.setDaemon(true);thread.start();}
 String next(long ms) throws Exception {
  if(ms<=0)throw new CaseTimeout();
  if(failed)throw new IllegalStateException("PROTOCOL_UNAVAILABLE");
  String s=lines.poll(ms,TimeUnit.MILLISECONDS);if(s==null)throw new CaseTimeout();
  if(failed)throw new IllegalStateException("PROTOCOL_UNAVAILABLE");return s;
 }
 void requireCleanEnd(long ms)throws Exception {
  if(ms<=0)throw new CaseTimeout();thread.join(ms);
  if(thread.isAlive())throw new CaseTimeout();
  if(failed||!complete||!lines.isEmpty())throw new IllegalStateException("PROTOCOL_TRAILING_OR_FAILED");
 }
 @Override public void close() {try{input.close();thread.join(500);}catch(Exception e){Thread.currentThread().interrupt();throw new IllegalStateException("PIPE_CLEANUP",e);}if(thread.isAlive())throw new IllegalStateException("PIPE_CLEANUP");}
}

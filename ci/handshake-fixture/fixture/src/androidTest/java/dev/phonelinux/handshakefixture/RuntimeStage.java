package dev.phonelinux.handshakefixture;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
/** Symlink-free immutable runtime ZIP staged only through held relative directory fds. */
final class RuntimeStage {
 static final class Entry {final int bytes;final String sha;Entry(int n,String h){bytes=n;sha=h;}}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
 static void stage(InputStream input,int root,int uid,Map<String,Entry> expected) throws Exception {
  if(expected.size()<1||expected.size()>32)throw new IllegalArgumentException("ASSET_COUNT");
  java.util.Set<String>requiredDirs=new java.util.LinkedHashSet<>(java.util.Arrays.asList("dev","proc","sys","tmp","run","run/ldoa-handshake","usr","usr/lib","usr/lib/x86_64-linux-gnu","lib64","empty-gio-modules"));
  Map<String,Integer>dirs=new HashMap<>();dirs.put("",root);long total=0;int count=0;
  java.util.Set<String>seen=new java.util.HashSet<>();
  try(ZipInputStream zip=new ZipInputStream(input)){
   for(String dir:requiredDirs){String[]parts=dir.split("/");String path="";int fd=root;for(String part:parts){path=path.isEmpty()?part:path+"/"+part;Integer held=dirs.get(path);if(held==null){held=SafeFiles.makeDirectory(fd,part,uid);dirs.put(path,held);}fd=held;}}
   ZipEntry z;while((z=zip.getNextEntry())!=null){
    String name=z.getName();Entry pin=expected.get(name);
    if(z.isDirectory()||pin==null||!seen.add(name)||name.startsWith("/")||name.contains("..")||!name.matches("[a-zA-Z0-9_./+-]{1,128}"))throw new IllegalStateException("ASSET_NAME");
    if(pin.bytes<1||pin.bytes>16*1024*1024)throw new IllegalStateException("ASSET_SIZE");
    String[]parts=name.split("/");String path="";int fd=root;
    for(int i=0;i<parts.length-1;i++){path=path.isEmpty()?parts[i]:path+"/"+parts[i];Integer held=dirs.get(path);if(held==null){held=SafeFiles.makeDirectory(fd,parts[i],uid);dirs.put(path,held);}fd=held;}
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[]buf=new byte[8192];int n;
    while((n=zip.read(buf))!=-1){if(bytes.size()+n>pin.bytes)throw new IllegalStateException("ASSET_CAP");bytes.write(buf,0,n);}
    byte[]raw=bytes.toByteArray();total+=raw.length;if(total>32*1024*1024||raw.length!=pin.bytes||!hex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(pin.sha))throw new IllegalStateException("ASSET_PIN");
    // Separate16MiB asset writer, never used for snapshot acceptance.
    SafeFiles.createAsset(fd,parts[parts.length-1],raw,uid);count++;
   }
   if(count!=expected.size())throw new IllegalStateException("ASSET_MISSING");
   SafeFiles.executableProducer(root,uid);
  }finally{for(Map.Entry<String,Integer>e:dirs.entrySet())if(!e.getKey().isEmpty())SafeFiles.close(e.getValue());}
 }
}

package dev.phonelinux.handshakefixture;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
/** Manifest bytes must match a compile-time injected digest, not a digest from the same asset. */
final class RuntimeManifest {
 static Map<String,RuntimeStage.Entry> read(InputStream input,String expectedHash)throws Exception {
  if(!expectedHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("MANIFEST_PIN");
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[]buf=new byte[4096];int n;
  try(InputStream in=input){while((n=in.read(buf))!=-1){if(out.size()+n>16384)throw new IllegalStateException("MANIFEST_CAP");out.write(buf,0,n);}}
  byte[]raw=out.toByteArray();if(!RuntimeStage.hex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(expectedHash))throw new IllegalStateException("MANIFEST_HASH");
  JSONObject manifest=new JSONObject(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
  if(manifest.getInt("schema")!=1||!manifest.getString("scope").equals("SYMLINK_FREE_HANDSHAKE_RUNTIME_ASSET_COMPILED_NOT_EXECUTED"))throw new IllegalStateException("MANIFEST_SCHEMA");
  JSONArray entries=manifest.getJSONArray("entries");if(entries.length()<1||entries.length()>32)throw new IllegalStateException("MANIFEST_COUNT");
  Map<String,RuntimeStage.Entry> pins=new LinkedHashMap<>();long total=0;boolean producer=false;
  for(int i=0;i<entries.length();i++){
   JSONObject row=entries.getJSONObject(i);String name=row.getString("name"),sha=row.getString("sha256");int bytes=row.getInt("bytes"),mode=row.getInt("mode");
   if(!name.matches("[a-zA-Z0-9_./+-]{1,128}")||name.startsWith("/")||name.contains("..")||name.contains("//")||name.endsWith("/")||!sha.matches("[0-9a-f]{64}")||bytes<1||bytes>16*1024*1024||pins.containsKey(name))throw new IllegalStateException("MANIFEST_ENTRY");
   if(mode!=(name.equals("producer")?448:384))throw new IllegalStateException("MANIFEST_MODE");
   producer|=name.equals("producer");total+=bytes;pins.put(name,new RuntimeStage.Entry(bytes,sha));
  }
  if(!producer||total>32*1024*1024||total!=manifest.getLong("total_bytes"))throw new IllegalStateException("MANIFEST_TOTAL");
  return pins;
 }
}

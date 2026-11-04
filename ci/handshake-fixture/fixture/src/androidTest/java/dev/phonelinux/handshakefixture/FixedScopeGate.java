package dev.phonelinux.handshakefixture;
import android.os.Bundle;
/** Absent/mismatched/extra args are closed. APK hashes independently checked before staging. */
final class FixedScopeGate {
 @SuppressWarnings("deprecation") // BaseBundle.get preserves type check on API26, getString can coerce/warn.
 static boolean accepts(Bundle a){try{return bundleValues(a);}catch(RuntimeException e){return false;}}
 @SuppressWarnings("deprecation")
 private static boolean bundleValues(Bundle a){
  if(a==null)return false;
  java.util.Map<String,String> values=new java.util.HashMap<>();
  for(String key:a.keySet()){Object value=a.get(key);if(!(value instanceof String))return false;values.put(key,(String)value);}
  return acceptsValues(values);
 }
 static boolean acceptsValues(java.util.Map<String,String> a){
  if(a==null)return false;
  java.util.Set<String>keys=new java.util.HashSet<>(java.util.Arrays.asList("fixture_scope","source_sha256","native_sha256","guest_sha256","zip_sha256","apk_sha256","test_apk_sha256"));
  if(!a.keySet().equals(keys))return false;
  return "HSF1_17CASE_V1".equals(a.get("fixture_scope"))&&FixedInputs.SOURCE.equals(a.get("source_sha256"))&&FixedInputs.NATIVE.equals(a.get("native_sha256"))&&FixedInputs.GUEST.equals(a.get("guest_sha256"))&&FixedInputs.RUNTIME_ZIP.equals(a.get("zip_sha256"))&&hex(a.get("apk_sha256"))&&hex(a.get("test_apk_sha256"));
 }
 private static boolean hex(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
}

package dev.phonelinux.handshakefixture;
import java.util.Map;
import java.util.HashMap;
public final class FixedScopeGateTest {
 public static void main(String[]args){
  Map<String,String> good=new HashMap<>();good.put("fixture_scope","HSF1_17CASE_V1");good.put("source_sha256",FixedInputs.SOURCE);good.put("native_sha256",FixedInputs.NATIVE);good.put("guest_sha256",FixedInputs.GUEST);good.put("zip_sha256",FixedInputs.RUNTIME_ZIP);good.put("apk_sha256","a".repeat(64));good.put("test_apk_sha256","b".repeat(64));
  if(!FixedScopeGate.acceptsValues(good)||FixedScopeGate.acceptsValues(null)||FixedScopeGate.acceptsValues(Map.of()))throw new AssertionError();int checks=3;
  for(String k:good.keySet()){
   Map<String,String> m=new HashMap<>(good);m.remove(k);if(FixedScopeGate.acceptsValues(m))throw new AssertionError(k);checks++;
   m=new HashMap<>(good);m.put(k,"wrong");if(FixedScopeGate.acceptsValues(m))throw new AssertionError(k);checks++;
  }
  for(String k:new String[]{"command","path","cases","duration","unknown"}){Map<String,String> m=new HashMap<>(good);m.put(k,"x");if(FixedScopeGate.acceptsValues(m))throw new AssertionError(k);checks++;}
  System.out.println("FIXED_SCOPE_GATE_"+checks+"_CHECKS_PASS_DEFAULT_CLOSED");
 }
}

#!/usr/bin/env python3
"""Proposed fixed fixture target leg. Not invoked; no arbitrary command/path CLI."""
import subprocess,json,pathlib,hashlib,os,signal,sys
BASE=pathlib.Path(__file__).resolve().parents[1]
WORK=BASE/'private-run';ADB=pathlib.Path(os.environ.get('ANDROID_HOME','/nonexistent'))/'platform-tools/adb';APP=WORK/'fixture-debug.apk';TEST=WORK/'fixture-debug-androidTest.apk'
def execute(serial):
 if serial!='emulator-5554':raise ValueError('SERIAL_SCOPE')
 def call(args,seconds):
  return subprocess.run([str(ADB),'-s',serial]+args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=seconds,check=True)
 pins=json.loads((BASE/'generated/payload-binding.json').read_text());args=['shell','am','instrument','-w','-r']
 values={'fixture_scope':'HSF1_17CASE_V1','source_sha256':pins['source_pin'],'native_sha256':pins['native_pin'],'guest_sha256':pins['guest_pin'],'zip_sha256':pins['runtime_zip_sha256'],'apk_sha256':hashlib.sha256(APP.read_bytes()).hexdigest(),'test_apk_sha256':hashlib.sha256(TEST.read_bytes()).hexdigest()}
 for k,v in values.items():args+=['-e',k,v]
 args+=['dev.phonelinux.handshakefixture.test/dev.phonelinux.handshakefixture.HandshakeInstrumentation']
 call(['install','--no-streaming',str(APP)],30);call(['install','--no-streaming',str(TEST)],30)
 # Process timeout alone does not assert guest tracees gone. Outer owned emulator cleanup required.
 call(args,240)
 raw=call(['exec-out','run-as','dev.phonelinux.handshakefixture','cat','files/result.hsf1'],30).stdout
 if len(raw)>87500:raise ValueError('FRAME_CAP')
 sys.path.insert(0,str(BASE))
 from check_result import unframe,assess
 verdict=assess(unframe(raw.decode('ascii')))
 (WORK/'validated-result.hsf1').write_bytes(raw) # publication waits until owned cleanup
 return 0 if verdict['assessment']=='MATCH' else 1
def classified_run(operation):
 try:return operation()
 except (subprocess.SubprocessError,OSError,ValueError,UnicodeError,KeyError,TypeError):
  print('{"scope":"HANDSHAKE_FIXTURE_ONLY","stage":"TRANSPORT_OR_EXECUTION_UNAVAILABLE","assessment":"UNKNOWN"}')
  return 2
 except Exception:
  print('{"scope":"HANDSHAKE_FIXTURE_ONLY","stage":"HARNESS_INTERNAL_ERROR","assessment":"UNKNOWN"}')
  return 2
if __name__=='__main__':
 # Intentionally closed: bootstrap/owned emulator lifecycle not complete or authorized.
 print('{"scope":"HANDSHAKE_FIXTURE_ONLY","stage":"BOOTSTRAP_LOCK_INCOMPLETE"}')
 raise SystemExit(78)

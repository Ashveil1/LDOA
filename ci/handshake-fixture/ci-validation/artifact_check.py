#!/usr/bin/env python3
"""Private APK static binding check before install. No target execution."""
import pathlib,json,zipfile,hashlib,subprocess,re,os
def check_zip(apk,expected_native,manifest_bytes,runtime_sha,is_test):
 with zipfile.ZipFile(apk) as z:
  names=z.namelist()
  if len(names)!=len(set(names)):raise ValueError('APK_DUPLICATE')
  native=[n for n in names if n.startswith('lib/')]
  if is_test:
   if native:raise ValueError('TEST_NATIVE')
  else:
   wanted={'lib/x86_64/'+n for n in expected_native}|{'lib/x86_64/libhandshake_safe.so'}
   if set(native)!=wanted:raise ValueError('NATIVE_INVENTORY')
   for n,pin in expected_native.items():
    if hashlib.sha256(z.read('lib/x86_64/'+n)).hexdigest()!=pin:raise ValueError('PREBUILT_PIN')
   if z.read('assets/runtime.manifest.json')!=manifest_bytes or hashlib.sha256(z.read('assets/runtime.zip')).hexdigest()!=runtime_sha:raise ValueError('ASSET_PIN')
 return {'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'bytes':apk.stat().st_size}
def inspect(base,sdk,app,test):
 native={r['name']:r['sha256'] for r in json.loads((base/'android-native-inputs.json').read_text())['files']};manifest=(base/'runtime-asset-plan.json').read_bytes();runtime=json.loads(manifest)['zip_sha256'];report={};cert=None
 for label,apk in [('app',app),('test',test)]:
  report[label]=check_zip(apk,native,manifest,runtime,label=='test')
  badging=subprocess.run([str(sdk/'build-tools/35.0.0/aapt2'),'dump','badging',str(apk)],capture_output=True,text=True,check=True,timeout=15).stdout
  package='dev.phonelinux.handshakefixture'+('.test' if label=='test' else '')
  if "package: name='"+package+"'" not in badging or 'application-debuggable' not in badging or 'uses-permission:' in badging:raise ValueError('APK_SCOPE')
  signed=subprocess.run([str(sdk/'build-tools/35.0.0/apksigner'),'verify','--print-certs',str(apk)],capture_output=True,text=True,check=True,timeout=15).stdout
  m=re.search(r'certificate SHA-256 digest: ([0-9a-f]{64})',signed)
  if not m or cert is not None and cert!=m[1]:raise ValueError('SIGNER_SCOPE')
  cert=m[1];report[label]['certificate_sha256']=cert
 # Inspect actual test DEX for the fixed scope gate route and exact compiled pins, not just source.
 payload=json.loads((base/'generated/payload-binding.json').read_text());dextext=[]
 with zipfile.ZipFile(test) as z:
  for name in z.namelist():
   if not re.fullmatch(r'classes(?:[0-9]+)?\.dex',name):continue
   raw=z.read(name)
   if len(raw)>4*1024*1024:raise ValueError('DEX_CAP')
   file=base/'private-run'/('inspect-'+name);file.write_bytes(raw)
   dextext.append(subprocess.run([str(sdk/'build-tools/35.0.0/dexdump'),'-d',str(file)],capture_output=True,text=True,check=True,timeout=15).stdout)
 text='\n'.join(dextext)
 for value in ['HSF1_17CASE_V1',payload['source_pin'],payload['native_pin'],payload['guest_pin'],payload['runtime_zip_sha256'],'FixedScopeGate;','PREIMPLEMENTATION_BLOCKED']:
  if value not in text:raise ValueError('DEX_GATE_OR_PIN')
 onstart=re.search(r'HandshakeInstrumentation.onStart:\(\)V(.*?)(?:\n      catches)',text,re.S)
 if not onstart or 'FixedScopeGate;.accepts' not in onstart[1] or 'DormantEntry;.execute' not in onstart[1]:raise ValueError('DEX_OPTIN_ROUTE')
 report['dex_gate_and_pins']=True
 # Actual runtime authorization remains independent of artifact inspection.

 payload=json.loads((base/'generated/payload-binding.json').read_text());changed=[n for n,pin in payload['source_files'].items() if hashlib.sha256((base/n).read_bytes()).hexdigest()!=pin]
 if changed:raise ValueError('SOURCE_CHANGED')
 report['source_pin']=payload['source_pin'];return report

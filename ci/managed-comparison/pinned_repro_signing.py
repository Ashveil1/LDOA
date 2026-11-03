#!/usr/bin/env python3
"""CI-only existing secret use. Never fetches a secret or prints tool output."""
import base64,hashlib,json,os,pathlib,re,selectors,shutil,signal,subprocess,tempfile,time
class Refused(Exception):pass
class Stop(BaseException):pass

def run(args,env,seconds,cap=65536):
 p=subprocess.Popen(args,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
 sel=selectors.DefaultSelector();sel.register(p.stdout,selectors.EVENT_READ);raw=bytearray();end=time.monotonic()+seconds
 try:
  while True:
   left=end-time.monotonic()
   if left<=0:raise Refused('private tool deadline')
   ready=sel.select(min(.2,left))
   if not ready:continue
   b=os.read(p.stdout.fileno(),65536)
   if not b:break
   raw.extend(b)
   if len(raw)>cap:raise Refused('private tool output cap')
  if p.wait(timeout=max(.01,end-time.monotonic()))!=0:raise Refused('private tool failed')
  return bytes(raw)
 finally:
  sel.close();p.stdout.close()
  if p.poll() is None:
   os.killpg(p.pid,signal.SIGTERM)
   try:p.wait(timeout=2)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=2)

def fingerprints(raw,expected):
 text=raw.decode('ascii','strict')
 certs=re.findall(r'^Signer #([0-9]+) certificate SHA-256 digest: ([0-9a-fA-F]{64})$',text,re.M)
 if certs!=[('1',expected)] and certs!=[('1',expected.upper())]:raise Refused('APK certificate mismatch/count')
 # Every certificate SHA-256 line must be recognized, never ignore extras.
 if len(re.findall(r'certificate SHA-256 digest:',text))!=1:raise Refused('APK multiple/unparsed certificate')
 return {'verified':True,'certificate_sha256':expected}

def signing(root,private,env):
 # Caller provides CI variable; never vault/API secret retrieval here.
 secret=env.get('LDOA_DEBUG_KEYSTORE_B64','')
 if not secret or len(secret)>65536:raise Refused('missing/oversize existing signing secret')
 clean={k:v for k,v in env.items() if k!='LDOA_DEBUG_KEYSTORE_B64'}
 os.environ.pop('LDOA_DEBUG_KEYSTORE_B64',None)
 temp=None
 try:
  temp=pathlib.Path(tempfile.mkdtemp(prefix='paired-signing-',dir=env['RUNNER_TEMP']));temp.chmod(0o700)
  key=temp/'debug.keystore'
  try:raw=base64.b64decode(secret,validate=True)
  except Exception:raise Refused('existing signing secret encoding') from None
  if not(0<len(raw)<=49152):raise Refused('decoded signing secret cap')
  fd=os.open(key,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
  with os.fdopen(fd,'wb') as f:f.write(raw)
  del raw,secret
  kt=str(pathlib.Path(env['JAVA_HOME'])/'bin/keytool')
  certpath=temp/'certificate.der'
  run([kt,'-exportcert','-keystore',str(key),'-storepass','android','-alias','androiddebugkey','-file',str(certpath)],clean,10)
  if certpath.is_symlink() or not certpath.is_file() or not(0<certpath.stat().st_size<=16384):raise Refused('certificate DER cap')
  cert=certpath.read_bytes();expected=hashlib.sha256(cert).hexdigest()
  args=['./gradlew','assembleDebug','assembleDebugAndroidTest','-Pldoa.keystore.path='+str(key),'-Pldoa.keystore.password=android','-Pldoa.key.alias=androiddebugkey','-Pldoa.key.password=android']
  run(args,clean,1800,4*1024*1024)
  apksigner=str(pathlib.Path(env['ANDROID_HOME'])/'build-tools/35.0.0/apksigner');result={}
  for name,p in {'app':'app/build/outputs/apk/debug/app-debug.apk','test':'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}.items():
   result[name]=fingerprints(run([apksigner,'verify','--print-certs',str(root/p)],clean,10),expected)
  if (temp.stat().st_mode&0o777)!=0o700 or (key.stat().st_mode&0o777)!=0o600:raise Refused('private signing permissions')
  # Only cert fingerprint/verified booleans, not key/path/tool output.
  out=private/'signing-receipt.json'
  with out.open('x') as f:json.dump(result,f,sort_keys=True,separators=(',',':'))
  return result
 finally:
  if temp is not None:
   shutil.rmtree(temp)
   if temp.exists():raise Refused('private signing cleanup')

def main():
 def stop(*_):raise Stop()
 for sig in [signal.SIGTERM,signal.SIGINT,signal.SIGHUP]:signal.signal(sig,stop)
 try:signing(pathlib.Path.cwd(),pathlib.Path(os.environ['FLOOR_EVIDENCE']),dict(os.environ))
 except BaseException:
  # No exception data: it may contain secret tool output, file paths or argv.
  print('SIGNING1 REFUSED private signing or cleanup failure',flush=True);return 1
 print('SIGNING1 PASS existing-key app/test certificate binding; private key removed',flush=True);return 0
if __name__=='__main__':raise SystemExit(main())

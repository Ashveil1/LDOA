"""Future one-shot INPUT RESOLUTION ONLY. No build/install/runtime. Run after stage approval."""
import pathlib,os,sys,subprocess,tempfile,threading,json,shutil,ctypes
from transport import Store,UnixHTTP,Handler
from provision_gradle import provision
HERE=pathlib.Path(__file__).resolve().parent

def main():
 # Before any reachable payload URL: test actual namespace+seccomp boundary with synthetic origin.
 subprocess.run([sys.executable,'-m','unittest','-v','test_transport','test_provision'],cwd=HERE,check=True,timeout=40)
 parent=pathlib.Path(os.environ['RUNNER_TEMP']).resolve();root=parent/'ldoa-build-inputs-one'
 root.mkdir(mode=0o700) # preexisting run is STOP, never resume
 lib=ctypes.CDLL(None,use_errno=True)
 if lib.prctl(4,0,0,0,0):raise RuntimeError('DUMPABLE_DISABLE')
 work=root/'work';work.mkdir(mode=0o700)
 source=HERE.parent
 shutil.copytree(source,work/'fixture',ignore=shutil.ignore_patterns('resolver','__pycache__','.gradle','build','private-run'))
 key=work/'hs-fixture-private';key.mkdir(mode=0o700)
 # Resolution config requires a path to an existing key, but no signing/build task executes.
 # Empty placeholder is NOT a signing identity and must never be used by a build.
 (key/'debug.keystore').touch(mode=0o600)
 executable=provision(root)
 # Retain the nonsecret binding in public job logs before always-run cleanup.
 receipt=json.loads((root/'gradle-provision-receipt.json').read_text())
 print(json.dumps({'scope':'GRADLE_PROVISION_BINDING',**{k:receipt[k] for k in ['start','final_host','bytes','sha256','redirects']}},sort_keys=True),flush=True)
 store=Store(root/'maven');server=UnixHTTP(str(root/'broker.sock'),Handler);server.store=store
 threading.Thread(target=server.serve_forever,daemon=True).start()
 java=pathlib.Path(os.environ['JAVA_HOME_17_X64'])
 env={k:v for k,v in os.environ.items() if k in ['PATH','LANG','LC_ALL','ANDROID_HOME','ANDROID_NDK_HOME','HS_SDK_CMAKE']}
 env.update(JAVA_HOME=str(java),HOME=str(work),GRADLE_USER_HOME=str(work/'gradle-home'),TMPDIR=str(work),HS_FIXTURE_DEBUG_KEYSTORE=str(key/'debug.keystore'),HS_TARGET_WRITE_ROOT=str(work),PYTHONDONTWRITEBYTECODE='1')
 cmd=['unshare','--user','--map-root-user','--net',sys.executable,str(HERE/'confinement.py'),'--inside',str(root/'broker.sock'),str(executable),'--stacktrace','--no-daemon','--max-workers=2','--no-configuration-cache','--console=plain','-Dorg.gradle.java.installations.auto-download=false','-Dorg.gradle.daemon=false','-Djava.io.tmpdir='+str(work),'-I',str(HERE/'local-repositories.init.gradle'),'-p',str(work/'fixture'),'resolveLockedInputs']
 try:
  result=subprocess.run(cmd,env=env,timeout=1200);code=result.returncode
 finally:server.shutdown();server.server_close();store.close()
 print(json.dumps({'scope':'INPUT_RESOLUTION_ONLY','returncode':code,'maven_bytes':store.total,'artifacts':len(store.receipts),'build':False,'runtime':False}))
 return code
if __name__=='__main__':raise SystemExit(main())

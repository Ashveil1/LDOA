"""Future one-shot INPUT RESOLUTION ONLY. No build/install/runtime. Run after stage approval."""
import pathlib,os,sys,subprocess,tempfile,threading,json,shutil,ctypes,time
from transport import Store,UnixHTTP,Handler
from provision_gradle import provision
HERE=pathlib.Path(__file__).resolve().parent

def run_graph_stages(executable,work,env,unix_path,java_tmp,clock=time.monotonic,run=subprocess.run):
 # One deadline for prepare, both fixture Gradle invocations/checks, and actual AGP.
 deadline=clock()+1200
 prefix=['unshare','--user','--map-root-user','--net',sys.executable,str(HERE/'confinement.py'),'--inside',str(unix_path)]
 flags=['--no-daemon','--max-workers=2','--no-configuration-cache','--console=plain','-Dorg.gradle.java.installations.auto-download=false','-Dorg.gradle.daemon=false','-Djava.io.tmpdir='+str(java_tmp)]
 fixture=work/'graph-fixture';checker=fixture/'prepare_and_check.py'
 def invoke(stage,command,expect_failure=False):
  remaining=deadline-clock()
  if remaining<=0:raise RuntimeError('SHARED_GRAPH_STAGE_DEADLINE')
  result=run(prefix+command,env=env,timeout=remaining)
  okay=(result.returncode!=0) if expect_failure else (result.returncode==0)
  print(json.dumps({'scope':'GRAPH_STAGE','stage':stage,'returncode':result.returncode,'expected_failure':expect_failure,'accepted_exit':okay}),flush=True)
  if not okay:raise RuntimeError('GRAPH_STAGE_EXIT_MISMATCH:'+stage)
  return result.returncode
 invoke('fixture_prepare',[sys.executable,str(checker),'prepare'])
 fixture_command=[str(executable)]+flags+['--offline','-I',str(fixture/'graph-only.init.gradle'),'-p',str(fixture),'resolveLockedInputs']
 invoke('fixture_success',fixture_command)
 invoke('fixture_check_success',[sys.executable,str(checker),'check-success'])
 # Remove old evidence so a failed launch cannot pass with the prior inventory.
 (fixture/'selected-inputs.json').unlink()
 invoke('fixture_unresolved',fixture_command+['-PgraphFixtureUnresolved'],expect_failure=True)
 invoke('fixture_check_unresolved',[sys.executable,str(checker),'check-failure'])
 # Actual graph is reached ONLY after both fixture assertions, not merely exit checks.
 remaining=deadline-clock()
 if remaining<=0:raise RuntimeError('SHARED_GRAPH_STAGE_DEADLINE')
 actual=[str(executable)]+flags+['-I',str(HERE/'local-repositories.init.gradle'),'-p',str(work/'fixture'),'resolveLockedInputs']
 return run(prefix+actual,env=env,timeout=remaining).returncode

def main():
 # Before any reachable payload URL: test actual namespace+seccomp boundary with synthetic origin.
 subprocess.run([sys.executable,'-m','unittest','-v','test_transport','test_provision','test_pair','test_http_semantics','test_project_binding','test_graph_inventory','test_graph_stages','test_fixture_observability','test_io'],cwd=HERE,check=True,timeout=40)
 parent=pathlib.Path(os.environ['RUNNER_TEMP']).resolve();root=parent/'ldoa-build-inputs-one'
 root.mkdir(mode=0o700) # preexisting run is STOP, never resume
 lib=ctypes.CDLL(None,use_errno=True)
 if lib.prctl(4,0,0,0,0):raise RuntimeError('DUMPABLE_DISABLE')
 work=root/'work';work.mkdir(mode=0o700)
 source=HERE.parent
 shutil.copytree(source,work/'fixture',ignore=shutil.ignore_patterns('resolver','__pycache__','.gradle','build','private-run'))
 # Resolver is excluded above, so explicitly stage this reviewed offline fixture.
 shutil.copytree(HERE/'graph-fixture',work/'graph-fixture',ignore=shutil.ignore_patterns('__pycache__','repo','.gradle','build','selected-inputs.json','graph-only.init.gradle'))
 shutil.copyfile(HERE/'local-repositories.init.gradle',work/'local-repositories.init.gradle')
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
 try:
  code=run_graph_stages(executable,work,env,root/'broker.sock',work)
  inventory=work/'fixture'/'selected-inputs.json'
  if inventory.is_file():
   print(json.dumps({'scope':'GRAPH_INVENTORY_RESULT','inventory':json.loads(inventory.read_text())},sort_keys=True),flush=True)
 finally:
  server.shutdown();server.server_close();store.close()
  print(json.dumps(store.summary(),sort_keys=True),flush=True)
 print(json.dumps({'scope':'GRAPH_INVENTORY_PREPARATION','complete_build_input_lock':False,'external_artifact_selection':'UNQUALIFIED','returncode':code,'maven_bytes':store.total,'cached_responses':len(store.receipts),'build':False,'runtime':False}))
 return code
if __name__=='__main__':raise SystemExit(main())

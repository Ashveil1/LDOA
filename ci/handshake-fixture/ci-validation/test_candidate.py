import unittest,pathlib,tempfile,sys,json,subprocess,io,contextlib
from unittest.mock import patch
sys.path.insert(0,str(pathlib.Path(__file__).parent))
from avd_prepare import prepare
from target_once import classified_run
from owned_emulator import OwnedEmulator
class Candidate(unittest.TestCase):
 def test_avd_missing_layout_fails(self):
  with tempfile.TemporaryDirectory() as t:
   w=pathlib.Path(t);image=w/'image';image.mkdir()
   with self.assertRaises(ValueError):prepare(w,image)
 def test_fixed_avd_no_commands(self):
  with tempfile.TemporaryDirectory() as t:
   w=pathlib.Path(t);image=w/'image';image.mkdir()
   for n in ['system.img','vendor.img','ramdisk.img','kernel-ranchu']:(image/n).write_bytes(b'unit')
   home=prepare(w,image);s=(home/'hs-fixture.avd/config.ini').read_text();self.assertIn('target=android-34',s);self.assertIn('abi.type=x86_64',s);self.assertNotIn('command',s)
 def test_workflow_remains_false(self):
  s=pathlib.Path(__file__).with_name('manual-workflow.yml').read_text();self.assertIn('if: ${{ false }}',s);self.assertNotIn('upload-artifact', '\n'.join(l for l in s.splitlines() if not l.startswith('#')))
 def test_exit_taxonomy_actual_function(self):
  self.assertEqual(classified_run(lambda:0),0);self.assertEqual(classified_run(lambda:1),1)
  for exc in [ValueError('hash'),UnicodeError('frame'),OSError('adb'),subprocess.TimeoutExpired('instrument',240),subprocess.CalledProcessError(1,'install')]:
   def operation():raise exc
   output=io.StringIO()
   with contextlib.redirect_stdout(output):self.assertEqual(classified_run(operation),2)
   d=json.loads(output.getvalue());self.assertEqual(d['assessment'],'UNKNOWN');self.assertEqual(d['stage'],'TRANSPORT_OR_EXECUTION_UNAVAILABLE')
 def test_unexpected_exception_is_not_mismatch(self):
  def operation():raise RuntimeError('private detail must not print')
  output=io.StringIO()
  with contextlib.redirect_stdout(output):self.assertEqual(classified_run(operation),2)
  self.assertEqual(json.loads(output.getvalue())['stage'],'HARNESS_INTERNAL_ERROR');self.assertNotIn('private detail',output.getvalue())
  for kind in [KeyboardInterrupt,SystemExit]:
   def stop():raise kind()
   with self.assertRaises(kind):classified_run(stop)
 def test_owned_cleanup_no_broad_kill(self):
  from unittest.mock import Mock
  child=Mock();child.pid=12345;child.wait.side_effect=[subprocess.TimeoutExpired('owned',20),0]
  owner=OwnedEmulator(pathlib.Path('/fixed/emulator'),pathlib.Path('/fixed/avd'));owner.child=child
  with patch('owned_emulator.os.killpg') as kill:
   self.assertEqual(owner.close(),'OWNED_EMULATOR_EXITED_NOT_GUEST_ABSENCE_PROOF');self.assertEqual([call.args[0] for call in kill.call_args_list],[12345,12345])
 def test_job_remains_closed(self):self.assertIn('return 78',pathlib.Path(__file__).with_name('job_once.py').read_text())
class Lifecycle(unittest.TestCase):
 def test_actual_lifecycle_all_outcomes_cleanup_once(self):
  from lifecycle import run_once
  from unittest.mock import Mock
  for verdict in [0,1,2]:
   owner=Mock();owner.close.return_value='OWNED_EMULATOR_EXITED_NOT_GUEST_ABSENCE_PROOF';calls=[];steps={n:(lambda name=n:calls.append(name)) for n in ['bootstrap','guest','build','boot']};steps['target']=lambda:verdict;published=[]
   self.assertEqual(run_once(steps,owner,lambda r,c:published.append((r.copy(),c))),verdict);owner.close.assert_called_once();self.assertEqual(len(published),1);self.assertEqual(calls,['bootstrap','guest','build','boot'])
 def test_actual_lifecycle_firstfailure_no_retry(self):
  from lifecycle import run_once
  from unittest.mock import Mock
  for failure in ['bootstrap','guest','build','boot','target']:
   owner=Mock();owner.close.return_value='NOT_LAUNCHED';calls=[]
   def step(name):
    calls.append(name)
    if name==failure:raise RuntimeError('private')
    return 0
   steps={n:(lambda name=n:step(name)) for n in ['bootstrap','guest','build','boot','target']};published=[]
   self.assertEqual(run_once(steps,owner,lambda r,c:published.append((r.copy(),c))),2);self.assertEqual(calls[-1],failure);self.assertEqual(len(calls),len(set(calls)));self.assertEqual(published[0][0]['stage'],'HARNESS_INTERNAL_ERROR');owner.close.assert_called_once()
 def test_supervisor_interrupt_never_publishes_match(self):
  from lifecycle import run_once
  from unittest.mock import Mock
  owner=Mock();owner.close.return_value='NOT_LAUNCHED';steps={n:lambda:0 for n in ['bootstrap','guest','build','boot']}
  def stop():raise SystemExit(2)
  steps['target']=stop;out=[]
  with self.assertRaises(SystemExit):run_once(steps,owner,lambda r,c:out.append((r.copy(),c)))
  self.assertEqual(out[0][1],2);self.assertEqual(out[0][0]['stage'],'SUPERVISOR_INTERRUPTED');owner.close.assert_called_once()
 def test_output_failure_exit2_fixed_json(self):
  from lifecycle import run_once
  from unittest.mock import Mock
  owner=Mock();owner.close.return_value='NOT_LAUNCHED';steps={n:lambda:0 for n in ['bootstrap','guest','build','boot','target']}
  def publish(r,c):raise RuntimeError('private')
  out=io.StringIO()
  with contextlib.redirect_stdout(out):self.assertEqual(run_once(steps,owner,publish),2)
  self.assertEqual(json.loads(out.getvalue())['stage'],'OUTPUT_TRANSPORT_UNAVAILABLE')
 def test_cleanup_unknown_overrides_match(self):
  from lifecycle import run_once
  from unittest.mock import Mock
  owner=Mock();owner.close.return_value='UNKNOWN';steps={n:lambda:0 for n in ['bootstrap','guest','build','boot','target']};out=[];self.assertEqual(run_once(steps,owner,lambda r,c:out.append(r.copy())),2);self.assertEqual(out[0]['stage'],'CLEANUP_UNKNOWN')
class BootstrapPolicy(unittest.TestCase):
 def test_project_repo_rejected_by_source(self):self.assertIn('RepositoriesMode.FAIL_ON_PROJECT_REPOS',pathlib.Path(__file__).with_name('local_maven.init.gradle').read_text())
 def test_actual_redirect_handler_rejects_other_host(self):
  from fetch_locked import ScopedRedirect
  import urllib.request
  handler=ScopedRedirect('dl.google.com');req=urllib.request.Request('https://dl.google.com/start')
  for url in ['https://evil.invalid/file','http://dl.google.com/file']:
   with self.assertRaises(ValueError):handler.redirect_request(req,None,302,'redirect',{},url)
  self.assertEqual(handler.redirect_request(req,None,302,'redirect',{},'https://dl.google.com/file').full_url,'https://dl.google.com/file')
class SourceScope(unittest.TestCase):
 def test_private_run_excluded_from_payload_source(self):self.assertIn("'private-run'",(pathlib.Path(__file__).parents[1]/'prepare_payload.py').read_text())
 def test_candidate_commit_stays_unpinned(self):
  d=json.loads(pathlib.Path(__file__).with_name('candidate-commit.json').read_text());self.assertIsNone(d['commit']);self.assertIn('EXTERNAL_COMMIT_PIN_REQUIRED',pathlib.Path(__file__).with_name('job_once.py').read_text())
class ExternalPin(unittest.TestCase):
 def test_pin_failure_pre_resource_fixed_unknown(self):
  from job_once import run_candidate
  out=[]
  with patch('job_once.verify_external_pin',side_effect=ValueError('private/path')):
   self.assertEqual(run_candidate(pathlib.Path('/private'),pathlib.Path('/repo'),lambda r,c:out.append((r,c)),'0'*40),2)
  self.assertEqual(out,[({'scope':'HANDSHAKE_FIXTURE_ONLY','stage':'CANDIDATE_IDENTITY_UNAVAILABLE','assessment':'UNKNOWN','cleanup':'NOT_LAUNCHED'},2)])
 def test_actual_external_pin_contract(self):
  from job_once import verify_external_pin
  for pin in [None,'', 'bad','0'*39]:
   with self.assertRaises(ValueError):verify_external_pin(pathlib.Path('/repo'),pin)
  with patch('subprocess.check_output',return_value='a'*40+'\n'):
   self.assertEqual(verify_external_pin(pathlib.Path('/repo'),'a'*40),'a'*40)
   with self.assertRaises(ValueError):verify_external_pin(pathlib.Path('/repo'),'b'*40)
class Artifact(unittest.TestCase):
 def test_actual_zip_binding_positive_and_failures(self):
  import zipfile,hashlib
  from artifact_check import check_zip
  with tempfile.TemporaryDirectory() as tmp:
   apk=pathlib.Path(tmp)/'unit.apk';native={'libproot.so':'a'};payload=b'manifest';runtime=b'zip'
   def make(wrong=False,test=False):
    with zipfile.ZipFile(apk,'w') as z:
     if not test:
      z.writestr('lib/x86_64/libproot.so',b'wrong' if wrong else b'a');z.writestr('lib/x86_64/libhandshake_safe.so',b'jni');z.writestr('assets/runtime.manifest.json',payload);z.writestr('assets/runtime.zip',runtime)
   pins={'libproot.so':hashlib.sha256(b'a').hexdigest()};sha=hashlib.sha256(runtime).hexdigest();make();self.assertEqual(check_zip(apk,pins,payload,sha,False)['bytes'],apk.stat().st_size)
   make(True)
   with self.assertRaises(ValueError):check_zip(apk,pins,payload,sha,False)
   with self.assertRaises(ValueError):check_zip(apk,pins,payload,sha,True)
   make(test=True);check_zip(apk,pins,payload,sha,True)
if __name__=='__main__':unittest.main()

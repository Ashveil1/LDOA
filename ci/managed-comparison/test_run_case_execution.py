import pathlib,tempfile,unittest,subprocess,os,shutil,sys
from test_evidence import Evidence
P=pathlib.Path(__file__).parent
class Execution(unittest.TestCase):
 def execute(self,mode):
  fixture=Evidence();fixture.setUp();self.addCleanup(fixture.tearDown)
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);r=pathlib.Path(t.name);ci=r/'ci/managed-comparison';ci.mkdir(parents=True);bin=r/'bin';bin.mkdir()
  for name in ['run-case.sh','check_case.py']:shutil.copyfile(P/name,ci/name)
  (ci/'incoming-transfer.sh').write_text('exit "${TRANSFER_RC:-0}"\n')
  source=fixture.e/'original';(source/'caller.txt').unlink();(source/'phase.txt').unlink()
  if mode=='target-fail':(source/'managed-junit.xml').write_text('<testsuite tests="1" failures="1" skipped="0"><testcase classname="dev.phonelinux.proto.MobileDesktopEntryTest" name="mobileEntryManagedDesktopComparison"><failure/></testcase></testsuite>')
  f=bin/'adb';f.write_text('''#!/usr/bin/python3
import os,sys,shutil,pathlib
args=sys.argv
if 'install' in args:sys.exit(int(os.environ['INSTALL_RC']))
if 'instrument' in args:sys.exit(int(os.environ['INSTRUMENT_RC']))
if 'pull' in args:
 if os.environ['PULL_RC']!='0':sys.exit(int(os.environ['PULL_RC']))
 shutil.copytree(os.environ['SOURCE'],args[-1]);sys.exit(0)
raise AssertionError(args)
''');f.chmod(0o700)
  env={**os.environ,'PATH':str(bin)+':'+os.environ['PATH'],'GITHUB_WORKSPACE':str(r),'MANAGED_CASE_EXEC_APPROVED':'yes','SOURCE':str(source),'INSTALL_RC':'31' if mode=='install-fail' else '0','INSTRUMENT_RC':'42' if mode=='instrument-fail' else '0','PULL_RC':'8' if mode=='pull-fail' else '0','TRANSFER_RC':'29' if mode=='transfer-fail' else '0'}
  result=subprocess.run(['bash',str(ci/'run-case.sh'),'original','synthetic','a'*64],cwd=r,env=env,capture_output=True,text=True)
  return result,r/'comparison-evidence/original',ci
 def test_success_real_shell_and_final_checker(self):
  x,out,ci=self.execute('ok');self.assertEqual(x.returncode,0,x.stderr+x.stdout);self.assertEqual((out/'caller.txt').read_text(),'caller_exit=0\n');self.assertEqual((out/'phase.txt').read_text(),'instrumentation_caller_rc=0\n')
  final=subprocess.run([sys.executable,str(ci/'check_case.py'),str(out),'original','a'*64],capture_output=True,text=True);self.assertEqual(final.returncode,0,final.stdout)
 def test_primary_failure_actual_exit_receipts(self):
  for mode,rc,phase in [('install-fail',31,None),('transfer-fail',29,None),('instrument-fail',1,'42'),('target-fail',1,'0'),('pull-fail',70,'0')]:
   with self.subTest(mode=mode):
    x,out,ci=self.execute(mode);self.assertEqual(x.returncode,rc,x.stderr+x.stdout);self.assertEqual((out/'caller.txt').read_text(),f'caller_exit={rc}\n')
    if phase is None:self.assertFalse((out/'phase.txt').exists())
    else:self.assertEqual((out/'phase.txt').read_text(),f'instrumentation_caller_rc={phase}\n')
    final=subprocess.run([sys.executable,str(ci/'check_case.py'),str(out),'original','a'*64],capture_output=True);self.assertNotEqual(final.returncode,0)
 def test_inflight_does_not_create_caller(self):
  x,out,ci=self.execute('ok');(out/'caller.txt').unlink()
  x=subprocess.run([sys.executable,str(ci/'check_case.py'),str(out),'original','a'*64,'--inflight'],capture_output=True);self.assertEqual(x.returncode,0);self.assertFalse((out/'caller.txt').exists())
  x=subprocess.run([sys.executable,str(ci/'check_case.py'),str(out),'original','a'*64],capture_output=True);self.assertNotEqual(x.returncode,0)
if __name__=='__main__':unittest.main()

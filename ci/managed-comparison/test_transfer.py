import pathlib,tempfile,subprocess,os,unittest,shlex
P=pathlib.Path(__file__).parent
class Transfer(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.addCleanup(self.t.cleanup);self.root=pathlib.Path(self.t.name);self.app=self.root/'app';self.app.mkdir();self.bin=self.root/'bin';self.bin.mkdir();self.source=self.root/'source';self.source.mkdir();(self.source/'fixture').write_text('SYNTHETIC');self.tmp=self.root/'tmp';self.tmp.mkdir()
  self.calls=self.root/'calls';self.calls.touch()
  f=self.bin/'readlink';f.write_text('#!/bin/sh\nprintf "%s\\n" "${FIXTURE_CANONICAL:-$FIXTURE_APP}"\n');f.chmod(0o700)
  f=self.bin/'cp';f.write_text('#!/bin/sh\nif [ "$FAIL_COPY" = yes ]; then exit 37; fi\nexec /bin/cp "$@"\n');f.chmod(0o700)
 def execute(self,fail=False,context=False):
  s=(P/'incoming-transfer.sh').read_text();line=next(l for l in s.splitlines() if '&& cp -R' in l)
  # Shell expands host escapes only, as real adb invocation; shell command is captured by stub.
  adb=self.bin/'adb';adb.write_text('''#!/usr/bin/python3
import os,sys,subprocess,pathlib,shlex
cmd=sys.argv[-1];prefix="run-as dev.phonelinux.proto sh -c "
assert cmd.startswith(prefix)
body=shlex.split(cmd[len(prefix):])[0]
body=body.replace('/data/local/tmp/ldoa-managed-incoming',os.environ['FIXTURE_SOURCE'])
pathlib.Path(os.environ['FIXTURE_CALLS']).write_text(body)
sys.exit(subprocess.run(['/bin/sh','-c',body],cwd=os.environ['FIXTURE_APP']).returncode)
''');adb.chmod(0o700)
  env={**os.environ,'PATH':str(self.bin)+':'+os.environ['PATH'],'SERIAL':'emulator-5554','FIXTURE_APP':str(self.app),'FIXTURE_SOURCE':str(self.source),'FIXTURE_CALLS':str(self.calls),'FAIL_COPY':'yes' if fail else 'no','FIXTURE_CANONICAL':str(self.root/'wrong') if context else str(self.app)}
  return subprocess.run(['bash','-c',line],env=env,capture_output=True,text=True)
 def test_fresh_no_files(self):
  x=self.execute();self.assertEqual(x.returncode,0,x.stderr);self.assertEqual((self.app/'files/managed-incoming/fixture').read_text(),'SYNTHETIC');self.assertIn('$(pwd -P)',self.calls.read_text())
 def test_existing_safe_files(self):
  (self.app/'files').mkdir();(self.app/'files/keep').write_text('keep');self.assertEqual(self.execute().returncode,0);self.assertEqual((self.app/'files/keep').read_text(),'keep')
 def test_symlink_parent(self):
  other=self.root/'other';other.mkdir();(self.app/'files').symlink_to(other);self.assertNotEqual(self.execute().returncode,0);self.assertFalse((other/'managed-incoming').exists())
 def test_nondirectory_parent(self):
  (self.app/'files').write_text('keep');self.assertNotEqual(self.execute().returncode,0);self.assertEqual((self.app/'files').read_text(),'keep')
 def test_existing_incoming(self):
  d=self.app/'files/managed-incoming';d.mkdir(parents=True);(d/'keep').write_text('keep');self.assertNotEqual(self.execute().returncode,0);self.assertEqual((d/'keep').read_text(),'keep');self.assertFalse((d/'fixture').exists())
 def test_dangling_incoming(self):
  (self.app/'files').mkdir();(self.app/'files/managed-incoming').symlink_to(self.root/'absent');self.assertNotEqual(self.execute().returncode,0)
 def test_wrong_runas_context(self):
  self.assertNotEqual(self.execute(context=True).returncode,0);self.assertFalse((self.app/'files').exists())
 def test_refusal_preflight_before_push(self):
  s=(P/'incoming-transfer.sh').read_text();pre=s.index('test ! -e files/managed-incoming');tmp=s.index('test ! -e /data/local/tmp/ldoa-managed-incoming');push=s.index('adb -s "$SERIAL" push')
  self.assertLess(pre,push);self.assertLess(tmp,push);self.assertIn('test ! -L /data/local/tmp/ldoa-managed-incoming',s);self.assertNotIn('rm ',s);self.assertNotIn('mkdir -p',s)
 def test_copy_failure_exact(self):
  self.assertEqual(self.execute(True).returncode,37);self.assertTrue((self.app/'files/managed-incoming').is_dir());self.assertFalse((self.app/'files/managed-incoming/fixture').exists());self.assertNotEqual(self.execute().returncode,0)
if __name__=='__main__':unittest.main()

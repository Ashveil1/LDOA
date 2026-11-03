import pathlib,unittest,subprocess,os,shutil
P=pathlib.Path(__file__).parent
class Copy(unittest.TestCase):
 def execute(self,variant):
  # Real current directory planner and complete synthetic pinned inventory.
  # Patch only fixture source hashes, never runtime source-pin validation.
  from test_directory_plan import Plan
  fixture=Plan();fixture.setUp();self.addCleanup(fixture.doCleanups);fixture.make()
  r=fixture.root;app=r/'app';app.mkdir();bin=r/'bin';bin.mkdir();tmp=r/'incoming';shutil.copytree(fixture.src,tmp)
  body=r/'identity-body.sh';s=(P/'identity-body.sh').read_text().replace('/data/data/dev.phonelinux.proto',str(app)).replace('/data/user/0/dev.phonelinux.proto',str(r/'alias'));body.write_text(s)
  if variant=='safe':(app/'files').mkdir();(app/'files/keep').write_text('keep')
  if variant=='link':(app/'files').symlink_to(tmp)
  if variant=='file':(app/'files').write_text('keep')
  if variant=='existing':(app/'files/managed-incoming').mkdir(parents=True);(app/'files/managed-incoming/keep').write_text('keep')
  if variant=='dangling':(app/'files').mkdir();(app/'files/managed-incoming').symlink_to(r/'absent')
  if variant=='wrong-cwd':(r/'wrong').mkdir()
  if variant=='missingempty':(tmp/'payload/lib/pcmanfm').rmdir()
  if variant=='mutated':(tmp/'payload/bin/fixture').write_text('changed')
  for n,script in [('id','printf 10123'),('stat','v=$(/usr/bin/stat "$@") || exit $?; printf "%s\\n" "${v%:*:*}:10123:10123"'),('cp','exit 37' if variant=='copy-fail' else 'exec /bin/cp "$@"')]:
   f=bin/n;f.write_text('#!/bin/sh\n'+script+'\n');f.chmod(0o700)
  f=bin/'adb';f.write_text('''#!/usr/bin/python3
import os,sys,pathlib,subprocess
assert sys.argv[1:]==['-s','emulator-5554','shell','-T','run-as','dev.phonelinux.proto','sh']
s=sys.stdin.read().replace('/data/local/tmp/ldoa-managed-incoming',os.environ['INCOMING'])
pathlib.Path(os.environ['CALLS']).write_text(s)
sys.exit(subprocess.run(['sh','-c',s],cwd=os.environ['CWD']).returncode)
''');f.chmod(0o700)
  s=(P/'incoming-transfer.sh').read_text();s=s[s.index('COPY_BODY=$(mktemp)'):s.index("echo 'TRANSFER ONLY")]
  script='set -e\nguard(){ shift; "$@"; };\n'+s
  x=subprocess.run(['bash','-c',script],env={**os.environ,'PATH':str(bin)+':'+os.environ['PATH'],'HOME':str(app),'BODY':str(body),'PLAN':str(fixture.out),'SERIAL':'emulator-5554','INCOMING':str(tmp),'CALLS':str(r/'calls'),'CWD':str(r/'wrong') if variant=='wrong-cwd' else str(app)},capture_output=True,text=True);return x,app
 def test_copy_fresh_safe(self):
  for v in ['fresh','safe']:
   x,a=self.execute(v);self.assertEqual(x.returncode,0,x.stderr);self.assertEqual((a/'files/managed-incoming/payload/bin/fixture').read_text(),'SYNTHETIC NONEXECUTABLE payload/bin/fixture');self.assertTrue((a/'files/managed-incoming/payload/lib/pcmanfm').is_dir())
 # Current appended exact-inventory verifier cd fails with2 after a refused
 # &&-chain, while existing destination refusal reaches inventory exit1.
 def test_identity_and_existing_refusal(self):
  for v,rc in [('wrong-cwd',76),('link',2),('file',2),('existing',1),('dangling',2),('copy-fail',37)]:
   with self.subTest(v=v):x,a=self.execute(v);self.assertEqual(x.returncode,rc,x.stderr);self.assertFalse((a/'files/managed-incoming/payload/bin/fixture').exists())
 def test_inventory_fault_refusal(self):
  for v in ['missingempty','mutated']:
   with self.subTest(v=v):x,a=self.execute(v);self.assertNotEqual(x.returncode,0);self.assertTrue((a/'files/managed-incoming').is_dir())
 def test_body_pin(self):
  import hashlib
  self.assertEqual(hashlib.sha256((P/'identity-body.sh').read_bytes()).hexdigest(),'7fe74068d6d1de066d7819fe9133e96bb174ae3cd504d0462506faa8a8434c6e')
if __name__=='__main__':unittest.main()

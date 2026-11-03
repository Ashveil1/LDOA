import pathlib,tempfile,subprocess,os,unittest
P=pathlib.Path(__file__).parent
LABELS=['context-identity','parent-nonlink','parent-type','incoming-absent','incoming-nonlink','tmp-absent','tmp-nonlink','tmp-scaffold','push','tmp-inventory','private-copy']
class Guards(unittest.TestCase):
 def exercise(self,fail):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);r=pathlib.Path(t.name);e=r/'comparison-evidence/original';e.mkdir(parents=True);bin=r/'bin';bin.mkdir()
  adb=bin/'adb';adb.write_text('''#!/usr/bin/python3
import os,pathlib,sys
f=pathlib.Path(os.environ['COUNT']);i=int(f.read_text()) if f.exists() else 0;f.write_text(str(i+1))
print('DO NOT EXPORT PRIVATE PATH/CONTENT')
sys.exit(29 if str(i)==os.environ['FAIL_INDEX'] else 0)
''');adb.chmod(0o700)
  from test_directory_plan import Plan
  fixture=Plan();fixture.setUp();self.addCleanup(fixture.doCleanups);fixture.make()
  planner=bin/'python3';planner.write_text('#!/bin/sh\nmkdir "$4" && /bin/cp "$PLANS/"* "$4/"\n');planner.chmod(0o700)
  s=(P/'incoming-transfer.sh').read_text();a=s.index('# Fixed labels');b=s.index("echo 'TRANSFER ONLY");script=s[a:b].replace('SCRIPT=$(realpath -e "$0")','SCRIPT="'+str(P/'incoming-transfer.sh')+'"').replace('"$CASE" /data/local/tmp','"$CASE" /data/local/tmp')
  x=subprocess.run(['bash','-e','-c',script],env={**os.environ,'PATH':str(bin)+':'+os.environ['PATH'],'COUNT':str(r/'count'),'FAIL_INDEX':str(fail),'GITHUB_WORKSPACE':str(r),'MANAGED_TRANSFER_CASE':'original','SERIAL':'emulator-5554','CASE':str(fixture.src),'PIN':fixture.pin,'PLANS':str(fixture.out)},capture_output=True,text=True)
  return x,(e/'transfer-guards.txt').read_text()
 def test_every_failure_receipt(self):
  for i,label in enumerate(LABELS):
   with self.subTest(label=label):
    x,receipt=self.exercise(i);self.assertEqual(x.returncode,29);self.assertEqual(receipt,'schema=1\n'+''.join('guard='+l+' rc='+('29' if j==i else '0')+'\n' for j,l in enumerate(LABELS[:i+1])));self.assertNotIn('PRIVATE',receipt+x.stdout+x.stderr)
 def test_success(self):
  x,receipt=self.exercise(-1);self.assertEqual(x.returncode,0);self.assertEqual(receipt,'schema=1\n'+''.join('guard='+l+' rc=0\n' for l in LABELS))
 def test_refusals_unchanged(self):
  s=(P/'incoming-transfer.sh').read_text();self.assertNotIn('rm ',s.replace('rm -f "$COPY_BODY"','').replace('rm -rf "$PLAN"',''));self.assertNotIn('mkdir -p',s);self.assertIn('mkdir -m 700 files/managed-incoming',s);self.assertIn('test ! -L files',s);self.assertIn('test ! -L /data/local/tmp/ldoa-managed-incoming',s)
if __name__=='__main__':unittest.main()

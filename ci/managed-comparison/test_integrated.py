import unittest,pathlib,tempfile,subprocess,os,shlex,hashlib
P=pathlib.Path(__file__).parent
LABELS=['context-identity','parent-nonlink','parent-type','incoming-absent','incoming-nonlink','tmp-absent','tmp-nonlink']
class Integrated(unittest.TestCase):
 def runfixture(self,failed):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);p=pathlib.Path(t.name);e=p/'preflight-evidence';e.mkdir();b=p/'bin';b.mkdir();count=p/'count'
  f=b/'adb';f.write_text('''#!/usr/bin/python3
import pathlib,sys,os
p=pathlib.Path(os.environ['COUNT']);i=int(p.read_text()) if p.exists() else 0;p.write_text(str(i+1))
if i==0:
 assert sys.argv[1:]==['-s','emulator-5554','shell','-T','run-as','dev.phonelinux.proto','sh']
 assert sys.stdin.read()==pathlib.Path(os.environ['BODY']).read_text()
print('PRIVATE DECOY')
sys.exit(76 if i==int(os.environ['FAIL']) else 0)
''');f.chmod(0o700)
  x=subprocess.run(['bash',str(P/'preflight-only.sh')],env={**os.environ,'PATH':str(b)+':'+os.environ['PATH'],'COUNT':str(count),'FAIL':str(failed),'BODY':str(P/'identity-body.sh'),'GITHUB_WORKSPACE':str(p)},capture_output=True,text=True)
  receipt=e/'transfer-guards.txt';return x,receipt
 def test_allrun_success_and_eachfailure(self):
  for i in range(-1,7):
   with self.subTest(i=i):
    x,r=self.runfixture(i);self.assertEqual(x.returncode,0 if i==-1 else 1);self.assertEqual(r.read_text(),'schema=1\n'+''.join('guard='+n+' rc='+('76' if k==i else '0')+'\n' for k,n in enumerate(LABELS)));self.assertNotIn('PRIVATE',x.stdout+x.stderr);v=subprocess.run(['python3',str(P/'preflight-receipt.py'),str(r)],capture_output=True);self.assertEqual(v.returncode,0)
 def test_verifier_refuses_old_order_missing_noise(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'r'
   for s in ['schema=1\nguard=context-cwd rc=0\n','privatepaths','schema=1\n'+'guard=context-identity rc=0\n'*7]:
    p.write_text(s);x=subprocess.run(['python3',str(P/'preflight-receipt.py'),str(p)],capture_output=True);self.assertNotEqual(x.returncode,0);self.assertEqual(x.stdout,b'')
 def test_exact_reviewed_identity_and_workflow(self):
  self.assertEqual(hashlib.sha256((P/'identity-body.sh').read_bytes()).hexdigest(),'7fe74068d6d1de066d7819fe9133e96bb174ae3cd504d0462506faa8a8434c6e');self.assertEqual(hashlib.sha256((P.parents[1]/'.github/workflows/mobile-desktop.yml').read_bytes()).hexdigest(),'35ec4901d245fd8b7523731043263fd623746e5c69139878830b63e4cfc2f6f1')
if __name__=='__main__':unittest.main()

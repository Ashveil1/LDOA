import pathlib,tempfile,subprocess,os,unittest,shlex,yaml
P=pathlib.Path(__file__).parent
class Preflight(unittest.TestCase):
 def test_context_rc_distinct(self):
  s=(P/'preflight-only.sh').read_text();line=next(l for l in s.splitlines() if l.startswith('guard context-equal '));cmd=subprocess.check_output(['bash','-c','guard(){ printf \"%s\" \"${@: -1}\"; }; '+line],text=True);body=shlex.split(cmd)[-1]
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);f=p/'readlink'
   for script,expected in [('exit 8',71),('exit 0',72),('printf /different',1),('pwd -P',0)]:
    f.write_text('#!/bin/sh\n'+script+'\n');f.chmod(0o700);x=subprocess.run(['sh','-c',body],env={**os.environ,'PATH':str(p)+':'+os.environ['PATH']});self.assertEqual(x.returncode,expected)
 def test_no_mutation_preflight(self):
  s=(P/'preflight-only.sh').read_text();self.assertNotIn(' push ',s);self.assertNotIn('mkdir ',s);self.assertNotIn('cp -R',s);self.assertNotIn('am instrument',s)
 def test_workflow_scope(self):
  w=yaml.load((P/'preflight-only-proposal.yml').read_text(),Loader=yaml.BaseLoader);s=(P/'preflight-only-proposal.yml').read_text();self.assertEqual(w['jobs']['preflight']['runs-on'],'ubuntu-24.04');self.assertEqual(s.count('uses: reactivecircus/android-emulator-runner'),1);self.assertIn('cache-disabled: true',s);self.assertNotIn('upload-artifact',s);self.assertNotIn('assembleDebugAndroidTest',s);self.assertNotIn('build-and-test.sh',s);self.assertIn('./gradlew assembleDebug',s)
 def test_receipt_verifier(self):
  labels=['context-cwd','context-canonical','context-equal','parent-nonlink','parent-type','incoming-absent','incoming-nonlink','tmp-absent','tmp-nonlink']
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'receipt';p.write_text('schema=1\n'+''.join('guard='+l+' rc=0\n' for l in labels));x=subprocess.run(['python3',str(P/'preflight-receipt.py'),str(p)],capture_output=True);self.assertEqual(x.returncode,0)
   p.write_text('private arbitrary content');x=subprocess.run(['python3',str(P/'preflight-receipt.py'),str(p)],capture_output=True);self.assertNotEqual(x.returncode,0);self.assertNotIn(b'private arbitrary content',x.stdout)
if __name__=='__main__':unittest.main()

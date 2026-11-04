"""Pure literal installer tests. All filesystem/privilege APIs mocked; no sudo or runner reads."""
import unittest,pathlib,io,json,types
from unittest.mock import patch
R=pathlib.Path(__file__).resolve().parent
class StatReceipt(unittest.TestCase):
 def execute(self,states,head='a'*40):
  text=(R/'installer.literal.py').read_text();tree=__import__('ast').parse(text)
  # Exercise exact literal through first failing guard only. Earlier payload/context checks remain real pure code.
  binding={'actual':{'head_sha':head,'workflow_sha':'b'*40,'ref':'refs/heads/review','workflow_ref':'Ashveil1/LDOA/.github/workflows/isolated-ndk29-snapshot-candidate.yml@refs/heads/review','repository':'Ashveil1/LDOA'},'expected':{'head_sha':head,'ref':'refs/heads/review'}}
  args=['-', 'ubuntu22','20261026.341.1','github-hosted','1','1','Ashveil1/LDOA','b'*40,head,json.dumps(binding)]
  out=io.StringIO();stats=iter(states)
  with patch('sys.argv',args),patch('os.geteuid',return_value=0),patch('os.open',side_effect=[10,11]),patch('os.close'),patch('os.fstat',side_effect=lambda x:next(stats)),patch('os.mkdir',side_effect=AssertionError('mutation before STOP')),patch('sys.stdout',out):
   with self.assertRaisesRegex(RuntimeError,'INSTALL_BOUNDARY'):exec(compile(tree,'installer.literal.py','exec'),{'__name__':'synthetic'})
  return [json.loads(line) for line in out.getvalue().splitlines()]
 def st(self,uid=0,gid=0,mode=0o755):return types.SimpleNamespace(st_uid=uid,st_gid=gid,st_mode=mode)
 def test_root_writable_stops_before_opt_or_mkdir(self):
  rows=self.execute([self.st(mode=0o777)]);self.assertEqual(len(rows),1);self.assertEqual(rows[0]['label'],'ROOT');self.assertFalse(rows[0]['guard']);self.assertTrue(rows[0]['before_installation_effects'])
 def test_nonroot_nonwritable_still_rejected(self):
  rows=self.execute([self.st(uid=1001,mode=0o755)]);self.assertFalse(rows[0]['guard']);self.assertEqual(rows[0]['uid'],1001)
 def test_labels_fixed_no_external_text(self):
  rows=self.execute([self.st(mode=0o777)])
  self.assertEqual(set(rows[0]),{'scope','label','uid','gid','mode','guard','before_installation_effects'});self.assertNotIn('path',rows[0])
if __name__=='__main__':unittest.main()

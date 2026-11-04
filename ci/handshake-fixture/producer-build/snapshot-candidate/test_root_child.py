import unittest,pathlib,os,tempfile,json,io,types,ast
from unittest.mock import patch
import snapshot_contract as s
R=pathlib.Path(__file__).resolve().parent
class RootChild(unittest.TestCase):
 def test_exact_single_root_child_literals(self):
  helper=(R/'standalone_helper.py').read_text();install=(R/'installer.literal.py').read_text();cleanup=(R/'cleanup.literal.py').read_text();workflow=(R/'SNAPSHOT-WORKFLOW.INERT.yml').read_text()
  self.assertIn("PARENT='/ldoa-qualification'",helper)
  for text in [install,cleanup]:self.assertIn("for component in ['']:",text);self.assertNotIn("'opt'",text)
  self.assertNotIn('/opt/ldoa-qualification/',workflow);self.assertIn('/ldoa-qualification/ndk29_snapshot.py',workflow)
 def test_root_guard_before_mkdir_and_no_chmod_existing(self):
  install=(R/'installer.literal.py').read_text();self.assertLess(install.index('if not guard:fail()'),install.index("os.mkdir('ldoa-qualification'"));self.assertNotIn('os.chmod',install);self.assertNotIn('os.chown',install);self.assertIn("os.mkdir('ldoa-qualification',0o700,dir_fd=root)",install);self.assertIn('stat.S_IMODE(st.st_mode)!=0o700',(R/'cleanup.literal.py').read_text())
 def test_existing_root_child_nooverwrite(self):
  ns=dict(s.__dict__);ns['__name__']='synthetic';exec((R/'fd_lifecycle.py').read_text(),ns)
  with tempfile.TemporaryDirectory() as d:
   parent=pathlib.Path(d);(parent/'ndk29-snapshot').mkdir();fd=os.open(d,ns['FLAGS'])
   try:
    with self.assertRaises(FileExistsError):ns['create_root'](fd)
   finally:os.close(fd)
 def test_symlink_child_nooverwrite(self):
  ns=dict(s.__dict__);ns['__name__']='synthetic';exec((R/'fd_lifecycle.py').read_text(),ns)
  with tempfile.TemporaryDirectory() as d:
   parent=pathlib.Path(d);(parent/'outside').mkdir();(parent/'ndk29-snapshot').symlink_to('outside');fd=os.open(d,ns['FLAGS'])
   try:
    with self.assertRaises(FileExistsError):ns['create_root'](fd)
   finally:os.close(fd)
   self.assertTrue((parent/'outside').is_dir())
 def test_installer_existing_child_mkdir_failure_never_open_child(self):
  binding={'actual':{'head_sha':'a'*40,'workflow_sha':'b'*40,'ref':'refs/heads/review','workflow_ref':'Ashveil1/LDOA/.github/workflows/isolated-ndk29-snapshot-candidate.yml@refs/heads/review','repository':'Ashveil1/LDOA'},'expected':{'head_sha':'a'*40,'ref':'refs/heads/review'}}
  args=['-','ubuntu22','20261026.341.1','github-hosted','1','1','Ashveil1/LDOA','b'*40,'a'*40,json.dumps(binding)];out=io.StringIO();st=types.SimpleNamespace(st_uid=0,st_gid=0,st_mode=0o755)
  with patch('sys.argv',args),patch('os.geteuid',return_value=0),patch('os.open',side_effect=[10]) as opened,patch('os.close'),patch('os.fstat',return_value=st),patch('os.mkdir',side_effect=FileExistsError()),patch('sys.stdout',out):
   with self.assertRaises(FileExistsError):exec(compile((R/'installer.literal.py').read_text(),'literal','exec'),{'__name__':'synthetic'})
  self.assertEqual(opened.call_count,1);self.assertTrue(json.loads(out.getvalue())['guard'])
if __name__=='__main__':unittest.main()

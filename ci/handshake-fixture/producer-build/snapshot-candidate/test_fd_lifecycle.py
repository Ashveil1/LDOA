import unittest,tempfile,pathlib,os,time,stat
from unittest.mock import patch
import snapshot_contract as s
ns=dict(s.__dict__);ns['__name__']='synthetic';exec(pathlib.Path(__file__).with_name('fd_lifecycle.py').read_text(),ns)
class FD(unittest.TestCase):
 def image(self):return {'ImageOS':'ubuntu22','ImageVersion':'20261026.341.1','runner_environment':'github-hosted','run_id':'1','run_attempt':'1','repository':'Ashveil1/LDOA','workflow_sha':'a'*40,'head_sha':'b'*40}
 def fixture(self,r):
  for n in [*s.TOOLS,'source.properties',s.HOST+'/lib/clang/21/include/x.h']:
   p=r/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'Pkg.Revision = 29.0.14206865\n' if n=='source.properties' else b'fixture')
  p=r/s.TOOLS[0];p.rename(p.with_name('clang-21'));p.symlink_to('clang-21')
 def test_full_fd_lifecycle(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);src=r/'src';src.mkdir();self.fixture(src);parent=r/'private';parent.mkdir();fd=os.open(parent,ns['FLAGS']);events=[]
   try:out=ns['snapshot_only'](src,fd,self.image(),time.monotonic()+5,os.getuid(),events.append)
   finally:os.close(fd)
   self.assertEqual(out['status'],'SNAPSHOT_ONLY_NOT_APPROVED');self.assertEqual(out['cleanup'],'ABSENT');self.assertEqual(len(events),2)
 def test_cleanup_failure_preserves_reason_and_receipts(self):
  with tempfile.TemporaryDirectory() as d:
   fd=os.open(d,ns['FLAGS']);events=[]
   try:
    with patch.dict(ns,{'inventory':lambda *a:(_ for _ in ()).throw(s.Stop('REVISION')),'cleanup_fd':lambda *a:(_ for _ in ()).throw(s.Stop('ROOT_BOUNDARY'))}):out=ns['snapshot_only'](pathlib.Path(d),fd,self.image(),time.monotonic()+5,os.getuid(),events.append)
   finally:os.close(fd)
   self.assertEqual(out['reason'],'REVISION');self.assertEqual(out['cleanup_reason'],'ROOT_BOUNDARY');self.assertEqual(len(events),2)
 def test_emit_failure_still_cleanup(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);src=r/'src';src.mkdir();self.fixture(src);fd=os.open(d,ns['FLAGS'])
   try:out=ns['snapshot_only'](src,fd,self.image(),time.monotonic()+5,os.getuid(),lambda x:(_ for _ in ()).throw(OSError()))
   finally:os.close(fd)
   self.assertEqual(out['cleanup'],'ABSENT');self.assertEqual(out['status'],'STOP')
 def test_alias_component_metadata_captured(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);(r/'real').mkdir();(r/'real/x').write_bytes(b'x');(r/'alias').symlink_to('real')
   chain=s.alias_chain(r,'alias/x');self.assertTrue(any(x.get('target')=='real' for x in chain))
   first=str(chain);(r/'real2').mkdir();(r/'real2/x').write_bytes(b'x');(r/'alias').unlink();(r/'alias').symlink_to('real2');self.assertNotEqual(first,str(s.alias_chain(r,'alias/x')))
 def test_escape_component_stop(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);(r/'alias').symlink_to('../outside')
   with self.assertRaisesRegex(s.Stop,'ALIAS_CHAIN'):s.alias_chain(r,'alias/x')
 def test_parent_writable_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaises(s.Stop):ns['trusted_parent'](d,os.getuid())
 def test_identity_replaced_root_stops_cleanup(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);fd=os.open(d,ns['FLAGS']);rootfd,identity=ns['create_root'](fd);(p/ns['ROOT_NAME']).rename(p/'old');(p/ns['ROOT_NAME']).mkdir()
   try:
    with self.assertRaisesRegex(s.Stop,'ROOT_BOUNDARY'):ns['cleanup_fd'](fd,rootfd,identity)
   finally:os.close(rootfd);os.close(fd)
 def test_chunk_hash_deadline_size(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'x';p.write_bytes(b'x');fd=os.open(p,os.O_RDONLY)
   try:
    with self.assertRaisesRegex(s.Stop,'SNAPSHOT_CHANGED'):ns['hash_fd'](fd,2,time.monotonic()+5)
    with self.assertRaisesRegex(s.Stop,'DEADLINE'):ns['hash_fd'](fd,1,0)
   finally:os.close(fd)
 def test_cleanup_symlink_does_not_follow(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d);(p/'keep').write_bytes(b'keep');fd=os.open(d,ns['FLAGS']);rootfd,identity=ns['create_root'](fd);os.symlink('../keep','outside',dir_fd=rootfd)
   try:ns['cleanup_fd'](fd,rootfd,identity)
   finally:os.close(rootfd);os.close(fd)
   self.assertEqual((p/'keep').read_bytes(),b'keep')
if __name__=='__main__':unittest.main()

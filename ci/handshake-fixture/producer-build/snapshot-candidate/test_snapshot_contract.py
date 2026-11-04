import unittest,tempfile,pathlib,os,time,stat
from unittest.mock import patch
import snapshot_contract as s
class Snapshot(unittest.TestCase):
 def rows(self):
  def row(n,scope,size=1):return {'logical':n,'canonical':n,'scope':scope,'metadata':{'size':size},'sha256':'fixture-only-not-origin'}
  return [*[row(n,'TOOL') for n in s.TOOLS],row('source.properties','DESCRIPTOR'),row(s.HOST+'/lib/clang/21/include/x.h','HEADER')]
 def image(self):return {'ImageOS':'ubuntu22','ImageVersion':'20261026.341.1','runner_environment':'github-hosted','run_id':'1','run_attempt':'1','repository':'Ashveil1/LDOA','workflow_sha':'a'*40,'head_sha':'b'*40}
 def test_exact_inventory(self):self.assertEqual(s.admit(self.rows())['headers'],1)
 def test_missing_extra_tool_ambiguous_resource(self):
  for rows in [self.rows()[1:],self.rows()+[{'logical':'extra','canonical':'extra','scope':'TOOL','metadata':{'size':1}}],self.rows()+[{'logical':s.HOST+'/lib/clang/22/include/y.h','canonical':s.HOST+'/lib/clang/22/include/y.h','scope':'HEADER','metadata':{'size':1}}]]:
   with self.assertRaises(s.Stop):s.admit(rows)
 def test_caps_before_copy(self):
  rows=self.rows();rows[0]['metadata']['size']=s.MAX_TOOL+1
  with self.assertRaisesRegex(s.Stop,'SNAPSHOT_CAP'):s.admit(rows)
  rows=self.rows();rows[-1]['metadata']['size']=s.MAX_HEADERS+1
  with self.assertRaisesRegex(s.Stop,'HEADER_CAP'):s.admit(rows)
 def test_header_count_exact_boundary(self):
  rows=self.rows()[:-1]
  for i in range(4096):
   n=s.HOST+f'/lib/clang/21/include/h{i}.h';rows.append({'logical':n,'canonical':n,'scope':'HEADER','metadata':{'size':1}})
  self.assertEqual(s.admit(rows)['headers'],4096)
  n=s.HOST+'/lib/clang/21/include/extra.h';rows.append({'logical':n,'canonical':n,'scope':'HEADER','metadata':{'size':1}})
  with self.assertRaisesRegex(s.Stop,'HEADER_CAP'):s.admit(rows)
 def test_source_copy_hash_metadata(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);src=root/'src';src.mkdir();(src/'x').write_bytes(b'x');expected=s.metadata((src/'x').stat())
   self.assertEqual(s.copy_exact(src,'x',root/'copy',expected,time.monotonic()+5),'2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881')
 def test_symlink_and_changed_metadata_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);(root/'x').write_bytes(b'x');(root/'alias').symlink_to('x')
   with self.assertRaises(OSError):s.open_beneath(root,'alias')
   bad=s.metadata((root/'x').stat());bad['size']=2
   with self.assertRaisesRegex(s.Stop,'SOURCE_CHANGED'):s.copy_exact(root,'x',root/'copy',bad,time.monotonic()+5)
 def test_no_overwrite(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);(root/'x').write_bytes(b'x');(root/'copy').write_bytes(b'old')
   with self.assertRaises(FileExistsError):s.copy_exact(root,'x',root/'copy',s.metadata((root/'x').stat()),time.monotonic()+5)
 def test_deadline(self):
  with self.assertRaisesRegex(s.Stop,'DEADLINE'):s.tick(1,lambda:2)
  for v in [float('nan'),float('inf'),1301]:
   with self.assertRaisesRegex(s.Stop,'DEADLINE_INVALID'):s.tick(v,lambda:100)
 def test_managed_image_not_floating_label(self):
  self.assertEqual(s.managed_image(self.image())['ImageOS'],'ubuntu22')
  for v in ['ubuntu-22.04','latest','']:
   i=self.image();i['ImageVersion']=v
   with self.assertRaises(s.Stop):s.managed_image(i)
 def test_receipt_dedup_and_sizecap(self):
  rows=self.rows();a={'label':'ROOT_DEPTH_1','uid':0,'mode':0o755};parents={r['logical']:[a] for r in rows}
  r=s.receipt(self.image(),rows,parents,time.monotonic()+5);self.assertEqual(len(r['ancestors']),1);self.assertEqual(r['status'],'NOT_APPROVED')
  with patch.object(s,'MAX_RECEIPT',100):
   with self.assertRaisesRegex(s.Stop,'RECEIPT_CAP'):s.receipt(self.image(),rows,parents,time.monotonic()+5)
 def test_parent_privacy(self):
  rows=self.rows();parents={r['logical']:[{'label':'/private/outside'}] for r in rows}
  with self.assertRaisesRegex(s.Stop,'PATH'):s.receipt(self.image(),rows,parents,time.monotonic()+5)
 def test_hostdeps_separately_closed(self):self.assertFalse(s.host_dependency_contract()['tool_execution_allowed']);self.assertFalse(s.host_dependency_contract()['ldd'])
 def test_inventory_synthetic_no_contentread(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d)
   for n in [*s.TOOLS,*s.DESCRIPTORS,s.HOST+'/lib/clang/21/include/x.h']:
    p=r/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'fixture')
   with patch('builtins.open',side_effect=AssertionError('content read')):rows,parents,counts=s.inventory(r,time.monotonic()+5)
   self.assertEqual(counts['headers'],1);self.assertEqual(len(rows),7);self.assertEqual(len(parents),7)
 def test_inventory_escape_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);p=r/s.TOOLS[0];p.parent.mkdir(parents=True);p.symlink_to('/etc/passwd')
   with self.assertRaisesRegex(s.Stop,'PATH_ESCAPE'):s.inventory(r,time.monotonic()+5)
if __name__=='__main__':unittest.main()

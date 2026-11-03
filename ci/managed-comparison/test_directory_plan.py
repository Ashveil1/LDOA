import unittest,pathlib,tempfile,hashlib,json,subprocess,shutil
from unittest.mock import patch
import directory_plan as d
P=pathlib.Path(__file__).parent
class Plan(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.addCleanup(self.t.cleanup);self.root=pathlib.Path(self.t.name);self.src=self.root/'host';self.src.mkdir();self.tmp=self.root/'tmp';self.out=self.root/'plan'
  for n in ['payload/bin/fixture','source/orig.tar.xz','source/debian.tar.xz','source/default-size.patch']:
   p=self.src/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('SYNTHETIC NONEXECUTABLE '+n)
  (self.src/'payload/lib/pcmanfm').mkdir(parents=True);self.pins()
 def pins(self):
  files={str(p.relative_to(self.src)):hashlib.sha256(p.read_bytes()).hexdigest() for p in self.src.rglob('*') if p.is_file() and p.name!='manifest.json'};dirs=sorted(str(p.relative_to(self.src)) for p in self.src.rglob('*') if p.is_dir());m={'schema':1,'case':'original','prefix':'/opt/ldoa/pcmanfm/1.3.2-4build2-floor1','files':files,'directories':dirs,'bytes':sum(p.stat().st_size for p in self.src.rglob('*') if p.is_file() and p.name!='manifest.json')};b=json.dumps(m).encode();(self.src/'manifest.json').write_bytes(b);self.pin=hashlib.sha256(b).hexdigest();self.files=files
 def make(self):
  with patch.multiple(d,ORIG=self.files['source/orig.tar.xz'],DEB=self.files['source/debian.tar.xz'],PATCH=self.files['source/default-size.patch']):d.plan(self.src,self.pin,self.out)
 def scaffold(self):
  s=(self.out/'scaffold.sh').read_text().replace('/data/local/tmp/ldoa-managed-incoming',str(self.tmp));return subprocess.run(['sh','-c',s],capture_output=True)
 def transfer_file_only(self):
  # Initial scaffold keeps empty branches even when push sends regularfiles only.
  for f in self.src.rglob('*'):
   if f.is_file():target=self.tmp/f.relative_to(self.src);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,target)
 def verify(self,path):return subprocess.run(['sh',str(self.out/'verify.sh'),str(path)],capture_output=True)
 def test_full_emptydir_roundtrip(self):
  self.make();self.assertEqual(self.scaffold().returncode,0);self.transfer_file_only();self.assertEqual(self.verify(self.tmp).returncode,0);private=self.root/'private';private.mkdir();subprocess.run(['cp','-R',str(self.tmp)+'/.',str(private)],check=True);self.assertEqual(self.verify(private).returncode,0)
 def test_modes_policy_pin(self):
  self.make();scaffold=(self.out/'scaffold.sh').read_text()
  self.assertIn('mkdir -m755 "$T"',scaffold);self.assertNotIn('mkdir -m700',scaffold)
  self.assertEqual(self.out.stat().st_mode & 0o777,0o700)
  transfer=(P/'incoming-transfer.sh').read_text()
  self.assertIn('mkdir -m 700 files',transfer);self.assertIn('mkdir -m 700 files/managed-incoming',transfer)
 def test_existing_scaffold_refuses(self):
  self.make();self.tmp.mkdir();self.assertNotEqual(self.scaffold().returncode,0);self.assertFalse((self.tmp/'payload').exists())
 def test_symlink_scaffold_refuses(self):
  self.make();self.tmp.symlink_to(self.root/'absent');self.assertNotEqual(self.scaffold().returncode,0)
 def test_inventory_faults(self):
  for kind in ['missingempty','extraempty','mutatedfile','symlink','missingfile']:
   with self.subTest(kind=kind):
    if not self.out.exists():self.make()
    if self.tmp.exists():shutil.rmtree(self.tmp)
    self.assertEqual(self.scaffold().returncode,0);self.transfer_file_only()
    if kind=='missingempty':(self.tmp/'payload/lib/pcmanfm').rmdir()
    elif kind=='extraempty':(self.tmp/'extra').mkdir()
    elif kind=='mutatedfile':(self.tmp/'payload/bin/fixture').write_text('changed')
    elif kind=='symlink':(self.tmp/'payload/bin/fixture').unlink();(self.tmp/'payload/bin/fixture').symlink_to(self.src/'payload/bin/fixture')
    else:(self.tmp/'payload/bin/fixture').unlink()
    self.assertNotEqual(self.verify(self.tmp).returncode,0)
 def test_host_hash_and_sourcepin(self):
  (self.src/'payload/bin/fixture').write_text('changed')
  with self.assertRaises(AssertionError):self.make()
  self.pins()
  with self.assertRaises(AssertionError):d.plan(self.src,self.pin,self.out)
 def test_unsupported_path(self):
  (self.src/'payload/evil@x').mkdir();self.pins()
  with self.assertRaises(AssertionError):self.make()
if __name__=='__main__':unittest.main()

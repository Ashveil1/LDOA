import pathlib,tempfile,unittest
from unittest.mock import patch
import prepare_incoming as p
class Prep(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.local=pathlib.Path(self.t.name)/'local';self.local.mkdir();self.stage=self.local/'original/stage'/p.PREFIX;self.stage.mkdir(parents=True);self.out=pathlib.Path(self.t.name)/'out'
  for n in ['bin/pcmanfm','COPYING','DEBIAN-COPYRIGHT','SOURCE.txt','inventory.sha256']:
   f=self.stage/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_text('SYNTHETIC FIXTURE NOT EXECUTABLE')
  (self.stage/'lib/pcmanfm').mkdir(parents=True)
  for n in p.SOURCE_NAMES:(self.local/n).write_text('fixture')
  for n in ['commands-original.txt','explicit-build-environment.txt']:(self.local.parent/n).write_text('fixture')
 def tearDown(self):self.t.cleanup()
 def pins(self,path):return {'orig.tar.xz':'14cb7b247493c4cce65fbb5902611e3ad00a7a870fbc1e50adc50428c5140cf7','debian.tar.xz':'ac2538fe14f44bd56ea77f1bba8c4f16349898ae259e2b602465f4d2faec0723','default-size.patch':'ae69d35d1215efc6e521c7fbe81ceacb261fd0c4917c2828ffb385bded7d6829'}.get(path.name,'f'*64)
 def test_roundtrip(self):
  with patch.object(p,'digest',side_effect=self.pins):self.assertEqual(len(p.prepare(self.local,'original',self.out)),64)
  self.assertTrue((self.out/'source/COPYING').is_file())
 def test_source_pin(self):
  with self.assertRaises(ValueError):p.prepare(self.local,'original',self.out)
  self.assertFalse(self.out.exists())
 def test_existing(self):
  self.out.mkdir()
  with self.assertRaises(ValueError):p.prepare(self.local,'original',self.out)
 def test_symlink(self):
  (self.stage/'extra').symlink_to(self.local/'orig.tar.xz')
  with self.assertRaises(ValueError):p.prepare(self.local,'original',self.out)
 def test_missingdir(self):
  (self.stage/'lib/pcmanfm').rmdir()
  with self.assertRaises(ValueError):p.prepare(self.local,'original',self.out)
 def test_case(self):
  with self.assertRaises(ValueError):p.prepare(self.local,'foreign',self.out)
 def test_budget(self):
  with patch.object(p,'CAP',1):
   with self.assertRaises(ValueError):p.inventory(self.stage)
if __name__=='__main__':unittest.main()

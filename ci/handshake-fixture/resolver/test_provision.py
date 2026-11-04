import unittest,tempfile,pathlib,zipfile,hashlib,io
from provision_gradle import extract_checked,Chain,START,GH,DIRECT
class Provision(unittest.TestCase):
 def check(self,members,ok):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);p=root/'test.zip'
   with zipfile.ZipFile(p,'w') as z:
    for name,data in members:z.writestr(name,data)
   kwargs={'expected_size':p.stat().st_size,'expected_sha':hashlib.sha256(p.read_bytes()).hexdigest()}
   if ok:self.assertTrue(extract_checked(p,root/'out',**kwargs).is_file())
   else:
    with self.assertRaises(ValueError):extract_checked(p,root/'out',**kwargs)
 def test_synthetic_pin_layout(self):self.check([('gradle-8.9/bin/gradle',b'synthetic never executed')],True)
 def test_traversal(self):self.check([('gradle-8.9/../../escape',b'x')],False)
 def test_wrong_layout(self):self.check([('other/bin/gradle',b'x')],False)
 def test_real_pin_rejects_synthetic(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'zip';p.write_bytes(b'x')
   with self.assertRaises(ValueError):extract_checked(p,pathlib.Path(d)/'out')
 def test_redirect_scope(self):
  c=Chain()
  for u in ['https://evil.invalid/x','http://downloads.gradle.org/distributions/gradle-8.9-bin.zip',GH+'?evil=1']:
   with self.assertRaises(ValueError):c.redirect_request(None,None,302,'',{},u)
if __name__=='__main__':unittest.main()

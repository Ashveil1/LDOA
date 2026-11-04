import unittest,tempfile,pathlib,json,io
from unittest.mock import patch
import measure_existing as m
class Measurement(unittest.TestCase):
 def tree(self,root,count):
  (root/'source.properties').write_text('Pkg.Revision = 29.0.14206865\n')
  host=root/'toolchains/llvm/prebuilt/linux-x86_64';inc=host/'lib/clang/21/include';inc.mkdir(parents=True)
  for i in range(count):(inc/f'h{i:04d}.h').write_bytes(b'x')
  return host,inc
 def boundary(self,count):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);host,inc=self.tree(root,count);hashed=[]
   def row(p,r,scope):
    if scope=='NDK_RESOURCE_HEADER':hashed.append(p.name)
    return {'path':str(p),'sha256':'fixture','bytes':1,'scope':scope}
   with patch.object(m,'row',side_effect=row):
    if count==4096:self.assertEqual(m.measure(root)['header_count'],4096)
    else:
     with self.assertRaisesRegex(m.Stop,'RESOURCE_INVENTORY_CAP'):m.measure(root)
   self.assertEqual(len(hashed),4096)
   self.assertNotIn('h4096.h',hashed)
 def test_exact4096_allowed(self):self.boundary(4096)
 def test_4097_not_hashed(self):self.boundary(4097)
 def test_safe_stop_persisted_and_printed_identically(self):
  with tempfile.TemporaryDirectory() as d:
   def fail():raise m.Stop('NDK_RESOURCE_AMBIGUOUS')
   out=io.StringIO();p=pathlib.Path(d)/'receipt.json'
   self.assertEqual(m.cli(p,fail,out),1)
   self.assertEqual(p.read_text(),out.getvalue())
   self.assertEqual(json.loads(out.getvalue())['reason'],'NDK_RESOURCE_AMBIGUOUS')
 def test_generic_failure_no_external_text(self):
  def fail():raise OSError('secret external path text')
  r,code=m.receipt(fail);self.assertEqual(code,1);self.assertEqual(r['reason'],'EXISTING_FILE_MEASUREMENT_FAILED');self.assertNotIn('secret',str(r))
 def test_unknown_stop_sanitized(self):
  def fail():raise m.Stop('unsafe content')
  self.assertEqual(m.receipt(fail)[0]['reason'],'UNCLASSIFIED_SAFE_STOP')
 def test_receipt_write_failure_is_stop(self):
  out=io.StringIO()
  with tempfile.TemporaryDirectory() as d:self.assertEqual(m.cli(pathlib.Path(d)/'missing/r.json',lambda:{'status':'fixture'},out),1)
  self.assertEqual(json.loads(out.getvalue())['reason'],'RECEIPT_WRITE_FAILED')
if __name__=='__main__':unittest.main()

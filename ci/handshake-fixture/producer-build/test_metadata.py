import pathlib,tempfile,unittest,io,json,stat
from unittest.mock import patch
import metadata_existing as m
class Metadata(unittest.TestCase):
 def fixture(self,r):
  h=r/'toolchains/llvm/prebuilt/linux-x86_64';(h/'bin').mkdir(parents=True);(h/'lib/clang/21/include').mkdir(parents=True)
  for p in [r/'source.properties',h/'bin/clang',h/'bin/ld.lld',h/'lib/clang/21/include/x.h']:p.write_bytes(b'fixture')
  return h
 def test_metadata_only_first_mismatch_no_content_read(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);self.fixture(r)
   with patch.object(pathlib.Path,'open',side_effect=AssertionError('content read')):
    result,code=m.metadata(r)
   self.assertEqual(code,1);self.assertEqual(result['status'],'OWNERSHIP_MISMATCH_METADATA_ONLY');self.assertEqual(result['first_mismatch'],'source.properties');self.assertFalse(result['hashes']);self.assertTrue(result['rows'][0]['ancestors'])
 def test_outside_symlink_hides_external_path(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);self.fixture(r);(r/'source.properties').unlink();(r/'source.properties').symlink_to('/etc/passwd')
   result,code=m.metadata(r);self.assertEqual(code,1);self.assertEqual(result['reason'],'PATH_SCOPE');self.assertNotIn('/etc/passwd',str(result))
 def test_deadline(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);self.fixture(r);clock=iter([0,121])
   result,code=m.metadata(r,lambda:next(clock));self.assertEqual(code,1);self.assertEqual(result['reason'],'METADATA_DEADLINE')
 def test_safe_labels_only(self):
  with self.assertRaisesRegex(m.Stop,'PATH_SCOPE'):m.safe_label(pathlib.Path('/fixed/private weird'),pathlib.Path('/fixed'))
 def test_durable_printed_receipt_equal(self):
  with tempfile.TemporaryDirectory() as d:
   out=io.StringIO();p=pathlib.Path(d)/'receipt'
   code=m.cli(p,lambda:({'status':'METADATA_ONLY_NOT_APPROVED'},0),out)
   self.assertEqual(code,0);self.assertEqual(p.read_text(),out.getvalue())
 def test_write_failure(self):
  with tempfile.TemporaryDirectory() as d:
   out=io.StringIO();self.assertEqual(m.cli(pathlib.Path(d)/'missing/r',lambda:({},0),out),1);self.assertEqual(json.loads(out.getvalue())['reason'],'RECEIPT_WRITE_FAILED')
 def test_count_and_size_boundaries(self):
  m.admit_header(4095,64*1024*1024-1,1)
  with self.assertRaisesRegex(m.Stop,'RESOURCE_INVENTORY_CAP'):m.admit_header(4096,0,1)
  with self.assertRaisesRegex(m.Stop,'RESOURCE_BYTE_CAP'):m.admit_header(0,64*1024*1024,1)
 def test_success_metadata_no_qualification(self):
  with tempfile.TemporaryDirectory() as d:
   r=pathlib.Path(d);self.fixture(r);original=pathlib.Path.stat
   def root_owned(p,*a,**kw):
    import os
    old=original(p,*a,**kw);fields=list(old);fields[0]=stat.S_IFMT(old.st_mode)|0o755;fields[4]=0;fields[5]=0
    return os.stat_result(fields)
   with patch.object(pathlib.Path,'stat',root_owned):result,code=m.metadata(r)
   self.assertEqual(code,0);self.assertEqual(result['header_count'],1);self.assertEqual(result['status'],'METADATA_ONLY_NOT_APPROVED')
if __name__=='__main__':unittest.main()

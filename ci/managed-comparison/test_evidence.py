import pathlib,tempfile,unittest,xml.etree.ElementTree as ET
import transport as t
import check_case as c
class Evidence(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.p=pathlib.Path(self.tmp.name);self.e=self.p/'evidence';self.private=self.p/'private';self.private.mkdir();(self.private/'binary').write_bytes(b'NEVER EXPORTED')
  self.out=self.p/'selected';self.dec=self.p/'decoded'
  for case in ('original','patched'):
   d=self.e/case;d.mkdir(parents=True)
   for n in t.CASE_FILES:(d/n).write_text('SYNTHETIC not pixels')
   (d/'managed-junit.xml').write_text('<testsuite tests="1" failures="0" skipped="0"><testcase classname="dev.phonelinux.proto.MobileDesktopEntryTest" name="mobileEntryManagedDesktopComparison"/></testsuite>')
   (d/'managed-comparison.txt').write_text('case='+case+' manifest='+'a'*64+'\n'+''.join('case='+case+' transition='+str(i)+' requested=800x1800 anchored=123/456 fit=true\n' for i in range(5))+'CASE geometry observations complete')
   (d/'managed-provenance.txt').write_text('case='+case+' manifest='+'a'*64+' inventory='+'b'*64+'\n')
   (d/'managed-teardown.txt').write_text('PASS original owned PID/start set absent after Stop')
 def tearDown(self):self.tmp.cleanup()
 def test_roundtrip_excludes_private(self):
  self.assertTrue(t.collect(self.e,self.private,self.out));lines=t.frame(self.out);t.decode(lines,self.dec)
  self.assertFalse((self.dec/'binary').exists());self.assertTrue((self.dec/'patched/managed-0.png').exists())
 def test_overlap(self):
  with self.assertRaises(AssertionError):t.collect(self.private,self.private,self.out)
 def test_missing(self):
  (self.e/'patched/managed-input.png').unlink();self.assertFalse(t.collect(self.e,self.private,self.out))
 def test_skipped(self):
  p=self.e/'original/managed-junit.xml';p.write_text(p.read_text().replace('/>','><skipped/></testcase>'))
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_incomplete(self):
  (self.e/'original/managed-comparison.txt').write_text('failure')
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_teardown(self):
  (self.e/'original/managed-teardown.txt').unlink()
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_decoder_corrupt(self):
  t.collect(self.e,self.private,self.out)
  with self.assertRaises(AssertionError):t.decode(t.frame(self.out)[:-1],self.dec)
 def test_symlink(self):
  p=self.e/'original/managed-abi.txt';p.unlink();p.symlink_to(self.private/'binary')
  with self.assertRaises(AssertionError):t.collect(self.e,self.private,self.out)
 def test_duplicate_frame(self):
  t.collect(self.e,self.private,self.out);lines=t.frame(self.out)
  with self.assertRaises(AssertionError):t.decode(lines[:2]+lines[1:],self.dec)
 def test_duplicate_pixel(self):
  extra=self.e/'original/extra';extra.mkdir();(extra/'managed-0.png').write_text('SYNTHETIC')
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_bound_ok(self):self.assertIn('PASS',c.check(self.e/'original','original','a'*64))
 def test_swapped_case(self):
  with self.assertRaises(ValueError):c.check(self.e/'original','patched','a'*64)
 def test_swapped_pin(self):
  with self.assertRaises(ValueError):c.check(self.e/'original','original','b'*64)
 def test_provenance_wrong(self):
  (self.e/'original/managed-provenance.txt').write_text('case=patched manifest='+'a'*64+' inventory='+'b'*64+'\n')
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_raw_wrong(self):
  p=self.e/'original/managed-comparison.txt';p.write_text(p.read_text().replace('case=original transition=2','case=patched transition=2'))
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_assumption_suite_count(self):
  p=self.e/'original/managed-junit.xml';p.write_text(p.read_text().replace('skipped="0"','skipped="1"'))
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_prior_failure_retained(self):
  p=self.e/'original/managed-junit.xml';p.write_text(p.read_text().replace('/>','><failure message="prior"/></testcase>'))
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_source_route(self):
  p=pathlib.Path(__file__).parent
  w=(p/'workflow-proposal.yml' if (p/'workflow-proposal.yml').exists() else p.parents[1]/'.github/workflows/managed-comparison.yml').read_text()
  self.assertNotIn('uses: actions/upload-artifact',w);self.assertIn('cache-disabled: true',w)
  self.assertEqual(w.count('emulator-port: 5554'),2);self.assertIn('managed-original',w);self.assertIn('managed-patched',w)
if __name__=='__main__':unittest.main()

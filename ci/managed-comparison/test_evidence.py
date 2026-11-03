import struct,json,pathlib,tempfile,unittest,xml.etree.ElementTree as ET
import transport as t
import check_case as c
class Evidence(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.p=pathlib.Path(self.tmp.name);self.e=self.p/'evidence';self.private=self.p/'private';self.private.mkdir();(self.private/'binary').write_bytes(b'NEVER EXPORTED')
  self.out=self.p/'selected';self.dec=self.p/'decoded'
  self.e.mkdir();(self.e/'host-phase.txt').write_text('schema=1\nphase=complete case=both rc=0\n')
  for case in ('original','patched'):
   d=self.e/case;d.mkdir(parents=True)
   for n in t.CASE_FILES:(d/n).write_text('SYNTHETIC not pixels')
   (d/'transfer-guards.txt').write_text('schema=1\n'+''.join('guard='+l+' rc=0\n' for l in ['context-identity','parent-nonlink','parent-type','incoming-absent','incoming-nonlink','tmp-absent','tmp-nonlink','tmp-scaffold','push','tmp-inventory','private-copy']))
   (d/'caller.txt').write_text('caller_exit=0\n');(d/'phase.txt').write_text('instrumentation_caller_rc=0\n')
   (d/'managed-junit.xml').write_text('<testsuite tests="1" failures="0" skipped="0"><testcase classname="dev.phonelinux.proto.MobileDesktopEntryTest" name="mobileEntryManagedDesktopComparison"/></testsuite>')
   (d/'managed-comparison.txt').write_text('case='+case+' manifest='+'a'*64+'\n'+''.join('case='+case+' transition='+str(i)+' requested=800x1800 anchored=123/456 fit='+('false' if case=='original' else 'true')+'\n' for i in range(5))+'CASE geometry observations complete')
   (d/'managed-provenance.txt').write_text('case='+case+' manifest='+'a'*64+' inventory='+'b'*64+'\n')
   (d/'managed-teardown.txt').write_text('PASS original owned PID/start set absent after Stop')
   for i in range(5):
    nums=[800,1800,800,1800,0,0,0,0,800,1800,0,0,800,1800,0,0,800,1800,0,0,800,1800,0,0,800,1800,1,0,0,0,1,0,0,0,1,1,1,1,1,0,1,0,0,0,0,0,0,0,0,0,0,0,1]
    snap=lambda st:{'complete':True,'valid':True,'start_ns':st,'end_ns':st+1,'numbers':nums}
    (d/f'managed-{i}.png').write_bytes(b'\x89PNG\r\n\x1a\n'+struct.pack('>I',13)+b'IHDR'+struct.pack('>II',800,1800)+b'fixture-not-real-image')
    (d/f'managed-{i}-capture-bracket.json').write_text(json.dumps({'source_pin':'29d4460b26e69b39af023d255a255e2778251952','capture_start_ns':2,'capture_end_ns':3,'schema':3,'case':case,'manifest':'a'*64,'transition':i,'desktop_pid':123,'desktop_start':'456','desktop_xid':100,'before':[800,1800,800,1800,0,0],'after':[800,1800,800,1800,0,0],'roi_root':[160,630,480,540],'root_desktop_intersection':[0,0,800,1800],'roi_desktop_intersection':[160,630,480,540],'root_roi_contained':True,'roi_coverage':'FULL','xterm_unmapped':True,'occluders':[],'scope':'x-geometry-and-manual-fullscreen','presentation_binding':'UNVERIFIED','manual_review':'PENDING','legacy_renderQualification':'NOT_EXECUTED_MANUAL_SCOPE','x_occlusion_scope':'central-root-only','bitmap':[800,1800],'pre':snap(1),'post':snap(3),'java_state':'UNCHANGED','query_calls':1,'query_complete':1,'query_zero_cleanup':1}))

   if case=='original':
    for i in range(5):
     f=d/f'managed-{i}-capture-bracket.json';b=json.loads(f.read_text());b['before'][2]=1280;b['after'][2]=1280;f.write_text(json.dumps(b))

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
 def test_bound_ok(self):self.assertIn('MANUAL_REVIEW_REQUIRED',c.check(self.e/'original','original','a'*64))
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
 def test_host_roundtrip(self):
  self.assertTrue(t.collect(self.e,self.private,self.out));t.decode(t.frame(self.out),self.dec)
  self.assertEqual((self.dec/'host-phase.txt').read_bytes(),(self.e/'host-phase.txt').read_bytes())
 def test_host_missing(self):
  (self.e/'host-phase.txt').unlink();self.assertFalse(t.collect(self.e,self.private,self.out))
 def test_host_private_text_refused(self):
  (self.e/'host-phase.txt').write_text('source/binary secret arbitrary log tail')
  with self.assertRaises(AssertionError):t.collect(self.e,self.private,self.out)
 def test_host_oversized_refused(self):
  (self.e/'host-phase.txt').write_text('x'*1025)
  with self.assertRaises(AssertionError):t.collect(self.e,self.private,self.out)
 def test_host_symlink_refused(self):
  f=self.e/'host-phase.txt';f.unlink();f.symlink_to(self.private/'binary')
  with self.assertRaises(AssertionError):t.collect(self.e,self.private,self.out)
 def test_guard_arbitrary_refused(self):
  (self.e/'original/transfer-guards.txt').write_text('private content')
  with self.assertRaises(AssertionError):t.collect(self.e,self.private,self.out)
 def test_guard_oversize_refused(self):
  (self.e/'original/transfer-guards.txt').write_text('x'*1025)
  with self.assertRaises(AssertionError):t.collect(self.e,self.private,self.out)
 def test_guard_case_missing_refuses(self):
  (self.e/'original/transfer-guards.txt').unlink()
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_guard_case_failed_refuses(self):
  f=self.e/'original/transfer-guards.txt';f.write_text(f.read_text().replace('private-copy rc=0','private-copy rc=76'))
  with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_source_route(self):
  p=pathlib.Path(__file__).parent
  w=(p.parents[1]/'.github/workflows/mobile-desktop.yml').read_text()
  self.assertNotIn('uses: actions/upload-artifact',w);self.assertIn('cache-disabled: true',w)
  self.assertEqual(w.count('emulator-port: 5554'),2);self.assertIn('managed-original',w);self.assertIn('managed-patched',w)
if __name__=='__main__':unittest.main()

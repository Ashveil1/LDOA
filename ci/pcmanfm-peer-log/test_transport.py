import base64,json,pathlib,tempfile,unittest
from unittest.mock import patch
import transport as t
class Peer(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.w=pathlib.Path(self.tmp.name)/'workspace';self.w.mkdir()
  self.root=pathlib.Path(self.tmp.name)/'selected';self.out=pathlib.Path(self.tmp.name)/'decoded'
  self.raw=self.w/'app/build/outputs/connected_android_test_additional_output/device/pcmanfm-peer-no-payload.txt';self.raw.parent.mkdir(parents=True);self.raw.write_bytes(b'fixture\nraw bytes\x00')
  self.xml=self.w/'app/build/outputs/androidTest-results/connected/device/TEST.xml';self.xml.parent.mkdir(parents=True);self.xml.write_text('<testsuite/>')
  (self.w/'phase.txt').write_text('fixture phase');(self.w/'peer-selection-result.txt').write_text('fixture selection')
 def tearDown(self):self.tmp.cleanup()
 def frames(self):self.assertTrue(t.collect(self.w,self.root));return t.frame(self.root)
 def test_roundtrip_exact(self):
  lines=self.frames();t.decode(['timestamp prefix '+l for l in lines],self.out)
  self.assertEqual((self.out/'raw.txt').read_bytes(),self.raw.read_bytes());self.assertEqual((self.out/'xml-000.xml').read_bytes(),self.xml.read_bytes())
  self.assertTrue(json.loads((self.out/'receipt.json').read_text())['complete'])
 def test_missing(self):
  self.raw.unlink();self.assertFalse(t.collect(self.w,self.root));t.decode(t.frame(self.root),self.out)
  self.assertFalse(json.loads((self.out/'receipt.json').read_text())['complete'])
 def test_overflow(self):
  self.raw.write_bytes(b'x'*(128*1024+1));self.assertFalse(t.collect(self.w,self.root));self.assertFalse((self.root/'raw.txt').exists())
 def test_symlink(self):
  self.raw.unlink();self.raw.symlink_to(self.w/'phase.txt');self.assertFalse(t.collect(self.w,self.root))
 def test_duplicate_raw(self):
  p=self.raw.parent/'sub';p.mkdir();(p/self.raw.name).write_text('duplicate');self.assertFalse(t.collect(self.w,self.root))
 def test_disallowed_excluded(self):
  (self.w/'password').write_text('never');self.frames();self.assertFalse((self.root/'password').exists())
 def test_truncated(self):
  lines=self.frames()
  for bad in [lines[:-1],lines[:2]+lines[3:],lines+[lines[-1]],lines[:2]+[lines[1]]+lines[2:]]:
   with self.assertRaises((AssertionError,ValueError,IndexError)):t.decode(bad,self.out)
   self.assertFalse(self.out.exists())
 def test_corrupt(self):
  lines=self.frames();i=next(i for i,l in enumerate(lines) if ' DATA ' in l);d=json.loads(lines[i].split(' DATA ',1)[1]);d['data']=base64.b64encode(b'corrupted').decode();lines[i]=t.PREFIX+'DATA '+json.dumps(d)
  with self.assertRaises(AssertionError):t.decode(lines,self.out)
  self.assertFalse(self.out.exists())
 def test_order(self):
  lines=self.frames();ix=[i for i,l in enumerate(lines) if ' DATA ' in l];lines[ix[0]],lines[ix[1]]=lines[ix[1]],lines[ix[0]]
  with self.assertRaises(AssertionError):t.decode(lines,self.out)
  self.assertFalse(self.out.exists())
 def test_emitter_failure_exit_retains_receipt(self):
  import subprocess,sys
  self.raw.unlink()
  p=subprocess.run([sys.executable,str(pathlib.Path(t.__file__)),'emit','--workspace',str(self.w)],capture_output=True,text=True,timeout=5)
  self.assertEqual(p.returncode,1);t.decode(p.stdout.splitlines(),self.out)
  self.assertFalse(json.loads((self.out/'receipt.json').read_text())['complete'])
 def test_caps(self):
  lines=self.frames()
  for name in ['MAX_RAW','MAX_ENCODED','MAX_LINE','MAX_CHUNKS']:
   with patch.object(t,name,1):
    with self.assertRaises(AssertionError):t.frame(self.root)
 def test_names(self):
  for name in ['../raw.txt','/raw.txt','raw.txt/child','password','xml-001.xml/child','./raw.txt']:self.assertFalse(t.allowed(name))
if __name__=='__main__':unittest.main()

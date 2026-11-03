import base64,hashlib,json,pathlib,tempfile,unittest
from unittest.mock import patch
from PIL import Image
import transport as t

class Transport(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name)/'source';self.root.mkdir()
  (self.root/'original').mkdir();(self.root/'original'/'geometry.json').write_text('{"test":"fixture"}')
  # True original-resolution tall and wide PNG fixtures, lossless bytes.
  im=Image.new('RGB',(1080,1949),(17,33,55));im.putpixel((1079,1948),(255,0,7));im.save(self.root/'original'/'00-initial.png')
  (self.root/'archive-signature.txt').write_text('fixture, NOT real signature proof')
  (self.root/'password').write_text('excluded fixture')
  (self.root/'original'/'home').mkdir();(self.root/'original'/'home'/'credentials').write_text('excluded fixture')
  self.lines=t.frame(self.root)
 def tearDown(self):self.tmp.cleanup()
 def output(self):return pathlib.Path(self.tmp.name)/'decoded'
 def test_original_pixels_bytes_roundtrip(self):
  out=self.output();m=t.decode(['2026-11-03 prefix '+l for l in self.lines],out)
  a=self.root/'original'/'00-initial.png';b=out/'original'/'00-initial.png'
  self.assertEqual(a.read_bytes(),b.read_bytes());im=Image.open(b);self.assertEqual(im.size,(1080,1949));self.assertEqual(im.getpixel((1079,1948)),(255,0,7))
  self.assertFalse((out/'password').exists());self.assertFalse((out/'original'/'home').exists())
  self.assertLessEqual(sum(len(l)+1 for l in self.lines),t.MAX_ENCODED)
 def test_duplicate(self):
  with self.assertRaises((AssertionError,ValueError)):t.decode(self.lines[:2]+[self.lines[1]]+self.lines[2:],self.output())
 def test_missing_and_truncated(self):
  for lines in [self.lines[:-1],self.lines[:2]+self.lines[3:]]:
   with self.assertRaises((AssertionError,ValueError,IndexError)):t.decode(lines,self.output())
 def test_out_of_order(self):
  indexes=[i for i,l in enumerate(self.lines) if ' DATA ' in l];a,b=indexes[:2];lines=self.lines[:];lines[a],lines[b]=lines[b],lines[a]
  with self.assertRaises(AssertionError):t.decode(lines,self.output())
 def test_wrong_hash(self):
  lines=self.lines[:];i=next(i for i,l in enumerate(lines) if ' DATA ' in l);d=json.loads(lines[i].split(' DATA ',1)[1]);raw=base64.b64decode(d['data']);d['data']=base64.b64encode(bytes([raw[0]^1])+raw[1:]).decode();lines[i]=t.PREFIX+'DATA '+json.dumps(d,separators=(',',':'))
  with self.assertRaises(AssertionError):t.decode(lines,self.output())
 def test_caps(self):
  for attr in ['MAX_RAW','MAX_ENCODED','MAX_LINE','MAX_CHUNKS']:
   with patch.object(t,attr,1):
    with self.assertRaises(AssertionError):t.frame(self.root)
 def test_reject_trailing_duplicate_frame(self):
  with self.assertRaises(AssertionError):t.decode(self.lines+[self.lines[-1]],self.output())
 def rewrite_manifest(self,mutate):
  lines=self.lines[:];start=json.loads(lines[0][len(t.PREFIX+'START '):]);count=start['manifest_chunks']
  m=b''.join(base64.b64decode(lines[i+1].split(' ',3)[3]) for i in range(count))
  manifest=json.loads(m);mutate(manifest);m=json.dumps(manifest,separators=(',',':')).encode()
  start={'manifest_bytes':len(m),'manifest_sha256':hashlib.sha256(m).hexdigest(),'manifest_chunks':(len(m)+t.CHUNK-1)//t.CHUNK}
  return [t.PREFIX+'START '+json.dumps(start)]+[t.PREFIX+'MANIFEST '+str(i//t.CHUNK)+' '+base64.b64encode(m[i:i+t.CHUNK]).decode() for i in range(0,len(m),t.CHUNK)]+lines[1+count:-1]+[t.PREFIX+'END '+start['manifest_sha256']]
 def test_unsafe_names_no_partial_output(self):
  for bad in ['original//geometry.json','original/./geometry.json','transport-manifest.json','../geometry.json','/absolute','original','original/geometry.json/child','original/home/password']:
   with self.subTest(name=bad):
    lines=self.rewrite_manifest(lambda m:m['files'][0].update(name=bad))
    with self.assertRaises(AssertionError):t.decode(lines,self.output())
    self.assertFalse(self.output().exists())
 def test_duplicate_name_no_partial_output(self):
  lines=self.rewrite_manifest(lambda m:m['files'][1].update(name=m['files'][0]['name']))
  with self.assertRaises(AssertionError):t.decode(lines,self.output())
  self.assertFalse(self.output().exists())
 def test_ancestor_refusal_before_write(self):
  # Allowlist itself excludes parent nodes and noncanonical descendants.
  self.assertFalse(t.allowed('original'));self.assertFalse(t.allowed('original/geometry.json/child'))
  self.assertFalse(t.allowed('transport-manifest.json'))
if __name__=='__main__':unittest.main()

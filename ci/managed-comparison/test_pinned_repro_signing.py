import base64,json,pathlib,tempfile,unittest,os,hashlib
from unittest.mock import patch
import pinned_repro_signing as s
class Signing(unittest.TestCase):
 def fixture(self,mode='ok'):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);root=pathlib.Path(t.name);private=root/'private';private.mkdir();env={'RUNNER_TEMP':str(root),'JAVA_HOME':str(root/'jdk'),'ANDROID_HOME':str(root/'sdk'),'LDOA_DEBUG_KEYSTORE_B64':base64.b64encode(b'SYNTHETIC NOT A REAL KEY').decode()};seen=[];h=hashlib.sha256(b'SYNTHETIC CERT').hexdigest()
  def run(args,child,seconds,cap=65536):
   self.assertNotIn('LDOA_DEBUG_KEYSTORE_B64',child);seen.append(args)
   if 'keytool' in args[0]:
    key=pathlib.Path(args[args.index('-keystore')+1]);self.assertEqual(key.stat().st_mode&511,384);self.assertEqual(key.parent.stat().st_mode&511,448)
    if mode=='keyfail':raise s.Refused('secret tool result')
    pathlib.Path(args[args.index('-file')+1]).write_bytes(b'SYNTHETIC CERT');return b''
   if args[0]=='./gradlew':
    if mode=='buildfail':raise s.Refused('secret tool result')
    if mode=='signal':raise s.Stop()
    self.assertTrue(any(x.startswith('-Pldoa.keystore.path=') for x in args));return b''
   if mode=='certfail':return b'Signer #1 certificate SHA-256 digest: '+b'a'*64+b'\n'
   return ('Signer #1 certificate SHA-256 digest: '+h+'\n').encode()
  return root,private,env,run,seen
 def test_success_permissions_cleanup_no_secret_children(self):
  r,p,e,run,seen=self.fixture()
  with patch.object(s,'run',side_effect=run):result=s.signing(r,p,e)
  self.assertEqual(set(result),{'app','test'});self.assertFalse(list(r.glob('paired-signing-*')));self.assertTrue((p/'signing-receipt.json').is_file());self.assertEqual(len(seen),4)
 def test_failure_paths_cleanup(self):
  for mode in ['keyfail','buildfail','certfail','signal']:
   r,p,e,run,seen=self.fixture(mode)
   with patch.object(s,'run',side_effect=run),self.assertRaises(BaseException):s.signing(r,p,e)
   self.assertFalse(list(r.glob('paired-signing-*')));self.assertFalse((p/'signing-receipt.json').exists())
 def test_missing_bad_oversize_secret_no_fallback(self):
  for value in ['', '!', 'x'*65537]:
   r,p,e,run,seen=self.fixture();e['LDOA_DEBUG_KEYSTORE_B64']=value
   with patch.object(s,'run',side_effect=run),self.assertRaises(Exception):s.signing(r,p,e)
   self.assertFalse(seen);self.assertFalse(list(r.glob('paired-signing-*')))
 def test_wrong_or_multiple_cert(self):
  h='a'*64;valid='Signer #1 certificate SHA-256 digest: '+h+'\n'
  for text in ['',valid.replace(h,'b'*64),valid+valid,valid+valid.replace('#1','#2'),valid+'unparsed certificate SHA-256 digest: extra']:
   with self.assertRaises(Exception):s.fingerprints(text.encode(),h)
 def test_cleanup_failure_refuses(self):
  r,p,e,run,seen=self.fixture()
  with patch.object(s,'run',side_effect=run),patch.object(s.shutil,'rmtree',side_effect=OSError('private path')),self.assertRaises(Exception):s.signing(r,p,e)
if __name__=='__main__':unittest.main()

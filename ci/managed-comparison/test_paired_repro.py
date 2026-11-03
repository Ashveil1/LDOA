import unittest,tempfile,pathlib,copy
import paired_repro as p
class Repro(unittest.TestCase):
 def base(self,role):return {'schema':1,'binding':{'repository':'Ashveil1/LDOA','run_id':'1','run_attempt':'1','sha':'a'*40,'job':'repro_'+role,'role':role},'inputs':{'source':{'x':'a'*64},'toolchain':'b'*64,'workflow':'1'*64,'environment':'2'*64},'outputs':{'payload':{'original':'c'*64,'patched':'d'*64},'apk':{'app':'e'*64,'test':'f'*64}},'manifest_pins':{'original':('1' if role=='first' else '2')*64,'patched':('3' if role=='first' else '4')*64},'label':'build-only'}
 def test_join_and_distinct_manifest_pins(self):self.assertIn('PASS',p.join(self.base('first'),self.base('second'))['result'])
 def test_all_identity_keys(self):
  for k in ['repository','run_id','run_attempt','sha','job','role']:
   s=self.base('second');s['binding'][k]='wrong'
   with self.subTest(k=k),self.assertRaises(ValueError):p.join(self.base('first'),s)
 def test_input_output_drift(self):
  for k in ['source','toolchain','workflow','environment']:
   s=self.base('second');s['inputs'][k]='changed'
   with self.assertRaises(ValueError):p.join(self.base('first'),s)
  for k in ['original','patched']:
   s=self.base('second');s['outputs']['payload'][k]='changed'
   with self.assertRaises(ValueError):p.join(self.base('first'),s)
  for k in ['app','test']:
   s=self.base('second');s['outputs']['apk'][k]='changed'
   with self.assertRaises(ValueError):p.join(self.base('first'),s)
 def test_inventory_directory_hash_and_symlink(self):
  with tempfile.TemporaryDirectory() as t:
   r=pathlib.Path(t)
   for n in ['bin/pcmanfm','COPYING','DEBIAN-COPYRIGHT','SOURCE.txt','inventory.sha256']:
    f=r/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_text('fixed')
   (r/'lib/pcmanfm').mkdir(parents=True);a=p.inventory(r);(r/'bin/pcmanfm').write_text('changed');self.assertNotEqual(a,p.inventory(r));(r/'bin/pcmanfm').unlink();(r/'bin/pcmanfm').symlink_to('/tmp/no')
   with self.assertRaises(ValueError):p.inventory(r)
 def test_binding_requires_exact_job_sha(self):
  env={'GITHUB_REPOSITORY':'Ashveil1/LDOA','GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'a'*40,'GITHUB_JOB':'repro_first'};self.assertEqual(p.binding(env,'first')['job'],'repro_first')
  with self.assertRaises(ValueError):p.binding(env,'second')
 def test_frames_hash_order_and_end(self):
  import hashlib,base64
  raw=p.canonical(self.base('first'));h=hashlib.sha256(raw).hexdigest();lines=['REPRO1 START '+str(len(raw))+' '+h,'REPRO1 DATA 0 '+base64.b64encode(raw).decode(),'REPRO1 END '+h]
  self.assertEqual(p.decode(lines),self.base('first'))
  for bad in [lines[:-1],lines+[lines[-1]],[lines[0],lines[1].replace('DATA 0','DATA 1'),lines[2]],[lines[0],lines[1],lines[2]+'x'],[lines[0],lines[1].replace('DATA 0 ','DATA 0 eA=='),lines[2]]]:
   with self.assertRaises(Exception):p.decode(bad)
 def test_missing_directory_and_caps(self):
  with tempfile.TemporaryDirectory() as t:
   with self.assertRaises(ValueError):p.inventory(pathlib.Path(t))
 def test_replay_and_malformed_receipts(self):
  for key,value in [('run_id','2'),('run_attempt','2'),('sha','b'*40),('job','repro_first'),('role','first')]:
   s=self.base('second');s['binding'][key]=value
   with self.subTest(key=key),self.assertRaises(ValueError):p.join(self.base('first'),s)
  for bad in [None,[],{},dict(self.base('second'),schema=2),dict(self.base('second'),inputs={}),dict(self.base('second'),outputs={}),dict(self.base('second'),extra=1),dict(self.base('second'),manifest_pins={'original':'bad','patched':'b'*64})]:
   with self.assertRaises(Exception):p.join(self.base('first'),bad)
 def test_frames_reject_caps_duplicates_replay_and_noncanonical(self):
  import hashlib,base64,json
  raw=p.canonical(self.base('first'));h=hashlib.sha256(raw).hexdigest();frames=['REPRO1 START '+str(len(raw))+' '+h,'REPRO1 DATA 0 '+base64.b64encode(raw).decode(),'REPRO1 END '+h]
  for bad in [frames+frames,[frames[0],frames[1],frames[1],frames[2]],['REPRO1 START 65537 '+h,frames[1],frames[2]],[frames[0],'REPRO1 DATA 0 !!!',frames[2]],['REPRO1 '+'x'*5000],['REPRO1 DATA 0 eA==']*27]:
   with self.assertRaises(Exception):p.decode(bad)
  raw=json.dumps(self.base('first'),indent=2).encode();h=hashlib.sha256(raw).hexdigest()
  with self.assertRaises(ValueError):p.decode(['REPRO1 START '+str(len(raw))+' '+h,'REPRO1 DATA 0 '+base64.b64encode(raw).decode(),'REPRO1 END '+h])
if __name__=='__main__':unittest.main()

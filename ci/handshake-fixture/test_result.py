import unittest,json,copy,base64,hashlib
from check_result import validate,frame,unframe,assess,CASES
class Tests(unittest.TestCase):
 def sample(self):
  rows=[]
  for c in CASES:
   one=c['case']=='positive';second=c['case']=='second_request'
   rows.append(dict(case=c['case'],expected=c['expected'],result=c['expected'],elapsed_ms=5000,android_uid=2147483647,android_owner=2147483647,guest_uid=2147483647,guest_owner=2147483647,guest_identity_reason='READY_OBSERVED',mode=4095,request_seen=2 if second else 1,published=1 if one or second else 0,accepted=1 if one or second else 0,snapshot_bytes=524288,snapshot_sha256='f'*64,snapshot_reason='COMPLETE',failure_stage='NONE',terminal='NORMAL_END',exit_code=0,capability='AVAILABLE',file_cleanup='REMOVED',token_sha256='f'*64,monitor='INOTIFY',cleanup='KNOWN_OWNED_REAPED'))
  rows[15].update(failure_stage='MONITOR',terminal='MONITOR_UNAVAILABLE',exit_code=73,capability='UNAVAILABLE_MONITOR',guest_uid=None,guest_owner=None,guest_identity_reason='MONITOR_UNAVAILABLE')
  rows[8].update(failure_stage='UNSUPPORTED',cleanup='NOT_LAUNCHED',terminal='NONE',exit_code=None,capability='UNKNOWN',guest_uid=None,guest_owner=None,guest_identity_reason='NOT_LAUNCHED')
  return dict(schema=1,fixture_only=True,scope='HANDSHAKE_FIXTURE_ONLY',pins={k:'f'*64 for k in ['source','native','guest','apk','test_apk']},cases=rows,cleanup='KNOWN_OWNED_REAPED')
 def raw(self,d):return json.dumps(d,separators=(',',':')).encode()
 def test_roundtrip_worst_valid(self):
  b=self.raw(self.sample());self.assertLess(len(b),65536);f=frame(b)+'\n';self.assertLess(len(f),87500);self.assertEqual(unframe(f),self.sample());print('MAX_VALID_FIXED_SCHEMA_BYTES',len(b),'FRAME',len(f))
 def test_bad_values(self):
  for k,v in [('schema',True),('fixture_only',False),('scope','product'),('cleanup','FAILED')]:
   d=self.sample();d[k]=v
   with self.assertRaises(ValueError):validate(self.raw(d))
  for k,v in [('elapsed_ms',60001),('accepted',3),('snapshot_bytes',524290),('case','unknown'),('token_sha256','x'),('monitor','POLL')]:
   d=self.sample();d['cases'][0][k]=v
   with self.assertRaises(ValueError):validate(self.raw(d))
 def test_genuine_mismatch_preserved(self):
  d=self.sample();d['cases'][2]['accepted']=1;d['cases'][0]['android_owner']=0
  recovered=unframe(frame(self.raw(d))+'\n');self.assertEqual(recovered,d);self.assertEqual(assess(recovered)['assessment'],'MISMATCH')
 def test_foreign_owner_constructible(self):
  d=self.sample();d['cases'][8].update(result='REJECTED',failure_stage='NONE',cleanup='KNOWN_OWNED_REAPED',terminal='NORMAL_END',exit_code=0);self.assertEqual(assess(validate(self.raw(d)))['assessment'],'MATCH')
 def test_absent_hash_and_cleanup(self):
  d=self.sample();d['cases'][1].update(snapshot_bytes=0,snapshot_sha256=None,snapshot_reason='NOT_PUBLISHED');self.assertEqual(validate(self.raw(d)),d)
  d['cleanup']='FAILED';self.assertEqual(assess(validate(self.raw(d)))['assessment'],'MISMATCH')
 def test_post_rename_uncertain(self):
  d=self.sample();d['cases'][0].update(result='INCONCLUSIVE',snapshot_reason='POST_RENAME_FSYNC_FAILED');self.assertEqual(assess(validate(self.raw(d)))['assessment'],'MISMATCH')
 def test_unavailable_without_hash(self):
  d=self.sample();d['cases'][15].update(snapshot_bytes=0,snapshot_sha256=None,snapshot_reason='UNAVAILABLE');self.assertEqual(validate(self.raw(d)),d)
 def test_timeout_evidence_preserved(self):
  d=self.sample();d['cases'][0].update(elapsed_ms=5001,failure_stage='CASE_TIMEOUT');self.assertEqual(assess(unframe(frame(self.raw(d))+'\n'))['assessment'],'MISMATCH')
 def test_exact_special_stages(self):
  for index,stage in [(15,'VALIDATION'),(8,'MONITOR')]:
   d=self.sample();d['cases'][index]['failure_stage']=stage;self.assertEqual(assess(validate(self.raw(d)))['assessment'],'MISMATCH')
 def test_constructed_foreign_acceptance_preserved(self):
  d=self.sample();d['cases'][8].update(result='ACCEPTED',failure_stage='NONE',cleanup='KNOWN_OWNED_REAPED',accepted=1);self.assertEqual(assess(unframe(frame(self.raw(d))+'\n'))['assessment'],'MISMATCH')
 def test_monitor_actual_terminal_contract(self):
  d=self.sample();r=assess(validate(self.raw(d)));self.assertEqual(r['assessment'],'MATCH');self.assertIn('monitor_disabled',r['capability_gaps'])
  for key,value in [('exit_code',0),('terminal','NONE'),('cleanup','UNKNOWN'),('file_cleanup','FAILED')]:
   changed=self.sample();changed['cases'][15][key]=value;self.assertEqual(assess(validate(self.raw(changed)))['assessment'],'MISMATCH')
 def test_structure(self):
  for d in [dict(self.sample(),extra='x'),dict(self.sample(),cases=[]),dict(self.sample(),cases=self.sample()['cases']*2)]:
   with self.assertRaises(ValueError):validate(self.raw(d))
  with self.assertRaises(ValueError):validate(b'{"schema":1,"schema":1}')
  with self.assertRaises(ValueError):validate(b' '*65537)
 def test_frame_negative(self):
  f=frame(self.raw(self.sample()))+'\n'
  for v in [f+f,f.rstrip(),f.replace('HSF1','HSF2'),f.replace(' '+hashlib.sha256(self.raw(self.sample())).hexdigest()+' ',' '+'0'*64+' '),f+'x',f.replace(' ', '\x00',1)]:
   with self.assertRaises(ValueError):unframe(v)
 def test_exact_cap_canonical_frame(self):
  raw=self.raw(self.sample());raw+=b' '*(65536-len(raw));f=frame(raw)+'\n';self.assertEqual(len(base64.b64decode(f.split()[3])),65536);self.assertLess(len(f),87500)
if __name__=='__main__':unittest.main()

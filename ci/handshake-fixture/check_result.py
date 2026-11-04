#!/usr/bin/env python3
"""JSON-only fixture result validator/framer. No diagnostic log publication."""
import base64,hashlib,json,pathlib,re,sys
MAX=65536
CASES=json.loads(pathlib.Path(__file__).with_name('cases.json').read_text())
HEX=re.compile(r'[0-9a-f]{64}\Z')
def pairs(items):
 d={}
 for k,v in items:
  if k in d:raise ValueError('duplicate key')
  d[k]=v
 return d
def keys(d,wanted):
 if type(d)!=dict or set(d)!=set(wanted):raise ValueError('schema keys')
def integer(v,lo,hi):
 if type(v)!=int or not lo<=v<=hi:raise ValueError('integer range')
def digest(v):
 if type(v)!=str or not HEX.fullmatch(v):raise ValueError('digest')
def validate(raw):
 if len(raw)>MAX:raise ValueError('raw cap')
 d=json.loads(raw.decode('utf-8'),object_pairs_hook=pairs,parse_constant=lambda x:(_ for _ in ()).throw(ValueError('constant')))
 keys(d,['schema','fixture_only','scope','pins','cases','cleanup'])
 if type(d['schema'])!=int or d['schema']!=1 or d['fixture_only'] is not True or d['scope']!='HANDSHAKE_FIXTURE_ONLY':raise ValueError('scope')
 keys(d['pins'],['source','native','guest','apk','test_apk'])
 for v in d['pins'].values():digest(v)
 if d['cleanup'] not in ['KNOWN_OWNED_REAPED','NOT_LAUNCHED','FAILED','UNKNOWN']:raise ValueError('cleanup enum')
 if type(d['cases'])!=list or len(d['cases'])!=len(CASES):raise ValueError('case inventory')
 for row,spec in zip(d['cases'],CASES):
  keys(row,['case','expected','result','elapsed_ms','android_uid','android_owner','guest_uid','guest_owner','guest_identity_reason','mode','request_seen','published','accepted','snapshot_bytes','snapshot_sha256','snapshot_reason','failure_stage','terminal','exit_code','capability','file_cleanup','token_sha256','monitor','cleanup'])
  if row['case']!=spec['case'] or row['expected']!=spec['expected'] or row['result'] not in ['ACCEPTED','REJECTED','UNAVAILABLE','INCONCLUSIVE','UNSUPPORTED']:raise ValueError('case verdict')
  for k in ['android_uid','android_owner']:integer(row[k],0,2147483647)
  for k in ['guest_uid','guest_owner']:
   if row[k] is not None:integer(row[k],0,2147483647)
  if row['guest_identity_reason'] not in ['READY_OBSERVED','NOT_LAUNCHED','MONITOR_UNAVAILABLE','READY_UNAVAILABLE']:raise ValueError('guest identity reason')
  observed=row['guest_identity_reason']=='READY_OBSERVED'
  if observed != (row['guest_uid'] is not None and row['guest_owner'] is not None) or ((row['guest_uid'] is None)!=(row['guest_owner'] is None)):raise ValueError('guest identity presence')
  # Owner mismatch is genuine test evidence, not transport corruption.
  integer(row['elapsed_ms'],0,60000);integer(row['mode'],0,4095)
  for k in ['request_seen','published','accepted']:integer(row[k],0,2)
  integer(row['snapshot_bytes'],0,524289)
  if row['snapshot_sha256'] is not None:digest(row['snapshot_sha256'])
  if row['snapshot_reason'] not in ['COMPLETE','NOT_PUBLISHED','REJECTED','UNAVAILABLE','PARTIAL','CAP_EXCEEDED','POST_RENAME_FSYNC_FAILED']:raise ValueError('snapshot reason')
  if row['snapshot_sha256'] is None and row['snapshot_reason']=='COMPLETE':raise ValueError('absent hash reason')
  if row['failure_stage'] not in ['NONE','MONITOR','CASE_TIMEOUT','VALIDATION','UNSUPPORTED','TRANSPORT','RUNAS','CLEANUP']:raise ValueError('failure stage')
  if row['terminal'] not in ['NONE','NORMAL_END','MONITOR_UNAVAILABLE']:raise ValueError('terminal')
  if row['exit_code'] is not None:integer(row['exit_code'],0,255)
  if row['capability'] not in ['AVAILABLE','UNAVAILABLE_MONITOR','UNKNOWN'] or row['file_cleanup'] not in ['REMOVED','FAILED','NOT_ATTEMPTED']:raise ValueError('capability/file cleanup')
  digest(row['token_sha256'])
  if row['monitor'] not in ['INOTIFY','DISABLED','UNAVAILABLE'] or row['cleanup'] not in ['KNOWN_OWNED_REAPED','NOT_LAUNCHED','FAILED','UNKNOWN']:raise ValueError('monitor/cleanup')
  # Do not discard a genuine negative acceptance or owner mismatch.
  if row['snapshot_reason']=='COMPLETE' and row['snapshot_sha256'] is None:raise ValueError('missing complete hash')
 return d

def assess(d):
 results=[]
 for row,spec in zip(d['cases'],CASES):
  acceptable={'UNSUPPORTED','REJECTED'} if row['case']=='foreign_owner_if_constructible' else {spec['expected']}
  ok=row['result'] in acceptable and row['cleanup']=='KNOWN_OWNED_REAPED' and row['elapsed_ms']<=5000
  if row['case']=='monitor_disabled':ok=ok and row['failure_stage']=='MONITOR' and row['terminal']=='MONITOR_UNAVAILABLE' and row['exit_code']==73 and row['capability']=='UNAVAILABLE_MONITOR'
  elif row['case']=='foreign_owner_if_constructible' and row['result']=='UNSUPPORTED':
   ok=row['cleanup']=='NOT_LAUNCHED' and row['elapsed_ms']<=5000 and row['failure_stage']=='UNSUPPORTED' and row['terminal']=='NONE' and row['exit_code'] is None
  else:ok=ok and row['failure_stage']=='NONE'
  ok=ok and row['file_cleanup']=='REMOVED'
  if row['cleanup']=='KNOWN_OWNED_REAPED' and not ((row['terminal']=='NORMAL_END' and row['exit_code']==0) or (row['terminal']=='MONITOR_UNAVAILABLE' and row['exit_code']==73)):ok=False
  if row['snapshot_reason']=='POST_RENAME_FSYNC_FAILED':ok=ok and row['result']=='INCONCLUSIVE'
  if row['case']=='positive':ok=ok and row['android_uid']==row['android_owner'] and [row[k] for k in ['request_seen','published','accepted']]==[1,1,1] and row['snapshot_bytes']>0 and row['monitor']=='INOTIFY'
  elif row['case']=='second_request':ok=ok and [row[k] for k in ['request_seen','published','accepted']]==[2,1,1]
  else:ok=ok and row['accepted']==0
  results.append({'case':row['case'],'assessment':'MATCH' if ok else 'MISMATCH'})
 return {'capability_gaps':[r['case'] for r in d['cases'] if r['capability']!='AVAILABLE'],'assessment':'MATCH' if d['cleanup']=='KNOWN_OWNED_REAPED' and all(r['assessment']=='MATCH' for r in results) else 'MISMATCH','cases':results}

def frame(raw):
 validate(raw)
 encoded=base64.b64encode(raw).decode()
 return 'HSF1 %d %s %s'%(len(raw),hashlib.sha256(raw).hexdigest(),encoded)
def unframe(text):
 if len(text.encode())>87500 or not text.endswith('\n') or text.count('\n')!=1:raise ValueError('frame cap/count')
 parts=text[:-1].split(' ')
 if len(parts)!=4 or parts[0]!='HSF1' or not re.fullmatch('[0-9]+',parts[1]):raise ValueError('frame syntax')
 integer(int(parts[1]),1,MAX);digest(parts[2]);raw=base64.b64decode(parts[3],validate=True)
 if len(raw)!=int(parts[1]) or hashlib.sha256(raw).hexdigest()!=parts[2] or base64.b64encode(raw).decode()!=parts[3]:raise ValueError('frame integrity')
 return validate(raw)
if __name__=='__main__':
 try:
  raw=pathlib.Path(sys.argv[2]).read_bytes()
  if sys.argv[1]=='frame':print(frame(raw))
  elif sys.argv[1]=='decode':print(json.dumps(unframe(raw.decode()),sort_keys=True))
  else:raise ValueError('mode')
 except Exception as e:print('FIXTURE_VALIDATION_FAILED',file=sys.stderr);sys.exit(2)

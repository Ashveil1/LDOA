#!/usr/bin/env python3
"""Read-only fresh-isolated-runner exit guard. Never signals an emulator.
PID/start identity plus broad emulator-family, ADB, and listener absence.
Not atomic: pre/post scans and two passes narrow races, not universal proof.
"""
import argparse,json,os,pathlib,re,selectors,subprocess,time,contextlib,errno
PORTS={5554,5555};MAX_PROCS=8192;MAX_RAW=16384;BUDGET=40.0
class Refused(Exception):pass
class Diagnostic(Refused):
 def __init__(self,stage,operation,pid,exc):
  self.fields={'stage':stage,'operation':operation,'pid':pid,'errno':exc.errno if isinstance(exc,OSError) else 0,
   'kind':('disappearance' if isinstance(exc,FileNotFoundError) else 'permission' if isinstance(exc,PermissionError) else 'parse' if isinstance(exc,(UnicodeError,ValueError,KeyError,IndexError,TypeError,json.JSONDecodeError)) else 'timeout' if isinstance(exc,subprocess.TimeoutExpired) else 'refusal' if isinstance(exc,Refused) else 'io' if isinstance(exc,OSError) else 'unexpected')}
  # Fixed refusals originate in this module, never an exception's data/path.
  if isinstance(exc,Refused) and not isinstance(exc,Diagnostic):
   self.fields['code']=str(exc)
   if str(exc)=='identity-race':self.fields['kind']='race'
  super().__init__('sanitized diagnostic')
@contextlib.contextmanager
def operation(stage,op,pid=0):
 try:yield
 except Diagnostic:raise
 except Exception as e:raise Diagnostic(stage,op,pid,e) from None

def call(stage,op,pid,fn):
 with operation(stage,op,pid):return fn()


def read(p,cap):
 with p.open('rb') as f:b=f.read(cap+1)
 if len(b)>cap:raise Refused('bounded file overflow')
 return b

def stat(p):
 b=read(p/'stat',4096).decode('ascii');tail=b[b.rfind(')')+2:].split()
 if len(tail)<20:raise Refused('bad process stat')
 return {'pid':int(p.name),'start':int(tail[19]),'state':tail[0]}

def family(name):return name=='emulator' or name.startswith(('qemu-system-','emulator64-'))

def scan(proc,uid,deadline):
 rows=[];names=call('process','enumerate',0,lambda:list(proc.iterdir()));pids=[p for p in names if p.name.isdecimal()]
 if len(pids)>MAX_PROCS:raise Refused('process scan cap')
 for p in pids:
  if time.monotonic()>=deadline:raise Refused('process scan deadline')
  try:
   if call('process','owner',int(p.name),lambda:p.stat().st_uid)!=uid:continue
   before=call('process','stat-before',int(p.name),lambda:stat(p));comm=call('process','comm',int(p.name),lambda:read(p/'comm',256).decode('ascii').strip())
   try:exe=call('process','exe',int(p.name),lambda:os.readlink(p/'exe'))
   except Diagnostic as e:
    if e.fields['kind']=='disappearance' and before['state']=='Z':exe=''
    else:raise
   isfamily=family(pathlib.Path(exe).name) or family(comm)
   if not isfamily:continue
   args=call('family','cmdline',int(p.name),lambda:read(p/'cmdline',8192).decode('utf-8','strict').rstrip('\0').split('\0'))
   after=call('family','stat-after',int(p.name),lambda:stat(p))
   if (before['pid'],before['start'])!=(after['pid'],after['start']):raise Diagnostic('family','identity',int(p.name),Refused('identity-race'))
   rows.append({**before,'exe':exe,'uid':uid,'args':args})
   if len(rows)>8:raise Refused('emulator family count cap')
  except Diagnostic as e:
   if e.fields['kind']=='disappearance':continue # Existing disappearing-object behavior, not permission skip.
   raise
 return rows

def ports(proc):
 rows=[]
 for name in ['tcp','tcp6']:
  lines=call('ports',name+'-read',0,lambda:read(proc/'net'/name,1048576).decode('ascii').splitlines())
  if not lines or 'local_address' not in lines[0]:raise Diagnostic('ports',name+'-parse',0,Refused('bad port table header'))
  for line in lines[1:]:
   fields=line.split()
   if len(fields)<10:raise Diagnostic('ports',name+'-parse',0,Refused('bad port table row'))
   try:port=int(fields[1].rsplit(':',1)[1],16);state=int(fields[3],16)
   except (ValueError,IndexError) as e:raise Diagnostic('ports',name+'-parse',0,e) from None
   if port in PORTS and state==10:rows.append({'table':name,'port':port,'state':'LISTEN','inode':fields[9]})
 return rows

def adb_snapshot(adb,deadline):
 # Never unbounded communicate(). Kill only OUR diagnostic adb subprocess on
 # deadline/overflow, never emu kill, adb kill-server, or emulator signals.
 p=call('adb','spawn',0,lambda:subprocess.Popen([adb,'devices'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT))
 sel=selectors.DefaultSelector();call('adb','register',0,lambda:sel.register(p.stdout,selectors.EVENT_READ));out=bytearray()
 end=min(deadline,time.monotonic()+2.0)
 try:
  eof=False
  while not eof:
   left=end-time.monotonic()
   if left<=0:raise Refused('ADB query timeout')
   for key,_ in call('adb','select',0,lambda:sel.select(min(left,0.1))):
    b=call('adb','read',0,lambda:os.read(key.fd,4096))
    if not b:eof=True;break
    out.extend(b)
    if len(out)>MAX_RAW:raise Refused('ADB query overflow')
  left=end-time.monotonic()
  if left<=0:raise Refused('ADB reap deadline')
  if call('adb','reap',0,lambda:p.wait(timeout=left))!=0:raise Refused('ADB query nonzero')
 finally:
  call('adb','close',0,lambda:(sel.close(),p.stdout.close()))
  if p.poll() is None:
   call('adb','diagnostic-child-kill',0,p.kill);call('adb','diagnostic-child-reap',0,lambda:p.wait(timeout=1))
 text=call('adb','decode',0,lambda:out.decode('ascii','strict'));lines=text.splitlines()
 if not lines or lines[0]!='List of devices attached':raise Refused('ADB malformed header/diagnostic')
 devices=[]
 for line in lines[1:]:
  if not line.strip():continue
  m=re.fullmatch(r'([A-Za-z0-9_.:-]{1,128})\s+(device|offline|unauthorized|recovery|sideload|bootloader|no permissions)(?:\s+.*)?',line)
  if not m:raise Refused('ADB malformed row')
  devices.append({'serial':m[1],'state':m[2]})
  if len(devices)>32:raise Refused('device count cap')
 return devices

def sanitized(rows):return [{k:v for k,v in r.items() if k!='args'} for r in rows]
def identity(r):return r['pid'],r['start'],r['uid'],r['exe']

def anchor(proc,uid,sdk,deadline):
 rows=scan(proc,uid,deadline)
 if len(rows)!=1:raise Refused('expected exactly one emulator-family process')
 r=rows[0];args=r['args']
 def option(n,v):return any(args[i]==n and args[i+1]==v for i in range(len(args)-1))
 if not option('-avd','managed-original') or not option('-port','5554'):raise Refused('original AVD/port not anchored')
 expected=call('anchor','sdk-canonical',r['pid'],lambda:str((sdk/'emulator').resolve())+'/')
 if not r['exe'].startswith(expected) or r['state']=='Z':raise Refused('unexpected original executable/state')
 return {'schema':1,'avd':'managed-original','port':5554,'process':sanitized([r])[0]}

def observation(proc,uid,adb,old,deadline):
 a=call('observation','process-before',0,lambda:scan(proc,uid,deadline));pa=call('observation','ports-before',0,lambda:ports(proc));dev=call('adb','snapshot',0,lambda:adb_snapshot(adb,deadline));pb=call('observation','ports-after',0,lambda:ports(proc));b=call('observation','process-after',0,lambda:scan(proc,uid,deadline))
 # Anchor must not still exist, even if executable changed and scanner no
 # longer calls it emulator-family. Same PID with DIFFERENT start is reused,
 # not the old process, but any emulator-family replacement still refuses.
 oldp=proc/str(old['pid']);old_present=False
 try:old_present=call('anchor','old-stat',old['pid'],lambda:stat(oldp))['start']==old['start']
 except Diagnostic as e:
  if e.fields['kind']!='disappearance':raise
 return {'before':sanitized(a),'ports_before':pa,'devices':dev,'ports_after':pb,'after':sanitized(b),'anchor_present':old_present,
         'absent':not(a or b or pa or pb or old_present or any(d['serial'].startswith('emulator-') for d in dev))}

def wait(proc,uid,adb,old,deadline,emit,interval=1.0):
 consecutive=0
 while time.monotonic()<deadline:
  obs=observation(proc,uid,adb,old,deadline);emit(obs)
  consecutive=consecutive+1 if obs['absent'] else 0
  if consecutive==2:return
  left=deadline-time.monotonic()
  if left<=interval:break
  time.sleep(interval)
 raise Refused('bounded absence not established')

def sdk_assert(sdk):
 text=call('sdk','properties-read',0,lambda:read(sdk/'emulator/source.properties',4096).decode('ascii','strict'))
 rows=re.findall(r'^(Pkg\.(?:Revision|BuildId))=(.*)$',text,re.M)
 if len(rows)!=2 or dict(rows)!={'Pkg.Revision':'37.2.13','Pkg.BuildId':'16740729'}:
  raise Refused('SDK drift/missing/duplicate version properties')
 return dict(rows)

def main():
 ap=argparse.ArgumentParser();ap.add_argument('mode',choices=['anchor','wait']);ap.add_argument('--anchor',required=True);a=ap.parse_args()
 start=time.monotonic();deadline=start+(5.0 if a.mode=='anchor' else BUDGET)
 def emit(x):print(json.dumps({'elapsed_ms':int((time.monotonic()-start)*1000),**x},sort_keys=True),flush=True)
 try:
  proc=pathlib.Path('/proc');uid=os.getuid();sdk=call('config','sdk-environment',0,lambda:pathlib.Path(os.environ['ANDROID_HOME']));path=pathlib.Path(a.anchor)
  if a.mode=='anchor':
   emit({'event':'SDK','properties':call('sdk','properties-validate',0,lambda:sdk_assert(sdk))})
   obj=call('anchor','bind',0,lambda:anchor(proc,uid,sdk,deadline))
   with operation('anchor','write-exclusive'):
    with path.open('x') as f:json.dump(obj,f,sort_keys=True)
   emit({'event':'anchor','anchor':obj})
  else:
   with operation('anchor','read-parse'):
    obj=json.loads(read(path,4096));old=obj['process']
   if obj['schema']!=1 or obj['avd']!='managed-original' or obj['port']!=5554 or old['uid']!=uid:raise Refused('invalid anchor')
   wait(proc,uid,str(sdk/'platform-tools/adb'),old,deadline,lambda x:emit({'event':'observation',**x}))
   emit({'event':'PASS','meaning':'two bracketed all-signals-absent observations; fresh isolated runner only'})
 except Exception as e:
  diagnostic=e if isinstance(e,Diagnostic) else Diagnostic('control','validate',0,e)
  emit({'event':'REFUSED',**diagnostic.fields});return 1
 return 0
if __name__=='__main__':raise SystemExit(main())

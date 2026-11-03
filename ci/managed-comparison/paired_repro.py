#!/usr/bin/env python3
"""Finite build-only equality receipts. No source/binary bytes leave runner.
Strict equality, never strips timestamps, resigns APKs, normalizes manifests,
changes recipe or repairs builds. JSON logs are hash/provenance only.
"""
import argparse,hashlib,json,os,pathlib,re,subprocess,base64
MAX_ENTRIES=1024;MAX_FILE=32*1024*1024;MAX_TOTAL=128*1024*1024
APK_CAP=256*1024*1024 # proposed resource budget, not measured APK size
PREFIX='opt/ldoa/pcmanfm/1.3.2-4build2-floor1'
SOURCE={'orig.tar.xz':'14cb7b247493c4cce65fbb5902611e3ad00a7a870fbc1e50adc50428c5140cf7','debian.tar.xz':'ac2538fe14f44bd56ea77f1bba8c4f16349898ae259e2b602465f4d2faec0723','default-size.patch':'ae69d35d1215efc6e521c7fbe81ceacb261fd0c4917c2828ffb385bded7d6829'}
RECIPES=['build-and-test.sh','prepare_incoming.py','directory_plan.py','incoming-transfer.sh','run-case.sh','transport.py','paired_repro.py','paired_repro_build.sh','pinned_repro_signing.py']
PACKAGES=['gcc','gcc-13','binutils','make','pkg-config','libc6-dev','libfm-gtk3-dev','libgtk-3-dev','intltool','bison','libglib2.0-dev','libpango1.0-dev','libcairo2-dev','libgdk-pixbuf-2.0-dev','libatk1.0-dev','zlib1g-dev']
def canonical(x):return json.dumps(x,sort_keys=True,separators=(',',':')).encode('ascii')
def digest(x):return hashlib.sha256(canonical(x)).hexdigest()
def filehash(p,cap=MAX_FILE):
 if p.is_symlink() or not p.is_file():raise ValueError('file type refused')
 size=p.stat().st_size
 if size>cap:raise ValueError('file size refused bytes='+str(size)+' cap='+str(cap))
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(65536),b''):h.update(b)
 return h.hexdigest()
def inventory(root):
 files={};dirs=[];total=0
 if root.is_symlink() or not root.is_dir():raise ValueError('stage root')
 for p in root.rglob('*'):
  n=p.relative_to(root).as_posix()
  if len(n)>512 or not n or any(c in n for c in '\n\r\0') or any(v in ('','..','.') for v in n.split('/')):raise ValueError('inventory path')
  if p.is_symlink():raise ValueError('inventory symlink')
  if p.is_dir():dirs.append(n)
  elif p.is_file():files[n]=filehash(p);total+=p.stat().st_size
  else:raise ValueError('inventory special')
  if len(files)+len(dirs)>MAX_ENTRIES or total>MAX_TOTAL:raise ValueError('inventory cap')
 if not {'bin/pcmanfm','COPYING','DEBIAN-COPYRIGHT','SOURCE.txt','inventory.sha256'}<=files.keys() or 'lib/pcmanfm' not in dirs:raise ValueError('stage required entries')
 return {'files':dict(sorted(files.items())),'directories':sorted(dirs),'bytes':total}
def command(args,limit=65536):
 # Fixed commands only. File-backed capture avoids unlimited in-memory read;
 # timeout bounds execution; finite package list controls output cardinality.
 import tempfile
 with tempfile.TemporaryFile() as f:
  subprocess.run(args,stdout=f,stderr=f,timeout=10,check=True)
  f.seek(0);b=f.read(limit+1)
 if len(b)>limit:raise ValueError('command receipt cap')
 return b.decode('utf-8','strict')
def package_receipt():
 text=command(['dpkg-query','-W','-f=${Package}\t${Architecture}\t${Version}\t${Status}\n',*PACKAGES]);rows={}
 for line in text.splitlines():
  fields=line.split('\t')
  if len(fields)!=4 or fields[3]!='install ok installed' or fields[0] in rows:raise ValueError('package metadata')
  rows[fields[0]]=fields[1:3]
 if set(rows)!=set(PACKAGES):raise ValueError('missing build package')
 return dict(sorted(rows.items()))
def binding(env,role):
 if role not in ('first','second'):raise ValueError('role')
 def take(k,pattern):
  v=env.get(k,'')
  if not re.fullmatch(pattern,v):raise ValueError('missing/invalid binding')
  return v
 return {'repository':take('GITHUB_REPOSITORY','Ashveil1/LDOA'),'run_id':take('GITHUB_RUN_ID',r'[1-9][0-9]{0,19}'),'run_attempt':take('GITHUB_RUN_ATTEMPT',r'[1-9][0-9]{0,9}'),'sha':take('GITHUB_SHA',r'[0-9a-f]{40}'),'job':take('GITHUB_JOB','repro_first' if role=='first' else 'repro_second'),'role':role}
def build(root,private,role,env):
 b=binding(env,role);local=pathlib.Path((private/'runner-local-location.txt').read_text().strip())
 # Location is trusted private recipe result, not a cross-job path identity.
 for n,h in SOURCE.items():
  if filehash(local/n)!=h:raise ValueError('source pin')
 if command(['git','rev-parse','HEAD']).strip()!=b['sha']:raise ValueError('checkout SHA')
 recipes={n:filehash(root/'ci/managed-comparison'/n) for n in RECIPES}
 recipes['workflow']=filehash(root/'.github/workflows/mobile-desktop.yml')
 payload={case:inventory(local/case/'stage'/PREFIX) for case in ('original','patched')}
 # Keep per-device incoming manifest pins exact, NOT equality keys: source
 # command DESTDIR is runner-local and may differ. No manifest normalization.
 manifests={case:filehash(private/('incoming-'+case)/'manifest.json') for case in ('original','patched')}
 signing=json.loads((private/'signing-receipt.json').read_text())
 if set(signing)!={'app','test'} or any(v!={'verified':True,'certificate_sha256':signing['app']['certificate_sha256']} for v in signing.values()) or not re.fullmatch('[0-9a-f]{64}',signing['app']['certificate_sha256']):raise ValueError('signing receipt')
 outputs={'signing':signing,'payload':payload,'apk':{n:{'sha256':filehash(root/p,APK_CAP),'bytes':(root/p).stat().st_size} for n,p in {'app':'app/build/outputs/apk/debug/app-debug.apk','test':'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}.items()}}
 inputs={'source':SOURCE,'recipes':recipes,'submodules_sha256':hashlib.sha256(command(['git','submodule','status','--recursive']).encode()).hexdigest(),'build_packages':package_receipt(),'toolchain_sha256':{n:hashlib.sha256(command(c).encode()).hexdigest() for n,c in {'gcc':['gcc','--version'],'ld':['ld','--version'],'make':['make','--version'],'pkg-config':['pkg-config','--version'],'java':['java','-version']}.items()},'image':{n:env.get(n,'') for n in ['ImageOS','ImageVersion']},'archive':{n:filehash(private/n) for n in ['InRelease','source-record.txt']},'build_environment_sha256':filehash(private/'explicit-build-environment.txt'),'environment':{'CC':'gcc','CFLAGS':'-O2 -g0','CPPFLAGS':'','LDFLAGS':'','LC_ALL':'C','SOURCE_DATE_EPOCH':'1714082400','prefix':'/'+PREFIX}}
 if any(not re.fullmatch(r'[A-Za-z0-9._-]{1,128}',v) for v in inputs['image'].values()):raise ValueError('missing image pin')
 receipts={'schema':1,'binding':b,'inputs':inputs,'outputs':outputs,'manifest_pins':manifests,'label':'build-only; no target execution; exact APK bytes and both canonical payload inventories'}
 if len(canonical(receipts))>65536:raise ValueError('receipt cap')
 return receipts
KEYS=['inputs','outputs']
def decode(lines):
 frames=[];total=0
 for line in lines:
  if 'REPRO1 ' not in line:continue
  line=line[line.index('REPRO1 '):].rstrip('\r\n');total+=len(line)
  if len(line)>4608 or total>100000 or len(frames)>25:raise ValueError('frame cap')
  frames.append(line)
 if len(frames)<3:raise ValueError('missing receipt')
 m=re.fullmatch(r'REPRO1 START ([0-9]{1,5}) ([0-9a-f]{64})',frames[0])
 if not m or not(0<int(m[1])<=65536):raise ValueError('receipt start')
 raw=b''
 for i,line in enumerate(frames[1:-1]):
  d=re.fullmatch(r'REPRO1 DATA ([0-9]{1,2}) ([A-Za-z0-9+/=]+)',line)
  if not d or int(d[1])!=i:raise ValueError('receipt order')
  b=base64.b64decode(d[2],validate=True)
  if not(0<len(b)<=3072):raise ValueError('chunk cap')
  raw+=b
 if len(raw)!=int(m[1]) or hashlib.sha256(raw).hexdigest()!=m[2] or frames[-1]!='REPRO1 END '+m[2]:raise ValueError('receipt hash/length/end')
 obj=json.loads(raw)
 if canonical(obj)!=raw:raise ValueError('noncanonical receipt')
 return obj

def validate_receipt(x,role):
 if not isinstance(x,dict) or set(x)!={'schema','binding','inputs','outputs','manifest_pins','label'} or x['schema']!=1:raise ValueError('receipt schema')
 b=x['binding']
 if not isinstance(b,dict) or set(b)!={'repository','run_id','run_attempt','sha','job','role'}:raise ValueError('binding schema')
 env={'GITHUB_'+k.upper():str(v) for k,v in b.items() if k not in ['role']}
 if binding(env,role)!=b:raise ValueError('binding malformed')
 if not isinstance(x['inputs'],dict) or not isinstance(x['outputs'],dict) or not x['inputs'] or not x['outputs']:raise ValueError('empty equality keys')
 if set(x['manifest_pins'])!={'original','patched'} or any(not re.fullmatch('[0-9a-f]{64}',v) for v in x['manifest_pins'].values()):raise ValueError('manifest pin malformed')
 if len(canonical(x))>65536:raise ValueError('receipt cap')
 return x

def join(first,second):
 validate_receipt(first,'first');validate_receipt(second,'second')
 for key in ['repository','run_id','run_attempt','sha']:
  if first['binding'][key]!=second['binding'][key]:raise ValueError('binding disagreement')
 for key in KEYS:
  if canonical(first[key])!=canonical(second[key]):raise ValueError('paired '+key+' disagreement')
 return {'schema':1,'result':'PASS build reproducibility only','binding':{k:first['binding'][k] for k in ['repository','run_id','run_attempt','sha']},'first_job':'repro_first','second_job':'repro_second','inputs':digest(first['inputs']),'outputs':digest(first['outputs']),'device_manifest_equality':'NOT asserted, each exact pin retained'}

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--role',required=True,choices=['first','second']);ap.add_argument('--private',type=pathlib.Path,required=True);a=ap.parse_args();r=build(pathlib.Path.cwd(),a.private,a.role,os.environ)
 raw=canonical(r);sha=hashlib.sha256(raw).hexdigest()
 print('REPRO1 START '+str(len(raw))+' '+sha,flush=True)
 for i in range(0,len(raw),3072):print('REPRO1 DATA '+str(i//3072)+' '+base64.b64encode(raw[i:i+3072]).decode(),flush=True)
 print('REPRO1 END '+sha,flush=True)
 # Only SHA256 digest outputs plus small fixed identity metadata. No payload.
 scalars={'inputs':digest(r['inputs']),'outputs':digest(r['outputs']),**r['binding']}
 if a.role=='second':
  for k in ['inputs','outputs','repository','run_id','run_attempt','sha']:
   if os.environ.get('FIRST_'+k.upper())!=str(scalars[k]):raise ValueError('first-job scalar mismatch '+k)
  if os.environ.get('FIRST_JOB')!='repro_first' or os.environ.get('FIRST_ROLE')!='first':raise ValueError('first-job identity')
  print('REPRO_JOIN1 PASS exact digest agreement; full receipt join still REQUIRED',flush=True)
 with open(os.environ['GITHUB_OUTPUT'],'a') as f:
  for k,v in scalars.items():f.write(k+'='+str(v)+'\n')
if __name__=='__main__':main()

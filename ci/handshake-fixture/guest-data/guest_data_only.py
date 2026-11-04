"""Proposed finite data-only Deb reader. No installation, compiler or guest execution."""
import argparse,hashlib,json,pathlib,tarfile,subprocess,urllib.request,time,os,select
TOTAL=13806598;UNPACK_CAP=256*1024*1024;MEMBER_CAP=64*1024*1024;SELECTED_CAP=512*1024*1024
class Stop(RuntimeError):pass

def safe_name(name):
 if name.startswith('./'):name=name[2:]
 p=pathlib.PurePosixPath(name)
 if not name or p.is_absolute() or '..' in p.parts:raise Stop('MEMBER_PATH')
 return str(p)
def selected(n):
 if any(x in n for x in ['/gio/modules/','/girepository-','/glib-2.0/codegen/']):return False
 return n.startswith(('usr/include/','usr/lib/x86_64-linux-gnu/','lib/x86_64-linux-gnu/','usr/lib64/','lib64/')) or (n.startswith('usr/lib/gcc/x86_64-linux-gnu/13/') and n.endswith(('.o','.a','.so')))
def link_target(name,target):
 if not target:raise Stop('EMPTY_LINK')
 parts=[] if target.startswith('/') else list(pathlib.PurePosixPath(name).parent.parts)
 for part in pathlib.PurePosixPath(target).parts:
  if part in ('/','.'):continue
  if part=='..':
   if not parts:raise Stop('LINK_ESCAPE')
   parts.pop()
  else:parts.append(part)
 return '/'.join(parts)
def link_errors(records):
 errors=[]
 for name,row in records.items():
  if row['type']!='symlink':continue
  seen={name};target=row['normalized_target']
  while target in records and records[target]['type']=='symlink':
   if target in seen:errors.append({'path':name,'reason':'LINK_CYCLE'});break
   seen.add(target);target=records[target]['normalized_target']
  else:
   if target not in records:errors.append({'path':name,'target':target,'reason':'LINK_TARGET_MISSING'})
 return errors
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*a,**kw):raise Stop('REDIRECT_REFUSED')
def acquire(row,dest,deadline):
 url=row['url'];prefix='https://archive.ubuntu.com/ubuntu/pool/'
 if not url.startswith(prefix) or '?' in url or '#' in url:raise Stop('ORIGIN_PATH')
 expected=row['bytes'];h=hashlib.sha256();count=0
 with urllib.request.build_opener(NoRedirect).open(url,timeout=min(20,max(.01,deadline-time.monotonic()))) as response, dest.open('xb') as out:
  if response.status!=200 or response.getheader('Content-Length')!=str(expected):raise Stop('HTTP_LENGTH')
  while True:
   if time.monotonic()>=deadline:raise Stop('DEADLINE')
   b=response.read(min(65536,expected-count+1))
   if not b:break
   count+=len(b)
   if count>expected:raise Stop('BODY_CAP')
   h.update(b);out.write(b)
 if count!=expected or h.hexdigest()!=row['sha256']:raise Stop('PACKAGE_PIN')
 return {'package':row['package'],'url':url,'bytes':count,'sha256':h.hexdigest()}
def inventory_tar(tf,package,records,stage,total):
 seen=set()
 for member in tf:
  n=safe_name(member.name)
  if n in seen:raise Stop('DUPLICATE_MEMBER')
  seen.add(n)
  if member.isdir():continue
  if member.size>MEMBER_CAP:raise Stop('MEMBER_SIZE')
  if not selected(n):continue
  if n in records:raise Stop('SELECTED_PATH_COLLISION')
  if member.issym():records[n]={'path':n,'package':package,'type':'symlink','target':member.linkname,'normalized_target':link_target(n,member.linkname)};continue
  if not member.isfile():raise Stop('UNSUPPORTED_SELECTED_TYPE')
  total+=member.size
  if total>SELECTED_CAP:raise Stop('SELECTED_TOTAL_CAP')
  data=tf.extractfile(member).read(MEMBER_CAP+1)
  if len(data)!=member.size:raise Stop('MEMBER_TRUNCATED')
  # All physical outputs regular0600, no archive permissions/symlinks applied.
  out=stage/n;out.parent.mkdir(mode=0o700,parents=True,exist_ok=True);out.write_bytes(data);out.chmod(0o600)
  records[n]={'path':n,'package':package,'type':'file','bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
 return total
def main():
 p=argparse.ArgumentParser();p.add_argument('--pins',required=True);p.add_argument('--pins-sha256',required=True);p.add_argument('--private-root',required=True);a=p.parse_args()
 raw=pathlib.Path(a.pins).read_bytes()
 if hashlib.sha256(raw).hexdigest()!=a.pins_sha256:raise Stop('PROPOSAL_PIN')
 pins=json.loads(raw);rows=pins['packages']
 if len(rows)!=13 or sum(r['bytes'] for r in rows)!=TOTAL or len({r['package'] for r in rows})!=13:raise Stop('EXACT13_REQUIRED')
 root=pathlib.Path(a.private_root);root.mkdir(mode=0o700);stage=root/'selected';stage.mkdir(mode=0o700);records={};receipts=[];total=0;deadline=time.monotonic()+600
 try:
  for i,row in enumerate(rows):
   deb=root/f'{i}.deb';receipts.append(acquire(row,deb,deadline));tar=root/'data.tar'
   # Host trusted dpkg-deb parser only. No -i, maintainer scripts, apt, chroot or guest executable.
   with tar.open('xb') as out:
    child=subprocess.Popen(['dpkg-deb','--fsys-tarfile',str(deb)],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    try:
     count=0
     while True:
      if time.monotonic()>=deadline:raise Stop('DEADLINE')
      ready,_,_=select.select([child.stdout],[],[],min(1,max(.01,deadline-time.monotonic())))
      if not ready:continue
      b=os.read(child.stdout.fileno(),65536)
      if not b:break
      count+=len(b)
      if count>UNPACK_CAP:raise Stop('UNPACK_CAP')
      out.write(b)
     if child.wait(timeout=max(.01,deadline-time.monotonic())):raise Stop('DEB_PARSE')
    finally:
     if child.poll() is None:child.kill();child.wait()
   with tarfile.open(tar,mode='r:') as tf:total=inventory_tar(tf,row['package'],records,stage,total)
   tar.unlink();deb.unlink()
  from qualify_static import qualify,canonical
  canonical_records={canonical(n):r for n,r in records.items()}
  if len(canonical_records)!=len(records):raise Stop('USRMERGE_COLLISION')
  adjusted={n:dict(r,normalized_target=canonical(r['normalized_target'])) if r['type']=='symlink' else r for n,r in canonical_records.items()}
  missing=link_errors(adjusted)
  result={'scope':'SELECTED_GUEST_DATA_ONLY_NOT_COMPILE_READY','packages':receipts,'files':list(records.values()),'unresolved_links':missing,'complete':False,'compiler_executed':False,'guest_executed':False,'selected_bytes':total,'producer_elf':'NOT_BUILT','runtime_asset':'NOT_READY'}
  (root/'member-binding.json').write_text(json.dumps(result,indent=2));print(json.dumps({'packages':len(receipts),'selected_files':len(records),'selected_bytes':total,'unresolved_links':len(missing),'scope':result['scope']}))
  if missing:raise Stop('SELECTED_LINK_TARGET_MISSING')
  static=qualify(records,stage,deadline)
  (root/'static-elf.json').write_text(json.dumps(static,indent=2))
  if static['missing']:raise Stop('STATIC_DSO_CLOSURE_MISSING')
 finally:
  # Failure receipt only, never inferred success. Caller owns bounded always-run full-root cleanup.
  (root/'download-receipts.json').write_text(json.dumps({'completed_packages':receipts,'complete':len(receipts)==13},indent=2))
if __name__=='__main__':
 try:main()
 except Exception:
  print(json.dumps({'scope':'GUEST_DATA_STOP','reason':'DATA_STAGE_FAILED_NO_RETRY'}),flush=True)
  raise SystemExit(1)

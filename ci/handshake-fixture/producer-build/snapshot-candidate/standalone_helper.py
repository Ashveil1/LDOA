"""One NDK29 producer snapshot candidate. Not an approved trust policy or executable build wrapper."""
import os,pathlib,stat,hashlib,json,time,math
NDK='/usr/local/lib/android/sdk/ndk/29.0.14206865'
HOST='toolchains/llvm/prebuilt/linux-x86_64'
TOOLS=[HOST+'/bin/clang',HOST+'/bin/ld.lld']
DESCRIPTORS=['source.properties',HOST+'/AndroidVersion.txt',HOST+'/clang_source_info.md',HOST+'/NOTICE.toolchain']
MAX_TOOL=512*1024**2;MAX_HEADERS=64*1024**2;MAX_TOTAL=600*1024**2;MAX_RECEIPT=4*1024**2
class Stop(RuntimeError):pass
def safe_reason(error):
 allowed={'DEADLINE_INVALID','DEADLINE','PATH','IMAGE_FIELDS','IMAGE_IDENTITY','IMAGE_CONTEXT','INVENTORY','CANONICAL_CONFLICT','TOOL_SCOPE','HEADER_SCOPE','DESCRIPTOR_SCOPE','SCOPE','INCOMPLETE_INVENTORY','HEADER_CAP','SNAPSHOT_CAP','DEPTH','TYPE','SOURCE_CHANGED','SOURCE_GREW','SOURCE_REPLACED','COPY_HASH','ANCESTOR_LABEL','ANCESTOR_CAP','RECEIPT_CAP','PATH_ESCAPE','SETID','RESOURCE_AMBIGUOUS','TRAVERSAL_CAP','REVISION','ROOT_BOUNDARY','OUTPUT_EXISTS','SNAPSHOT_CHANGED','CONTEXT_CHANGED','INVENTORY_CHANGED','HELPER_BOUNDARY','ALIAS_CHAIN','CLEANUP_FAILED','INSTALL_BOUNDARY','HELPER_DIGEST','RECEIPT_WRITE'}
 return error.args[0] if isinstance(error,Stop) and error.args and error.args[0] in allowed else 'SAFE_SNAPSHOT_STOP'
def tick(deadline,clock=time.monotonic):
 now=clock()
 if not math.isfinite(deadline) or not math.isfinite(now) or deadline-now>1200:raise Stop('DEADLINE_INVALID')
 if now>=deadline:raise Stop('DEADLINE')
def label(name):
 import re
 p=pathlib.PurePosixPath(name)
 if not name or p.is_absolute() or '..' in p.parts or str(p)!=name or len(name)>512 or not re.fullmatch(r'[A-Za-z0-9_+./-]+',name):raise Stop('PATH')
 return name
def metadata(s):
 return {'dev':s.st_dev,'ino':s.st_ino,'uid':s.st_uid,'gid':s.st_gid,'mode':stat.S_IMODE(s.st_mode),'size':s.st_size,'mtime_ns':s.st_mtime_ns,'ctime_ns':s.st_ctime_ns}
def managed_image(data):
 # Inputs must be supplied by the reviewed workflow from managed runner context, not repo files.
 import re
 expected={'ImageOS','ImageVersion','runner_environment','run_id','run_attempt','repository','workflow_sha','head_sha'}
 if set(data)!=expected:raise Stop('IMAGE_FIELDS')
 if data['ImageOS']!='ubuntu22' or not re.fullmatch(r'\d{8}\.\d+\.\d+',data['ImageVersion']):raise Stop('IMAGE_IDENTITY')
 if data['runner_environment']!='github-hosted' or data['repository']!='Ashveil1/LDOA':raise Stop('IMAGE_CONTEXT')
 for k in ['run_id','run_attempt']:
  if not re.fullmatch(r'[1-9][0-9]*',data[k]):raise Stop('IMAGE_CONTEXT')
 for k in ['workflow_sha','head_sha']:
  if not re.fullmatch(r'[0-9a-f]{40}',data[k]):raise Stop('IMAGE_CONTEXT')
 return dict(data)
def admit(rows):
 # Validate count/size before any file hashing/copying; aliases to same target count once in byte caps.
 tools={};headers={};descriptors={};names=set();targets={};resource=set()
 for r in rows:
  name=label(r['logical']);canonical=label(r['canonical']);size=r['metadata']['size'];scope=r['scope']
  if name in names or type(size)!=int or size<0:raise Stop('INVENTORY')
  names.add(name)
  if canonical in targets and targets[canonical]!=r['metadata']:raise Stop('CANONICAL_CONFLICT')
  targets[canonical]=r['metadata']
  if scope=='TOOL':
   if name not in TOOLS:raise Stop('TOOL_SCOPE')
   tools[canonical]=size
  elif scope=='HEADER':
   prefix=HOST+'/lib/clang/'
   if not name.startswith(prefix) or '/include/' not in name[len(prefix):]:raise Stop('HEADER_SCOPE')
   resource.add(name.split('/include/',1)[0]);headers[canonical]=size
  elif scope=='DESCRIPTOR':
   if name not in DESCRIPTORS or size>4*1024**2:raise Stop('DESCRIPTOR_SCOPE')
   descriptors[canonical]=size
  else:raise Stop('SCOPE')
 if not set(TOOLS).issubset(names) or 'source.properties' not in names or len(resource)!=1:raise Stop('INCOMPLETE_INVENTORY')
 if len(headers)>4096 or sum(headers.values())>MAX_HEADERS:raise Stop('HEADER_CAP')
 if sum(tools.values())>MAX_TOOL or sum({k:v['size'] for k,v in targets.items()}.values())>MAX_TOTAL:raise Stop('SNAPSHOT_CAP')
 return {'headers':len(headers),'header_bytes':sum(headers.values()),'tools_bytes':sum(tools.values()),'snapshot_bytes':sum(v['size'] for v in targets.values()),'resource':next(iter(resource))}
def open_beneath(root,name):
 # Canonical relative path only. Every component uses O_NOFOLLOW, descriptor traversal avoids ancestor replacement.
 label(name);opened=[]
 try:
  fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW);opened.append(fd)
  parts=pathlib.PurePosixPath(name).parts
  if len(parts)>32:raise Stop('DEPTH')
  for part in parts[:-1]:
   fd=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd);opened.append(fd)
  child=os.open(parts[-1],os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=fd)
  if not stat.S_ISREG(os.fstat(child).st_mode):os.close(child);raise Stop('TYPE')
  return child
 finally:
  for fd in reversed(opened):os.close(fd)
def copy_exact(root,canonical,dest,expected,deadline,clock=time.monotonic):
 # dest is created only under a fresh private tree, no repository paths permitted by privileged caller.
 tick(deadline,clock);fd=open_beneath(root,canonical)
 try:
  before=metadata(os.fstat(fd))
  if before!=expected:raise Stop('SOURCE_CHANGED')
  h=hashlib.sha256();total=0
  with open(dest,'xb') as out:
   while True:
    tick(deadline,clock);b=os.read(fd,65536)
    if not b:break
    total+=len(b)
    if total>expected['size']:raise Stop('SOURCE_GREW')
    out.write(b);h.update(b)
   out.flush();os.fsync(out.fileno())
  if metadata(os.fstat(fd))!=before or total!=expected['size']:raise Stop('SOURCE_CHANGED')
  # Reopen logical canonical path to detect replaced names after descriptor was opened.
  other=open_beneath(root,canonical)
  try:
   if metadata(os.fstat(other))!=before:raise Stop('SOURCE_REPLACED')
  finally:os.close(other)
  # Compare the destination bytes, not only successful write or source hash.
  g=hashlib.sha256()
  with open(dest,'rb') as f:
   while True:
    tick(deadline,clock);b=f.read(65536)
    if not b:break
    g.update(b)
  if g.hexdigest()!=h.hexdigest():raise Stop('COPY_HASH')
  return h.hexdigest()
 finally:os.close(fd)
def receipt(image,rows,ancestors,deadline,clock=time.monotonic):
 tick(deadline,clock);counts=admit(rows);image=managed_image(image)
 # Deduplicate ancestor metadata. Only safe relative labels or numeric root-depth labels, never outside path text.
 table={};items=[]
 for r in rows:
  ids=[]
  for a in ancestors[r['logical']]:
   name=a['label']
   if name.startswith('ROOT_DEPTH_'):
    if not name.removeprefix('ROOT_DEPTH_').isdigit():raise Stop('ANCESTOR_LABEL')
   else:label(name)
   encoded=json.dumps(a,sort_keys=True,separators=(',',':'));key=hashlib.sha256(encoded.encode()).hexdigest()
   table[key]=a;ids.append(key)
  if len(ids)>32:raise Stop('ANCESTOR_CAP')
  items.append({**r,'ancestors':ids})
 data={'scope':'CANDIDATE_JOB_SCOPED_RUNNER_IMAGE_SNAPSHOT_NOT_PROVENANCE','status':'NOT_APPROVED','managed_image':image,'counts':counts,'files':items,'ancestors':table,'host_dependencies':'UNQUALIFIED_NO_EXECUTION','compiler_executed':False,'runtime_asset_ready':False,'deadline_monotonic':deadline}
 if len(json.dumps(data,sort_keys=True,separators=(',',':')).encode())>MAX_RECEIPT:raise Stop('RECEIPT_CAP')
 return data
def host_dependency_contract():
 return {'status':'UNQUALIFIED_NO_EXECUTION','discovery':'FUTURE_SEPARATELY_APPROVED_STATIC_ELF_PARSER_ONLY','ldd':False,'require':['canonical interpreter','every recursive DT_NEEDED target','RPATH/RUNPATH interpretation','source/metadata/hash binding','explicit runner-OS trust decision or private snapshot of closure'],'tool_execution_allowed':False}
def inventory(root,deadline,clock=time.monotonic):
 """Descriptor-based metadata inventory, no tool execution. root fixed by future privileged adapter."""
 root=pathlib.Path(root);realroot=root.resolve(strict=True);rows=[];parents={}
 def record(p,scope):
  tick(deadline,clock);logical=label(p.relative_to(root).as_posix());real=p.resolve(strict=True)
  if not real.is_relative_to(realroot):raise Stop('PATH_ESCAPE')
  canonical=label(real.relative_to(realroot).as_posix());fd=open_beneath(realroot,canonical)
  try:meta=metadata(os.fstat(fd))
  finally:os.close(fd)
  if meta['mode']&(stat.S_ISUID|stat.S_ISGID):raise Stop('SETID')
  chain=[real,*real.parents]
  if len(chain)>32:raise Stop('ANCESTOR_CAP')
  ancestor=[]
  for q in chain:
   tick(deadline,clock);name=('NDK' if q==realroot else 'NDK/'+q.relative_to(realroot).as_posix()) if q.is_relative_to(realroot) else 'ROOT_DEPTH_'+str(len(q.parts))
   ancestor.append({'label':name,**metadata(q.stat())})
  rows.append({'logical':logical,'canonical':canonical,'scope':scope,'metadata':meta,'alias_chain':alias_chain(root,logical)});parents[logical]=ancestor
 for name in TOOLS:record(root/name,'TOOL')
 for name in DESCRIPTORS:
  p=root/name
  if name=='source.properties' or p.exists() or p.is_symlink():record(p,'DESCRIPTOR')
 bases=list((root/HOST/'lib/clang').glob('*/include'))
 if len(bases)!=1:raise Stop('RESOURCE_AMBIGUOUS')
 stack=[bases[0]];visited=set();entries=0;headers=0;size=0
 while stack:
  directory=stack.pop();real=directory.resolve(strict=True)
  if not real.is_relative_to(realroot) or real in visited:raise Stop('PATH_ESCAPE')
  visited.add(real)
  for p in directory.iterdir():
   tick(deadline,clock);entries+=1
   if entries>8192:raise Stop('TRAVERSAL_CAP')
   if p.is_dir():stack.append(p);continue
   if headers>=4096:raise Stop('HEADER_CAP')
   # Metadata only here; count/size admission before any subsequent copy/hash.
   record(p,'HEADER');headers+=1;size+=rows[-1]['metadata']['size']
   if size>MAX_HEADERS:raise Stop('HEADER_CAP')
 counts=admit(rows)
 return rows,parents,counts

def alias_chain(root,name):
 # Walk each lexical and redirected component, recording lstat, including every symlink.
 root=pathlib.Path(root);pending=list(pathlib.PurePosixPath(label(name)).parts);parts=[];events=[];hops=0
 while pending:
  part=pending.pop(0)
  if part in ('','.'):continue
  if part=='..':
   if not parts:raise Stop('ALIAS_CHAIN')
   parts.pop();continue
  p=root.joinpath(*parts,part);st=p.lstat();rel=p.relative_to(root).as_posix()
  event={'path':rel,'metadata':metadata(st),'type':stat.S_IFMT(st.st_mode)}
  if stat.S_ISLNK(st.st_mode):
   hops+=1
   if hops>32:raise Stop('ALIAS_CHAIN')
   target=os.readlink(p);event['target']=target
   if target.startswith('/') or len(target)>512:raise Stop('ALIAS_CHAIN')
   pending=list(pathlib.PurePosixPath(target).parts)+pending
  else:parts.append(part)
  events.append(event)
  if len(events)>128:raise Stop('ALIAS_CHAIN')
 return events

"""Pure proposed dispatch binding, no I/O or effect."""
import re

REPO='Ashveil1/LDOA'
PATH='.github/workflows/isolated-ndk29-snapshot-candidate.yml'
def bind(actual,expected):
 if set(actual)!={'workflow_sha','head_sha','ref','workflow_ref','repository'} or set(expected)!={'head_sha','ref'}:raise Stop('BINDING_FIELDS')
 if not re.fullmatch('[0-9a-f]{40}',actual['workflow_sha']):raise Stop('WORKFLOW_CONTEXT_SHAPE')
 for name in ['head_sha']:
  if not re.fullmatch('[0-9a-f]{40}',expected[name]) or actual[name]!=expected[name]:raise Stop('BINDING_SHA')
 ref=expected['ref']
 if not isinstance(ref,str) or not ref.startswith(('refs/heads/','refs/tags/')):raise Stop('BINDING_REF')
 short=ref.split('/',2)[2]
 if not short or len(ref)>512 or ref.endswith(('/', '.')) or '..' in ref or '@{' in ref or '\\' in ref or short=='@':raise Stop('BINDING_REF')
 if any(ord(c)<33 or ord(c)==127 or c in '~^:?*[' for c in ref):raise Stop('BINDING_REF')
 parts=short.split('/')
 if any(not x or x.startswith('.') or x.endswith('.lock') for x in parts):raise Stop('BINDING_REF')
 # Branch names have stricter leading-dash restriction than generic refnames.
 if ref.startswith('refs/heads/') and short.startswith('-'):raise Stop('BINDING_REF')
 if actual['repository']!=REPO or actual['ref']!=ref or actual['workflow_ref']!=REPO+'/'+PATH+'@'+ref:raise Stop('BINDING_CONTEXT')
 # workflow_sha is recorded opaque. No guessed equality or last-touched semantics.
 return {'actual':dict(actual),'expected':dict(expected),'status':'CONTEXT_MATCH_EXTERNAL_CONTROLLER_APPROVAL_STILL_REQUIRED'}

# Standalone helper is generated by concatenating snapshot_contract.py and this source.
import sys,errno
PARENT='/opt/ldoa-qualification'
ROOT_NAME='ndk29-snapshot'
FLAGS=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW

def trusted_parent(path,uid=0):
 # Absolute descriptor walk. No symlink or writable ancestor, including /.
 path=pathlib.PurePosixPath(path)
 if not path.is_absolute():raise Stop('ROOT_BOUNDARY')
 fd=os.open('/',FLAGS)
 try:
  for part in ['',*path.parts[1:]]:
   if part:
    new=os.open(part,FLAGS,dir_fd=fd);os.close(fd);fd=new
   st=os.fstat(fd)
   if st.st_uid!=uid or stat.S_IMODE(st.st_mode)&0o022:raise Stop('ROOT_BOUNDARY')
  return fd
 except BaseException:os.close(fd);raise

def create_root(parentfd,name=ROOT_NAME):
 if name!=ROOT_NAME:raise Stop('ROOT_BOUNDARY')
 os.mkdir(name,0o700,dir_fd=parentfd) # Existing file/directory/symlink all fail, no overwrite.
 fd=os.open(name,FLAGS,dir_fd=parentfd);s=os.fstat(fd)
 return fd,(s.st_dev,s.st_ino)

def root_binding(parentfd,rootfd,identity):
 a=os.fstat(rootfd);b=os.stat(ROOT_NAME,dir_fd=parentfd,follow_symlinks=False)
 if not stat.S_ISDIR(b.st_mode) or (a.st_dev,a.st_ino)!=identity or (b.st_dev,b.st_ino)!=identity:raise Stop('ROOT_BOUNDARY')

def relative_dir(rootfd,name,create=False):
 parts=pathlib.PurePosixPath(label(name)).parts;fd=os.dup(rootfd)
 try:
  for part in parts:
   if create:
    try:os.mkdir(part,0o700,dir_fd=fd)
    except FileExistsError:pass
   child=os.open(part,FLAGS,dir_fd=fd);os.close(fd);fd=child
  return fd
 except BaseException:os.close(fd);raise

def parent_of(rootfd,name,create=False):
 parts=pathlib.PurePosixPath(label(name)).parts
 fd=relative_dir(rootfd,'/'.join(parts[:-1]),create) if len(parts)>1 else os.dup(rootfd)
 return fd,parts[-1]

def copy_to_fd(source,name,rootfd,row,deadline):
 tick(deadline);src=open_beneath(source,name);out=None;destparent=None
 try:
  before=metadata(os.fstat(src))
  if before!=row['metadata']:raise Stop('SOURCE_CHANGED')
  destparent,last=parent_of(rootfd,name,True)
  out=os.open(last,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=destparent)
  h=hashlib.sha256();size=0
  while True:
   tick(deadline);b=os.read(src,65536)
   if not b:break
   size+=len(b)
   if size>before['size']:raise Stop('SOURCE_GREW')
   h.update(b);view=memoryview(b)
   while view:
    tick(deadline);n=os.write(out,view)
    if n<=0:raise Stop('COPY_HASH')
    view=view[n:]
  os.fsync(out)
  if size!=before['size'] or metadata(os.fstat(src))!=before:raise Stop('SOURCE_CHANGED')
  again=open_beneath(source,name)
  try:
   if metadata(os.fstat(again))!=before:raise Stop('SOURCE_REPLACED')
  finally:os.close(again)
  return h.hexdigest()
 finally:
  os.close(src)
  if out is not None:os.close(out)
  if destparent is not None:os.close(destparent)

def hash_fd(fd,expected,deadline):
 h=hashlib.sha256();total=0;before=metadata(os.fstat(fd))
 if before['size']!=expected:raise Stop('SNAPSHOT_CHANGED')
 while True:
  tick(deadline);b=os.read(fd,65536)
  if not b:break
  total+=len(b)
  if total>expected:raise Stop('SNAPSHOT_CHANGED')
  h.update(b)
 if total!=expected or metadata(os.fstat(fd))!=before:raise Stop('SNAPSHOT_CHANGED')
 return h.hexdigest()

def fd_walk(rootfd,deadline):
 # Yield parentfd/name/lstat for one entry at a time; descriptor remains live during yield.
 def walk(fd,prefix):
  for name in os.listdir(fd):
   tick(deadline);label(name);s=os.stat(name,dir_fd=fd,follow_symlinks=False);rel=prefix+name
   yield fd,name,rel,s
   if stat.S_ISDIR(s.st_mode):
    child=os.open(name,FLAGS,dir_fd=fd)
    try:
     now=os.fstat(child)
     if (now.st_dev,now.st_ino)!=(s.st_dev,s.st_ino):raise Stop('ROOT_BOUNDARY')
     yield from walk(child,rel+'/')
    finally:os.close(child)
 yield from walk(rootfd,'')

def aliases(rootfd,rows):
 for row in rows:
  if row['logical']==row['canonical']:continue
  parent,name=parent_of(rootfd,row['logical'],True)
  try:os.symlink(os.path.relpath(row['canonical'],str(pathlib.PurePosixPath(row['logical']).parent)),name,dir_fd=parent)
  finally:os.close(parent)

def seal_fd(rootfd,rows,deadline,uid=0):
 tools={r['canonical'] for r in rows if r['scope']=='TOOL'}
 # Directories sealed after descendants, through opened descriptors only.
 def seal(fd,prefix):
  for name in os.listdir(fd):
   tick(deadline);label(name);st=os.stat(name,dir_fd=fd,follow_symlinks=False);rel=prefix+name
   if st.st_uid!=uid:raise Stop('ROOT_BOUNDARY')
   if stat.S_ISLNK(st.st_mode):continue
   flags=os.O_RDONLY|os.O_NOFOLLOW|(os.O_DIRECTORY if stat.S_ISDIR(st.st_mode) else 0)
   child=os.open(name,flags,dir_fd=fd)
   try:
    now=os.fstat(child)
    if (now.st_dev,now.st_ino)!=(st.st_dev,st.st_ino):raise Stop('ROOT_BOUNDARY')
    if stat.S_ISDIR(st.st_mode):seal(child,rel+'/')
    elif stat.S_ISREG(st.st_mode):os.fchmod(child,0o555 if rel in tools else 0o444)
    else:raise Stop('TYPE')
   finally:os.close(child)
  os.fchmod(fd,0o555)
 seal(rootfd,'')

def verify_fd(rootfd,rows,deadline,uid=0):
 files={r['canonical']:r for r in rows};links={r['logical']:os.path.relpath(r['canonical'],str(pathlib.PurePosixPath(r['logical']).parent)) for r in rows if r['logical']!=r['canonical']}
 tools={r['canonical'] for r in rows if r['scope']=='TOOL'};seen=set();seenlinks=set();expected_dirs=set()
 for n in [*files,*links]:
  expected_dirs.update(str(x) for x in pathlib.PurePosixPath(n).parents if str(x)!='.')
 rootstat=os.fstat(rootfd)
 if rootstat.st_uid!=uid or stat.S_IMODE(rootstat.st_mode)!=0o555:raise Stop('SNAPSHOT_CHANGED')
 for parent,name,rel,st in fd_walk(rootfd,deadline):
  if st.st_uid!=uid:raise Stop('SNAPSHOT_CHANGED')
  if stat.S_ISLNK(st.st_mode):
   if rel not in links or os.readlink(name,dir_fd=parent)!=links[rel]:raise Stop('SNAPSHOT_CHANGED')
   seenlinks.add(rel)
  elif stat.S_ISDIR(st.st_mode):
   if rel not in expected_dirs or stat.S_IMODE(st.st_mode)!=0o555:raise Stop('SNAPSHOT_CHANGED')
  elif stat.S_ISREG(st.st_mode):
   if rel not in files or stat.S_IMODE(st.st_mode)!=(0o555 if rel in tools else 0o444):raise Stop('SNAPSHOT_CHANGED')
   fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=parent)
   try:
    now=os.fstat(fd)
    if (now.st_dev,now.st_ino)!=(st.st_dev,st.st_ino) or hash_fd(fd,files[rel]['metadata']['size'],deadline)!=files[rel]['sha256']:raise Stop('SNAPSHOT_CHANGED')
   finally:os.close(fd)
   seen.add(rel)
  else:raise Stop('TYPE')
 if seen!=set(files) or seenlinks!=set(links):raise Stop('SNAPSHOT_CHANGED')

def cleanup_fd(parentfd,rootfd,identity):
 root_binding(parentfd,rootfd,identity)
 def clear(fd):
  os.fchmod(fd,0o700)
  for name in os.listdir(fd):
   label(name);st=os.stat(name,dir_fd=fd,follow_symlinks=False)
   if stat.S_ISDIR(st.st_mode):
    child=os.open(name,FLAGS,dir_fd=fd)
    try:
     now=os.fstat(child)
     if (now.st_dev,now.st_ino)!=(st.st_dev,st.st_ino):raise Stop('ROOT_BOUNDARY')
     clear(child)
    finally:os.close(child)
    os.rmdir(name,dir_fd=fd)
   else:os.unlink(name,dir_fd=fd) # unlinks symlinks, never follows.
 clear(rootfd);root_binding(parentfd,rootfd,identity);os.rmdir(ROOT_NAME,dir_fd=parentfd)
 try:os.stat(ROOT_NAME,dir_fd=parentfd,follow_symlinks=False)
 except FileNotFoundError:return
 raise Stop('CLEANUP_FAILED')

def exact_revision(source,rows,deadline):
 r=next(r for r in rows if r['logical']=='source.properties');fd=open_beneath(source,r['canonical'])
 try:
  if r['metadata']['size']>65536:raise Stop('REVISION')
  if metadata(os.fstat(fd))!=r['metadata']:raise Stop('SOURCE_CHANGED')
  tick(deadline);data=os.read(fd,65537)
  if metadata(os.fstat(fd))!=r['metadata']:raise Stop('SOURCE_CHANGED')
 finally:os.close(fd)
 try:lines=data.decode('utf-8').splitlines()
 except UnicodeError:raise Stop('REVISION')
 values=[x.split('=',1)[1].strip() for x in lines if '=' in x and x.split('=',1)[0].strip()=='Pkg.Revision']
 if values!=['29.0.14206865']:raise Stop('REVISION')

def fingerprint(rows,parents):return json.dumps({'rows':sorted(rows,key=lambda r:r['logical']),'parents':parents},sort_keys=True,separators=(',',':'))
def snapshot_only(source,parentfd,image,deadline,uid=0,emit=None):
 result={'scope':'JOB_IMAGE_SNAPSHOT_CANDIDATE','status':'STOP','tool_executed':False,'host_dependencies':'UNQUALIFIED_NO_EXECUTION'};fd=None;identity=None
 try:
  managed_image(image);tick(deadline);fd,identity=create_root(parentfd)
  rows,parents,_=inventory(source,deadline);exact_revision(source,rows,deadline);before=fingerprint(rows,parents)
  receipt(image,rows,parents,deadline) # receipt preflight cap before bytecopy.
  unique={r['canonical']:r for r in rows}
  for name,r in unique.items():r['sha256']=copy_to_fd(source,name,fd,r,deadline)
  again,again_parents,_=inventory(source,deadline)
  if before!=fingerprint(again,again_parents):raise Stop('INVENTORY_CHANGED')
  for name,r in unique.items():
   sourcefd=open_beneath(source,name)
   try:
    if metadata(os.fstat(sourcefd))!=r['metadata'] or hash_fd(sourcefd,r['metadata']['size'],deadline)!=r['sha256']:raise Stop('SOURCE_CHANGED')
   finally:os.close(sourcefd)
  for row in rows:row['sha256']=unique[row['canonical']]['sha256']
  aliases(fd,rows);root_binding(parentfd,fd,identity);seal_fd(fd,rows,deadline,uid);verify_fd(fd,rows,deadline,uid)
  result=receipt(image,rows,parents,deadline);result['status']='SNAPSHOT_ONLY_NOT_APPROVED';result['host_dependencies']='UNQUALIFIED_NO_EXECUTION';result['snapshot_only']=True
  verify_fd(fd,rows,deadline,uid);root_binding(parentfd,fd,identity)
 except Exception as error:result['reason']=safe_reason(error)
 finally:
  # Emit receipt BEFORE cleanup and again after. A cleanup failure does not erase the primary reason.
  if emit:
   try:emit({'phase':'BEFORE_CLEANUP','receipt':result})
   except Exception:result['receipt_emit']='FAILED';result['status']='STOP'
  if fd is not None:
   try:cleanup_fd(parentfd,fd,identity);result['cleanup']='ABSENT'
   except Exception as error:result['cleanup']='FAILED';result['cleanup_reason']=safe_reason(error);result['status']='STOP'
   finally:os.close(fd)
  else:result['cleanup']='NOT_CREATED'
  if emit:
   try:emit({'phase':'AFTER_CLEANUP','receipt':result})
   except Exception:result['receipt_emit']='FAILED';result['status']='STOP'
 return result

def cli():
 # No path or policy arguments accepted. Context file is installed rootowned by workflow-literal installer.
 result=None
 try:
  if len(sys.argv)!=1 or os.geteuid()!=0:raise Stop('HELPER_BOUNDARY')
  parentfd=trusted_parent(PARENT)
  try:
   st=os.stat('context.json',dir_fd=parentfd,follow_symlinks=False)
   if not stat.S_ISREG(st.st_mode) or st.st_uid!=0 or stat.S_IMODE(st.st_mode)!=0o444 or st.st_size>8192:raise Stop('HELPER_BOUNDARY')
   contextfd=os.open('context.json',os.O_RDONLY|os.O_NOFOLLOW,dir_fd=parentfd)
   try:
    context=json.loads(os.read(contextfd,8193));image=managed_image(context['managed_image']);dispatch_binding=bind(context['dispatch_binding']['actual'],context['dispatch_binding']['expected'])
   finally:os.close(contextfd)
   deadlinefd=os.open('deadline',os.O_RDONLY|os.O_NOFOLLOW,dir_fd=parentfd)
   try:
    st=os.fstat(deadlinefd)
    if st.st_uid!=0 or not stat.S_ISREG(st.st_mode) or stat.S_IMODE(st.st_mode)!=0o444 or st.st_size>64:raise Stop('HELPER_BOUNDARY')
    deadline=float(os.read(deadlinefd,65));tick(deadline)
   finally:os.close(deadlinefd)
   result=snapshot_only(pathlib.Path(NDK),parentfd,image,deadline,emit=lambda data:print(json.dumps({**data,'dispatch_binding':dispatch_binding},sort_keys=True),flush=True))
  finally:os.close(parentfd)
 except Exception as error:print(json.dumps({'scope':'SNAPSHOT_PREFLIGHT_STOP','reason':safe_reason(error),'tool_executed':False}),flush=True)
 return 0 if result and result['status']=='SNAPSHOT_ONLY_NOT_APPROVED' and result.get('cleanup')=='ABSENT' else 1
if __name__=='__main__':raise SystemExit(cli())

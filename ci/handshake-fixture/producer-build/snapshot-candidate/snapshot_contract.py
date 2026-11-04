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

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
 if type(error).__name__=='StaticPhaseStop' and isinstance(error,Stop):return error.args[0] if error.args[0] in globals().get('HOST_REASONS',()) or error.args[0]=='STATIC_UNCLASSIFIED_STOP' else 'SAFE_SNAPSHOT_STOP'
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
"""Pure, bounded clang21/lld host closure candidate. Never reads or executes real files."""
import struct,re,hashlib,json
MAX_ELF=192*1024*1024;MAX_NODES=64;MAX_EDGES=256;HOST_MAX_TOTAL=512*1024*1024
ROOTS=('/lib/x86_64-linux-gnu/','/usr/lib/x86_64-linux-gnu/','/lib64/','/usr/lib64/')
def parse_elf(data):
 if not isinstance(data,bytes) or len(data)>MAX_ELF:raise Stop('ELF_SIZE')
 def span(offset,size):
  if offset<0 or size<0 or offset+size>len(data):raise Stop('ELF_BOUNDS')
  return data[offset:offset+size]
 def unpack(fmt,offset):return struct.unpack(fmt,span(offset,struct.calcsize(fmt)))
 if len(data)<64 or data[:7]!=b'\x7fELF\x02\x01\x01':raise Stop('ELF_FORMAT')
 fields=unpack('<HHIQQQIHHHHHH',16);typ,machine,version,entry,phoff,shoff,flags,ehsize,phsize,phnum,*_=fields
 if typ not in (2,3) or machine!=62 or version!=1 or ehsize!=64 or phsize!=56 or not 1<=phnum<=128:raise Stop('ELF_HEADER')
 span(phoff,phsize*phnum);loads=[];dynamic=[];interp=[]
 for i in range(phnum):
  ptype,pflags,off,va,pa,filesz,memsz,align=unpack('<IIQQQQQQ',phoff+i*phsize)
  if filesz>memsz:raise Stop('ELF_SEGMENT')
  if ptype in (1,2,3):span(off,filesz)
  if ptype==1:loads.append((va,filesz,off))
  elif ptype==2:dynamic.append((off,filesz))
  elif ptype==3:interp.append(span(off,filesz))
 if len(dynamic)>1 or len(interp)>1:raise Stop('ELF_DUP_SEGMENT')
 def string(raw,index=0):
  if not 0<=index<len(raw):raise Stop('ELF_STRING')
  end=raw.find(b'\0',index)
  if end<0 or end-index>4096:raise Stop('ELF_STRING')
  try:value=raw[index:end].decode('ascii')
  except UnicodeError:raise Stop('ELF_STRING')
  if not value or any(ord(c)<32 or ord(c)==127 for c in value):raise Stop('ELF_STRING')
  return value
 interpreter=string(interp[0]) if interp else None
 if interpreter and interpreter not in ('/lib64/ld-linux-x86-64.so.2','/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2'):raise Stop('HOST_INTERPRETER')
 tags={};needed=[]
 if dynamic:
  off,size=dynamic[0]
  if size%16 or size//16>4096:raise Stop('DYNAMIC_CAP')
  terminated=False
  for i in range(size//16):
   tag,value=unpack('<qQ',off+i*16)
   if tag==0:terminated=True;break
   if tag==1:
    needed.append(value)
    if len(needed)>64:raise Stop('NEEDED_CAP')
   elif tag in (5,10,14,15,29):
    if tag in tags:raise Stop('DYNAMIC_DUPLICATE')
    tags[tag]=value
   # ELF DF_1_NODEFLIB changes search semantics. Do not silently apply default roots.
   elif tag==0x6ffffffb and value&0x800:raise Stop('NODEFLIB_UNSUPPORTED')
  if not terminated:raise Stop('DYNAMIC_UNTERMINATED')
  if needed or any(t in tags for t in [14,15,29]):
   if 5 not in tags or 10 not in tags or not 0<tags[10]<=1024*1024:raise Stop('STRTAB')
   choices=[off+tags[5]-va for va,n,off in loads if va<=tags[5] and tags[5]+tags[10]<=va+n]
   if len(choices)!=1:raise Stop('STRTAB_MAPPING')
   raw=span(choices[0],tags[10])
  else:raw=b''
 else:raw=b''
 names=[string(raw,index) for index in needed]
 if len(names)!=len(set(names)) or any(not re.fullmatch(r'[A-Za-z0-9_.+-]+',n) for n in names):raise Stop('NEEDED_NAME')
 return {'interpreter':interpreter,'needed':names,'rpath':string(raw,tags[15]) if 15 in tags else None,'runpath':string(raw,tags[29]) if 29 in tags else None,'soname':string(raw,tags[14]) if 14 in tags else None,'architecture':'ELF64_LE_X86_64'}

PATH_CONTEXTS=frozenset({'START','ROW_CANONICAL','ALIAS_COMPONENT','RUNPATH_EXPANDED','UNSPECIFIED'})
PATH_CATEGORIES=frozenset({'NOT_STRING','NOT_ABSOLUTE','LENGTH','CHARSET','DOT_COMPONENT','PARENT_COMPONENT'})
class HostPathStop(Stop):
 def __init__(self,context,category):
  super().__init__('HOST_PATH')
  self.path_category={'context':context if context in PATH_CONTEXTS else 'UNSPECIFIED','category':category}
def canonical(path,context='UNSPECIFIED'):
 def fail(category):raise HostPathStop(context,category)
 if not isinstance(path,str):fail('NOT_STRING')
 if not path.startswith('/'):fail('NOT_ABSOLUTE')
 if len(path)>512:fail('LENGTH')
 if not re.fullmatch(r'[A-Za-z0-9_+./-]+',path):fail('CHARSET')
 parts=[]
 for p in path.split('/'):
  if not p:continue
  if p=='.':fail('DOT_COMPONENT')
  if p=='..':fail('PARENT_COMPONENT')
  parts.append(p)
 return '/'+ '/'.join(parts)

def closure(tool_paths,lookup):
 """lookup(path) returns frozen currentjob bytes+metadata+explicit alias chain, or None.
 Pure map callback in tests. Future I/O adapter must enforce descriptor/hash/context gates.
 No cache/environment/hwcaps discovery or executable loader. Unsupported semantics STOP.
 """
 if set(tool_paths)!={'clang21','lld'}:raise Stop('HOST_START_SET')
 queue=[(canonical(p,'START'),True) for p in tool_paths.values()];seen={};edges=[];bindings={};total=0
 def get(path):
  row=lookup(path)
  if row is None:return None
  required={'canonical','data','metadata','aliases'}
  if set(row)!=required or not isinstance(row['data'],bytes):raise Stop('HOST_ROW')
  real=canonical(row['canonical'],'ROW_CANONICAL');meta=row['metadata'];aliases=row['aliases']
  if not isinstance(aliases,list) or len(aliases)>32 or any(set(a)!={'path','target','metadata'} for a in aliases):raise Stop('HOST_ALIAS')
  for a in aliases:
   canonical(a['path'],'ALIAS_COMPONENT')
   if not isinstance(a['target'],str) or len(a['target'])>512:raise Stop('HOST_ALIAS')
  if meta.get('size')!=len(row['data']):raise Stop('HOST_SIZE')
  h=hashlib.sha256(row['data']).hexdigest();bound={'requested':path,'canonical':real,'metadata':meta,'aliases':aliases,'sha256':h}
  if path in bindings and bindings[path]!=bound:raise Stop('HOST_CHANGED')
  bindings[path]=bound;return real,row
 def resolve(soname,parsed,origin):
  # RUNPATH is direct-only. RPATH inheritance is NOT guessed, even when a match might exist.
  if parsed['rpath'] is not None:raise Stop('RPATH_INHERITANCE_UNSUPPORTED')
  roots=[]
  if parsed['runpath']:
   for value in parsed['runpath'].split(':'):
    if not value:raise Stop('HOST_RUNPATH')
    value=value.replace('${ORIGIN}',origin).replace('$ORIGIN',origin)
    if '$' in value:raise Stop('HOST_RUNPATH')
    value=canonical(value,'RUNPATH_EXPANDED')
    # NDK private $ORIGIN directories admitted only for the exact tool's same host subtree.
    if not value.startswith('/ldoa-qualification/ndk29-snapshot/toolchains/llvm/prebuilt/linux-x86_64/') and not any(value+'/'==r for r in ROOTS):raise Stop('HOST_RUNPATH')
    roots.append(value+'/')
  roots+=list(ROOTS)
  matches=[]
  for directory in dict.fromkeys(roots):
   row=get(directory+soname)
   if row is not None:matches.append((directory+soname,row))
  if not matches:raise Stop('HOST_MISSING')
  # Conservative: duplicate aliases to same canonical bytes fine; conflicting choices STOP, no loader-order inference.
  if len({(r[1][0],hashlib.sha256(r[1][1]['data']).hexdigest()) for r in matches})!=1:raise Stop('HOST_AMBIGUOUS')
  return matches[0]
 while queue:
  path,is_tool=queue.pop(0);hit=get(path)
  if hit is None:raise Stop('HOST_MISSING')
  real,row=hit
  if not is_tool and not any(real.startswith(r) for r in ROOTS) and not real.startswith('/ldoa-qualification/ndk29-snapshot/toolchains/llvm/prebuilt/linux-x86_64/'):raise Stop('HOST_OUTSIDE_ROOTS')
  if real in seen:continue
  if len(seen)>=MAX_NODES:raise Stop('HOST_NODE_CAP')
  total+=len(row['data'])
  if total>HOST_MAX_TOTAL:raise Stop('HOST_BYTE_CAP')
  parsed=parse_elf(row['data']);seen[real]={'parsed':parsed,'sha256':hashlib.sha256(row['data']).hexdigest(),'bytes':len(row['data'])}
  if is_tool and not parsed['interpreter']:raise Stop('HOST_TOOL_INTERPRETER_REQUIRED')
  if parsed['interpreter']:
   
   if len(edges)>=MAX_EDGES:raise Stop('HOST_EDGE_CAP')
   edges.append({'from':real,'kind':'interpreter','to':parsed['interpreter']});queue.append((parsed['interpreter'],False))
  for name in parsed['needed']:
   selected,_=resolve(name,parsed,real.rsplit('/',1)[0])
   if len(edges)>=MAX_EDGES:raise Stop('HOST_EDGE_CAP')
   edges.append({'from':real,'kind':'needed','name':name,'to':selected});queue.append((selected,False))
 return {'scope':'HOST_STATIC_CLOSURE_CANDIDATE_NOT_EXECUTION_PROOF','status':'UNAPPROVED_JOB_IMAGE_OS_BINDING','files':seen,'bindings':bindings,'edges':edges,'total_bytes':total,'loader_execution':False,'tool_execution':False,'runtime_linker_hwcaps_cache':'NOT_MODELED_REQUIRES_RESTRICTED_POLICY_OR_STOP'}
"""SOURCE ONLY descriptor-bound host rows. Tests use synthetic roots only. No executable tools."""
import os,stat,pathlib,hashlib,time
FLAGS=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
def meta(st):return {'dev':st.st_dev,'ino':st.st_ino,'uid':st.st_uid,'gid':st.st_gid,'mode':stat.S_IMODE(st.st_mode),'size':st.st_size,'mtime_ns':st.st_mtime_ns,'ctime_ns':st.st_ctime_ns}
def host_tick(deadline):
 if time.monotonic()>=deadline:raise Stop('HOST_BINDING_DEADLINE')
def capture(rootfd,logical,deadline,cap=MAX_ELF):
 """Return exact lexical alias events, terminal and bytes. Retain rootfd externally identity-bound.
 Canonical descriptorwalk starts again on each symlink expansion, never follows OS symlinks.
 Absolute symlink target interpreted beneath this explicit logical filesystemroot, not hostambient.
 """
 def components(value):
  if not isinstance(value,str) or len(value)>512 or '\0' in value:raise Stop('HOST_ALIAS_PATH')
  parts=[]
  for p in value.split('/'):
   if p in ('','.'):continue
   if p=='..':
    if not parts:raise Stop('HOST_ALIAS_ESCAPE')
    parts.pop()
   else:
    if any(ord(c)<33 or ord(c)==127 for c in p):raise Stop('HOST_ALIAS_PATH')
    parts.append(p)
  return parts
 parts=components(logical);events=[];seen=set();hops=0
 while True:
  fd=os.dup(rootfd);prefix=[];restart=False
  try:
   for index,part in enumerate(parts):
    host_tick(deadline);st=os.stat(part,dir_fd=fd,follow_symlinks=False);path='/'+ '/'.join(prefix+[part]);event={'path':path,'metadata':meta(st),'type':stat.S_IFMT(st.st_mode)}
    if stat.S_ISLNK(st.st_mode):
     target=os.readlink(part,dir_fd=fd);after=os.stat(part,dir_fd=fd,follow_symlinks=False)
     if meta(after)!=meta(st):raise Stop('HOST_ALIAS_CHANGED')
     event['target']=target;events.append(event);hops+=1
     if hops>32:raise Stop('HOST_ALIAS_CAP')
     rewritten=(target if target.startswith('/') else '/'.join(prefix+[target]))+'/'+ '/'.join(parts[index+1:]);parts=components(rewritten)
     key=tuple(parts)
     if key in seen:raise Stop('HOST_ALIAS_CYCLE')
     seen.add(key);restart=True;break
    events.append(event)
    if len(events)>256:raise Stop('HOST_ALIAS_CAP')
    if index<len(parts)-1:
     child=os.open(part,FLAGS,dir_fd=fd)
     if meta(os.fstat(child))!=meta(st):os.close(child);raise Stop('HOST_COMPONENT_CHANGED')
     os.close(fd);fd=child;prefix.append(part)
    else:
     if not stat.S_ISREG(st.st_mode) or st.st_mode&(stat.S_ISUID|stat.S_ISGID):raise Stop('HOST_TERMINAL_TYPE')
     if st.st_size>cap:raise Stop('HOST_BINDING_BYTE_CAP')
     leaf=os.open(part,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=fd)
     try:
      if meta(os.fstat(leaf))!=meta(st):raise Stop('HOST_TERMINAL_CHANGED')
      chunks=[];total=0
      while True:
       host_tick(deadline);b=os.read(leaf,65536)
       if not b:break
       total+=len(b)
       if total>st.st_size or total>cap:raise Stop('HOST_BINDING_BYTE_CAP')
       chunks.append(b)
      if total!=st.st_size or meta(os.fstat(leaf))!=meta(st) or meta(os.stat(part,dir_fd=fd,follow_symlinks=False))!=meta(st):raise Stop('HOST_TERMINAL_CHANGED')
      data=b''.join(chunks)
     finally:os.close(leaf)
     return {'canonical':path,'data':data,'metadata':meta(st),'aliases':[{'path':e['path'],'target':e['target'],'metadata':e['metadata']} for e in events if e['type']==stat.S_IFLNK]},events
  finally:os.close(fd)
  if not restart:raise Stop('HOST_ALIAS_PATH')
def authenticated_row(rootfd,logical,deadline,cap=MAX_ELF):
 first,events=capture(rootfd,logical,deadline,cap);second,again=capture(rootfd,logical,deadline,cap)
 if first!=second or events!=again:raise Stop('HOST_BINDING_CHANGED')
 return first,{'logical':logical,'canonical':first['canonical'],'sha256':hashlib.sha256(first['data']).hexdigest(),'metadata':first['metadata'],'components':events}
"""Closed finite policy object. Static consistency only, not actual runtime loader prediction."""
POLICY={
 'status':'SOURCE_CANDIDATE_NOT_APPROVED',
 'tier':'THIS_JOB_RUNNER_IMAGE_AND_OS_PROCEDURAL_TRUST_NOT_PROVENANCE',
 'start':'EXACT_SEALED_CLANG21_AND_LLD_THIS_JOB',
 'environment':{'allow':['PATH=/usr/bin:/bin','LC_ALL=C','HOME=PRIVATE','TMPDIR=PRIVATE'],'reject':['LD_LIBRARY_PATH','LD_PRELOAD','LD_AUDIT','LD_DEBUG','LD_HWCAP_MASK','GLIBC_TUNABLES','GCONV_PATH','LIBRARY_PATH','COMPILER_PATH','GCC_EXEC_PREFIX','CPATH','C_INCLUDE_PATH','CPLUS_INCLUDE_PATH']},
 'cache':'NOT_PARSED_OR_PREDICTED; CURRENT_JOB_OS_CACHE_METADATA_HASH_BINDING_REQUIRED_IF_PRESENT',
 'preload_config':'REJECT_NONEMPTY_ETC_LD_SO_PRELOAD; BIND_ABSENCE_OR_EMPTY_CURRENT_JOB',
 'hwcaps':'NOT_PREDICTED; CPU_AUXV_AND_OS_LOADER_VARIANT_SELECTION_REMAIN_RUNTIME_TRUST_LIMIT',
 'rpath':'REJECT_ALL_INHERITANCE_UNSUPPORTED',
 'runpath':'DIRECT_ONLY_EXPLICIT_ORIGIN_AND_DECLARED_ROOTS; AMBIGUOUS_STOP',
 'versions':'DT_NEEDED_IDENTITY_ONLY; SYMBOL_VERSION_AND_ABI_COMPATIBILITY_UNPROVEN',
 'static_result':'CONSISTENCY_GATE_NOT_NECESSARY_OR_SUFFICIENT_LOADER_PROOF',
 'failure':'REAL_LOADER_FAILURE_REMAINS_POSSIBLE; NO_FALLBACK_OR_WIDENING',
 'execution_authority':False,
}
"""Finite static-phase stop reporting. Never return exception text or filesystem paths."""
HOST_REASONS=frozenset({
 'ELF_SIZE','ELF_BOUNDS','ELF_FORMAT','ELF_HEADER','ELF_SEGMENT','ELF_DUP_SEGMENT','ELF_STRING','HOST_INTERPRETER','DYNAMIC_CAP','NEEDED_CAP','DYNAMIC_DUPLICATE','NODEFLIB_UNSUPPORTED','DYNAMIC_UNTERMINATED','STRTAB','STRTAB_MAPPING','NEEDED_NAME','HOST_PATH','HOST_START_SET','HOST_ROW','HOST_ALIAS','HOST_SIZE','HOST_CHANGED','RPATH_INHERITANCE_UNSUPPORTED','HOST_RUNPATH','HOST_MISSING','HOST_AMBIGUOUS','HOST_OUTSIDE_ROOTS','HOST_NODE_CAP','HOST_BYTE_CAP','HOST_TOOL_INTERPRETER_REQUIRED','HOST_EDGE_CAP',
 'HOST_BINDING_DEADLINE','HOST_ALIAS_PATH','HOST_ALIAS_ESCAPE','HOST_ALIAS_CHANGED','HOST_ALIAS_CAP','HOST_ALIAS_CYCLE','HOST_COMPONENT_CHANGED','HOST_TERMINAL_TYPE','HOST_BINDING_BYTE_CAP','HOST_TERMINAL_CHANGED','HOST_BINDING_CHANGED',
 'STATIC_DEADLINE_RESERVE','HOST_ROOT_CHANGED','HOST_LOOKUP_CAP','HOST_ABSENCE_CHANGED','HOST_CONFIG_CAP','HOST_CONFIG_CHANGED','HOST_PRELOAD','SEALED_TOOL_BINDING'})
PHASES=frozenset({'ENTER','CONFIG_PRE','TOOLS','CLOSURE','CONFIG_POST','POST_BINDINGS','COMPLETE'})
class StaticPhaseStop(Stop):
 def __init__(self,error,phase,session=None):
  reason=error.args[0] if isinstance(error,Stop) and len(error.args)==1 and error.args[0] in HOST_REASONS else 'STATIC_UNCLASSIFIED_STOP'
  super().__init__(reason)
  self.checkpoint={'status':'STATIC_STOP_PARTIAL_NOT_QUALIFIED','phase':phase if phase in PHASES else 'ENTER','reason':reason}
  if isinstance(error,HostPathStop) and error.path_category.get('context') in PATH_CONTEXTS and error.path_category.get('category') in PATH_CATEGORIES:
   self.checkpoint['path_category']=dict(error.path_category)
  if session is not None:
   self.checkpoint.update({'lookup_attempts':session.attempts,'unique_requested_paths':len(session.records),'unique_observed_bytes':session.bytes,'phase_resources':list(session.phases)})
"""Finite SOURCE candidate. Entry reads occur only under future reviewed execution authority."""
import os,stat,time,resource,hashlib,json,math
SNAP='/ldoa-qualification/ndk29-snapshot/'
CLEAN_ENV={'PATH':'/usr/bin:/bin','LC_ALL':'C'}
CLEANUP_RESERVE=30 # proposed seconds inside same1200 ceiling, unmeasured, no assurance
CONFIG_CAP=4*1024**2
class Session:
 def __init__(self,rootfd,deadline,clock=time.monotonic):
  self.rootfd=rootfd;self.deadline=deadline;self.clock=clock;self.start=clock();self.records={};self.bytes=0;self.attempts=0
  self.identity=meta(os.fstat(rootfd));self.phases=[]
 def check(self,phase):
  now=self.clock();remaining=self.deadline-now
  if not math.isfinite(remaining) or remaining>1200 or remaining<=CLEANUP_RESERVE:raise Stop('STATIC_DEADLINE_RESERVE')
  if meta(os.fstat(self.rootfd))!=self.identity:raise Stop('HOST_ROOT_CHANGED')
  self.phases.append({'phase':phase,'elapsed':now-self.start,'remaining':remaining,'cleanup_reserve':CLEANUP_RESERVE,'maxrss_KiB':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,'free_bytes':os.fstatvfs(self.rootfd).f_bavail*os.fstatvfs(self.rootfd).f_frsize})
 def lookup(self,path,cap=None):
  self.check('DESCRIPTOR_LOOKUP');self.attempts+=1
  if self.attempts>MAX_EDGES:raise Stop('HOST_LOOKUP_CAP')
  if path not in self.records and len(self.records)>=MAX_NODES:raise Stop('HOST_NODE_CAP')
  try:row,binding=authenticated_row(self.rootfd,path,self.deadline-CLEANUP_RESERVE,cap if cap is not None else MAX_ELF)
  except FileNotFoundError:
   # Missing observations rechecked postphase under same root identity; never qualifies loader fallback.
   try:authenticated_row(self.rootfd,path,self.deadline-CLEANUP_RESERVE,cap if cap is not None else MAX_ELF)
   except FileNotFoundError:
    self.records.setdefault(path,None);return None
   raise Stop('HOST_ABSENCE_CHANGED')
  if cap is not None and len(row['data'])>cap:raise Stop('HOST_CONFIG_CAP')
  value={'row':binding,'bytes':len(row['data'])}
  if path in self.records:
   if self.records[path]!=value:raise Stop('HOST_CHANGED')
  else:
   if self.bytes+len(row['data'])>HOST_MAX_TOTAL:raise Stop('HOST_BYTE_CAP')
   self.bytes+=len(row['data']);self.records[path]=value
  return row
 def config(self):
  # Exactconfigfiles and exactconf.d membership. No cache decoding/hwcaps enumeration.
  names=['/etc/ld.so.cache','/etc/ld.so.conf','/etc/ld.so.preload']
  directory='/etc/ld.so.conf.d';fd=None
  try:
   # Directory must be real nofollow under rootfd via fixed etc component.
   etc=os.open('etc',os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=self.rootfd)
   try:fd=os.open('ld.so.conf.d',os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=etc)
   finally:os.close(etc)
   before=meta(os.fstat(fd));members=sorted(os.listdir(fd))
   if len(members)>MAX_NODES or any('/' in n or n in ('.','..') for n in members):raise Stop('HOST_CONFIG_CAP')
   names +=[directory+'/'+n for n in members]
   if meta(os.fstat(fd))!=before:raise Stop('HOST_CONFIG_CHANGED')
  except FileNotFoundError:members=None;before=None
  finally:
   if fd is not None:os.close(fd)
  bindings={}
  for path in names:
   row=self.lookup(path,CONFIG_CAP)
   if path=='/etc/ld.so.preload' and row is not None and row['data'].strip():raise Stop('HOST_PRELOAD')
   bindings[path]=self.records[path]
  return {'files':bindings,'conf_d_members':members,'conf_d_metadata':before}
 def finish(self):
  saved=dict(self.records)
  for path in saved:self.lookup(path,CONFIG_CAP if path.startswith('/etc/') else None)
  if self.records!=saved:raise Stop('HOST_CHANGED')
  self.check('POST_BINDINGS')
  return {'bindings':saved,'unique_observed_bytes':self.bytes,'lookup_attempts':self.attempts,'phases':self.phases}
def integrated_static_phase(snapshotfd,rows,image,deadline):
 # Called only AFTER snapshot verify, BEFORE snapshot cleanup. No tool subprocess.
 root=None;session=None;phase='ENTER'
 try:
  root=os.open('/',os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
  if meta(os.fstat(root))!=meta(os.stat('/',follow_symlinks=False)):raise Stop('HOST_ROOT_CHANGED')
  session=Session(root,deadline);session.check('STATIC_BEGIN');phase='CONFIG_PRE';pre=session.config();phase='TOOLS'
  tools={r['logical']:r for r in rows if r['scope']=='TOOL'};prefix='toolchains/llvm/prebuilt/linux-x86_64/bin/'
  if set(tools)!={prefix+'clang',prefix+'ld.lld'}:raise Stop('HOST_START_SET')
  starts={'clang21':SNAP+tools[prefix+'clang']['canonical'],'lld':SNAP+tools[prefix+'ld.lld']['canonical']}
  for logical,r in tools.items():
   captured=session.lookup(SNAP+logical)
   if captured is None or captured['canonical']!=SNAP+r['canonical'] or hashlib.sha256(captured['data']).hexdigest()!=r['sha256']:raise Stop('SEALED_TOOL_BINDING')
  # No ambientenv copied into anyconsumer. This candidate has NO consumer.
  phase='CLOSURE';result=closure(starts,session.lookup);phase='CONFIG_POST';post=session.config()
  if pre!=post:raise Stop('HOST_CONFIG_CHANGED')
  phase='POST_BINDINGS';evidence=session.finish();phase='COMPLETE'
  return {'status':'STATIC_CONSISTENCY_ONLY_NOT_APPROVED','policy':POLICY,'environment_for_no_consumer':CLEAN_ENV,'closure':result,'host_config':pre,'evidence':evidence,'job':image,'compiler_execution_authorized':False,'runtime_loader_failure_possible':True}
 except Exception as error:raise StaticPhaseStop(error,phase,session) from None
 finally:
  if root is not None:os.close(root)
# Standalone helper is generated by concatenating snapshot_contract.py and this source.
import sys,errno
PARENT='/ldoa-qualification'
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
def snapshot_only(source,parentfd,image,deadline,uid=0,emit=None,static_phase=None):
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
  if static_phase is not None:
   static_result=static_phase(fd,rows,image,deadline)
   if len(json.dumps(static_result,sort_keys=True).encode())>MAX_RECEIPT:raise Stop('RECEIPT_CAP')
   result['static_consistency']=static_result
   verify_fd(fd,rows,deadline,uid);root_binding(parentfd,fd,identity)
 except Exception as error:
  result['reason']=safe_reason(error);result['status']='STOP'
  if type(error).__name__=='StaticPhaseStop' and isinstance(error,Stop):
   # The bundled finite wrapper owns this small sanitized checkpoint.
   result['static_checkpoint']=error.checkpoint
 finally:
  result['timing']={'elapsed_total':time.monotonic()-(deadline-1200),'remaining':deadline-time.monotonic(),'cleanup_reserve_proposal':30}
  # Emit receipt BEFORE cleanup and again after. A cleanup failure does not erase the primary reason.
  if emit:
   try:emit({'phase':'BEFORE_CLEANUP','receipt':result})
   except Exception:result['receipt_emit']='FAILED';result['status']='STOP'
  if fd is not None:
   try:cleanup_fd(parentfd,fd,identity);result['cleanup']='ABSENT'
   except Exception as error:result['cleanup']='FAILED';result['cleanup_reason']=safe_reason(error);result['status']='STOP'
   finally:os.close(fd)
  else:result['cleanup']='NOT_CREATED'
  result['timing']['after_cleanup_remaining']=deadline-time.monotonic()
  result['timing']['after_cleanup_elapsed_total']=time.monotonic()-(deadline-1200)
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
   result=snapshot_only(pathlib.Path(NDK),parentfd,image,deadline,static_phase=integrated_static_phase,emit=lambda data:print(json.dumps({**data,'dispatch_binding':dispatch_binding},sort_keys=True),flush=True))
  finally:os.close(parentfd)
 except Exception as error:print(json.dumps({'scope':'SNAPSHOT_PREFLIGHT_STOP','reason':safe_reason(error),'tool_executed':False}),flush=True)
 return 0 if result and result['status']=='SNAPSHOT_ONLY_NOT_APPROVED' and result.get('cleanup')=='ABSENT' else 1
if __name__=='__main__':raise SystemExit(cli())

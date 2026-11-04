"""Pure, bounded clang21/lld host closure candidate. Never reads or executes real files."""
import struct,re,hashlib,json
class Stop(RuntimeError):pass
MAX_ELF=192*1024*1024;MAX_NODES=64;MAX_EDGES=256;MAX_TOTAL=512*1024*1024
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
  if total>MAX_TOTAL:raise Stop('HOST_BYTE_CAP')
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

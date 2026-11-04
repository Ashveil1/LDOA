"""PROPOSED metadata diagnostic only. No file-content reads, hashes or subprocesses."""
import pathlib,stat,time,json,sys,re
ROOT=pathlib.Path('/usr/local/lib/android/sdk/ndk/29.0.14206865')
class Stop(RuntimeError):pass
SAFE=frozenset(['METADATA_DEADLINE','PATH_SCOPE','PATH_TYPE','RESOURCE_AMBIGUOUS','RESOURCE_INVENTORY_CAP','RESOURCE_BYTE_CAP','ANCESTOR_CAP'])
def check(deadline,clock):
 if clock()>=deadline:raise Stop('METADATA_DEADLINE')
def safe_label(p,root):
 try:label=p.relative_to(root).as_posix()
 except ValueError:raise Stop('PATH_SCOPE')
 if not re.fullmatch(r'[A-Za-z0-9_+./-]+',label) or len(label)>512:raise Stop('PATH_SCOPE')
 return label

def admit_header(count,total,size):
 if count>=4096:raise Stop('RESOURCE_INVENTORY_CAP')
 if total+size>64*1024*1024:raise Stop('RESOURCE_BYTE_CAP')

def metadata(root=ROOT,clock=time.monotonic):
 deadline=clock()+120;root=pathlib.Path(root)
 # Only the fixed NDK subtree is followed; fixture roots are supplied only by pure tests.
 realroot=root.resolve(strict=True);header_count=0;header_bytes=0;rows=[]
 result={'scope':'EXISTING_NDK_METADATA_ONLY_NOT_TOOL_APPROVAL','status':'METADATA_ONLY_NOT_APPROVED','tool_executed':False,'compiler_executed':False,'fetch':False,'file_content_read':False,'hashes':False,'rows':rows,'header_count':0,'header_bytes':0,'host_dependencies':'NOT_YET_QUALIFIED'}
 def ancestors(p):
  chain=[p,*p.parents]
  if len(chain)>32:raise Stop('ANCESTOR_CAP')
  out=[]
  for x in chain:
   check(deadline,clock);s=x.stat()
   # Fixed ancestor names only. Never serialize arbitrary paths outside the NDK tree.
   label='NDK_ROOT' if x==realroot else ('NDK/'+safe_label(x,realroot) if x.is_relative_to(realroot) else 'ROOT_ANCESTOR_DEPTH_'+str(len(x.parts)))
   out.append({'label':label,'uid':s.st_uid,'gid':s.st_gid,'mode':format(stat.S_IMODE(s.st_mode),'04o'),'os_owner_guard':s.st_uid==0 and not bool(s.st_mode&(stat.S_IWGRP|stat.S_IWOTH))})
  return out
 def record(p,scope):
  check(deadline,clock);lexical=safe_label(p,root);real=p.resolve(strict=True)
  if not real.is_relative_to(realroot):raise Stop('PATH_SCOPE')
  s=real.stat()
  if not stat.S_ISREG(s.st_mode):raise Stop('PATH_TYPE')
  chain=ancestors(real)
  row={'label':lexical,'canonical_label':safe_label(real,realroot),'scope':scope,'bytes':s.st_size,'uid':s.st_uid,'gid':s.st_gid,'mode':format(stat.S_IMODE(s.st_mode),'04o'),'os_owner_guard':s.st_uid==0 and not bool(s.st_mode&(stat.S_IWGRP|stat.S_IWOTH)),'ancestors':chain}
  rows.append(row)
  return row
 try:
  host=root/'toolchains/llvm/prebuilt/linux-x86_64'
  for p,scope in [(root/'source.properties','NDK_DESCRIPTOR'),(host/'bin/clang','HOST_COMPILER'),(host/'bin/ld.lld','HOST_LINKER')]:
   row=record(p,scope)
   if not row['os_owner_guard'] or any(not a['os_owner_guard'] for a in row['ancestors']):
    result['status']='OWNERSHIP_MISMATCH_METADATA_ONLY';result['first_mismatch']=row['label'];return result,1
  resources=list((host/'lib/clang').glob('*/include'))
  if len(resources)!=1:raise Stop('RESOURCE_AMBIGUOUS')
  # Bounded streaming traversal. Never build an unbounded sorted inventory.
  stack=[resources[0]];visited=set();entries=0
  while stack:
   directory=stack.pop();real=directory.resolve(strict=True)
   if not real.is_relative_to(realroot):raise Stop('PATH_SCOPE')
   if real in visited:raise Stop('PATH_SCOPE')
   visited.add(real)
   for p in directory.iterdir():
    check(deadline,clock);entries+=1
    if entries>8192:raise Stop('RESOURCE_INVENTORY_CAP')
    if p.is_dir():stack.append(p);continue
    if not p.is_file():raise Stop('PATH_TYPE')
    if header_count>=4096:raise Stop('RESOURCE_INVENTORY_CAP')
    s=p.stat();admit_header(header_count,header_bytes,s.st_size)
    row=record(p,'NDK_RESOURCE_HEADER');header_count+=1;header_bytes+=row['bytes']
    result['header_count']=header_count;result['header_bytes']=header_bytes
    if not row['os_owner_guard'] or any(not a['os_owner_guard'] for a in row['ancestors']):
     result['status']='OWNERSHIP_MISMATCH_METADATA_ONLY';result['first_mismatch']=row['label'];return result,1
  check(deadline,clock);return result,0
 except Stop as error:result['status']='METADATA_STOP';result['reason']=error.args[0] if error.args and error.args[0] in SAFE else 'SAFE_METADATA_STOP'
 except (OSError,RuntimeError,ValueError):result['status']='METADATA_STOP';result['reason']='METADATA_UNAVAILABLE'
 except Exception:result['status']='METADATA_STOP';result['reason']='UNEXPECTED_METADATA_FAILURE'
 return result,1

def cli(destination='ndk-metadata-receipt.json',measurement=metadata,stream=None):
 try:result,code=measurement()
 except Exception:result={'scope':'NDK_METADATA_STOP','reason':'METADATA_UNAVAILABLE','tool_executed':False};code=1
 text=json.dumps(result,sort_keys=True)
 if len(text.encode())>4*1024*1024:result={'scope':'NDK_METADATA_STOP','reason':'RECEIPT_BYTE_CAP','tool_executed':False};text=json.dumps(result,sort_keys=True);code=1
 try:pathlib.Path(destination).write_text(text+'\n')
 except OSError:text=json.dumps({'scope':'NDK_METADATA_STOP','reason':'RECEIPT_WRITE_FAILED','tool_executed':False});code=1
 print(text,file=stream or sys.stdout);return code
if __name__=='__main__':raise SystemExit(cli())

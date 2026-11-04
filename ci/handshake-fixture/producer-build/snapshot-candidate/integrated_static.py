"""Finite SOURCE candidate. Entry reads occur only under future reviewed execution authority."""
import os,stat,time,resource,hashlib,json,math
from host_contract import Stop,closure,MAX_NODES,MAX_EDGES,MAX_TOTAL,MAX_ELF
from descriptor_binding import authenticated_row,meta
from resolution_policy import POLICY
from static_diagnostic import StaticPhaseStop
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
   if self.bytes+len(row['data'])>MAX_TOTAL:raise Stop('HOST_BYTE_CAP')
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

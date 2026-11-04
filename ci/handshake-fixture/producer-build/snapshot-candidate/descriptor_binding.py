"""SOURCE ONLY descriptor-bound host rows. Tests use synthetic roots only. No executable tools."""
import os,stat,pathlib,hashlib,time
from host_contract import Stop,MAX_ELF
FLAGS=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
def meta(st):return {'dev':st.st_dev,'ino':st.st_ino,'uid':st.st_uid,'gid':st.st_gid,'mode':stat.S_IMODE(st.st_mode),'size':st.st_size,'mtime_ns':st.st_mtime_ns,'ctime_ns':st.st_ctime_ns}
def tick(deadline):
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
    tick(deadline);st=os.stat(part,dir_fd=fd,follow_symlinks=False);path='/'+ '/'.join(prefix+[part]);event={'path':path,'metadata':meta(st),'type':stat.S_IFMT(st.st_mode)}
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
       tick(deadline);b=os.read(leaf,65536)
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

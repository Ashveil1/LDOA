import os,stat,hashlib,json
FLAGS=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
EXPECTED_DIGEST='788658cc92b432df093316e8b09ffae8320c81c3ec2d14e4dec03bd5386ccb40'
def fail():raise RuntimeError('CLEANUP_BOUNDARY')
def clear(fd):
 os.fchmod(fd,0o700)
 for name in os.listdir(fd):
  if '/' in name or name in ['.','..']:fail()
  st=os.stat(name,dir_fd=fd,follow_symlinks=False)
  if st.st_uid!=0:fail()
  if stat.S_ISDIR(st.st_mode):
   child=os.open(name,FLAGS,dir_fd=fd)
   try:
    now=os.fstat(child)
    if (now.st_dev,now.st_ino)!=(st.st_dev,st.st_ino):fail()
    clear(child)
   finally:os.close(child)
   os.rmdir(name,dir_fd=fd)
  else:os.unlink(name,dir_fd=fd)
root=os.open('/',FLAGS)
try:
 for component in ['']:
  if component:
   child=os.open(component,FLAGS,dir_fd=root);os.close(root);root=child
  st=os.fstat(root)
  if st.st_uid!=0 or stat.S_IMODE(st.st_mode)&0o022:fail()
 try:parent=os.open('ldoa-qualification',FLAGS,dir_fd=root)
 except FileNotFoundError:print('QUALIFICATION_INSTALL_ABSENT');raise SystemExit(0)
 try:
  st=os.fstat(parent);named=os.stat('ldoa-qualification',dir_fd=root,follow_symlinks=False)
  if st.st_uid!=0 or stat.S_IMODE(st.st_mode)!=0o700 or (st.st_dev,st.st_ino)!=(named.st_dev,named.st_ino):fail()
  # Do not delete unknown installation contents. Hash validates exact reviewed helper, not provenance of tools.
  names=set(os.listdir(parent))
  if not names.issubset({'ndk29_snapshot.py','context.json','deadline','ndk29-snapshot'}):fail()
  # Require authenticated helper before treating installation as this workflow's owned root.
  f=os.open('ndk29_snapshot.py',os.O_RDONLY|os.O_NOFOLLOW,dir_fd=parent)
  try:
   st=os.fstat(f);h=hashlib.sha256()
   if not stat.S_ISREG(st.st_mode) or st.st_uid!=0 or stat.S_IMODE(st.st_mode)!=0o555:fail()
   while True:
    block=os.read(f,65536)
    if not block:break
    h.update(block)
   if h.hexdigest()!=EXPECTED_DIGEST:fail()
  finally:os.close(f)
  if 'ndk29-snapshot' in names:
   fd=os.open('ndk29-snapshot',FLAGS,dir_fd=parent)
   try:
    st=os.fstat(fd);named=os.stat('ndk29-snapshot',dir_fd=parent,follow_symlinks=False)
    if st.st_uid!=0 or (st.st_dev,st.st_ino)!=(named.st_dev,named.st_ino):fail()
    clear(fd)
   finally:os.close(fd)
   os.rmdir('ndk29-snapshot',dir_fd=parent)
  for name in ['ndk29_snapshot.py','context.json','deadline']:
   try:st=os.stat(name,dir_fd=parent,follow_symlinks=False)
   except FileNotFoundError:continue
   if not stat.S_ISREG(st.st_mode) or st.st_uid!=0:fail()
   os.unlink(name,dir_fd=parent)
 finally:os.close(parent)
 os.rmdir('ldoa-qualification',dir_fd=root);print('QUALIFICATION_INSTALL_CLEANUP_ABSENT')
finally:os.close(root)

"""Pure input/depfile/trace gates for one fixed producer build, not a general build tool."""
import pathlib,hashlib,re,json
class Stop(RuntimeError):pass
def digest(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def depfile(text):
 # GNU make escaping emitted by clang: continuation, whitespace, #, $, backslash.
 text=text.replace('\\\r\n','').replace('\\\n','');tokens=[];word='';escaped=False;started=False;i=0
 while i<len(text):
  ch=text[i];i+=1
  if escaped:word+=ch;escaped=False;continue
  if ch=='\\':escaped=True;continue
  if not started:
   if ch==':':started=True;word=''
   continue
  if ch=='#':raise Stop('DEPFILE_UNESCAPED_COMMENT')
  if ch=='$':
   if i>=len(text) or text[i]!='$':raise Stop('DEPFILE_VARIABLE')
   i+=1;word+='$';continue
  if ch.isspace():
   if word:tokens.append(word);word=''
  else:word+=ch
 if escaped or not started:raise Stop('DEPFILE_SYNTAX')
 if word:tokens.append(word)
 if not tokens:raise Stop('DEPFILE_EMPTY')
 return list(dict.fromkeys(tokens))
def approved_file(path,allowed):
 p=pathlib.Path(path)
 if not p.is_absolute():raise Stop('INPUT_NOT_ABSOLUTE')
 try:real=str(p.resolve(strict=True))
 except OSError:raise Stop('INPUT_ABSENT')
 row=allowed.get(real)
 if row is None or digest(real)!=row['sha256']:raise Stop('UNAPPROVED_OR_CHANGED_INPUT')
 return {'path':real,'sha256':row['sha256'],'bytes':pathlib.Path(real).stat().st_size,'scope':row['scope']}
def trace_inputs(text,allowed,object_path):
 result=[]
 for line in text.splitlines():
  line=line.strip()
  if not line:continue
  # lld -t may print archive(member); bind whole archive, retain member label.
  match=re.fullmatch(r'(.+?)(?:\(([^()]+)\))?',line)
  if not match:raise Stop('LINK_TRACE_SYNTAX')
  name,member=match.groups()
  if name==str(object_path):result.append({'path':name,'scope':'GENERATED_OBJECT','sha256':digest(name),'archive_member':None});continue
  row=approved_file(name,allowed);row['archive_member']=member;result.append(row)
 if not result:raise Stop('LINK_TRACE_EMPTY')
 return result
def bind_manifest(sysroot,manifest):
 # Share exactly the staging logical-name rules, not broad in-root tolerance.
 from stage_manifest import normalized,canonical,target,Stop as StageStop
 allowed={};root=pathlib.Path(sysroot).resolve(strict=True);records={};links={}
 try:
  for row in manifest['files']:
   name=canonical(normalized(row['path']))
   if name in records:raise Stop('MEMBER_PATH_DUPLICATE')
   records[name]=row
   if row['type']=='symlink':links[name]=target(name,row['target'])
   elif row['type']!='file':raise Stop('MEMBER_TYPE')
 except StageStop:raise Stop('MEMBER_LOGICAL_PATH')
 final={}
 for name,link in links.items():
  seen={name}
  while link in links:
   if link in seen:raise Stop('STAGED_LINK_CYCLE')
   seen.add(link);link=links[link]
  if link not in records or records[link]['type']!='file':raise Stop('STAGED_LINK_TARGET_MISSING')
  final[name]=link
 for name,row in records.items():
  p=root/name
  if row['type']=='file':
   if p.is_symlink() or not p.is_file() or not p.resolve().is_relative_to(root) or digest(p)!=row['sha256'] or p.stat().st_size!=row['bytes']:raise Stop('MEMBER_BYTES')
   allowed[str(p.resolve())]={'sha256':row['sha256'],'scope':'SELECTED_SYSROOT'}
  else:
   if not p.is_symlink():raise Stop('STAGED_LINK')
   raw=p.readlink().as_posix()
   # The stage always writes relative links. Require exact canonical immediate logical edge.
   try:actual=target(name,raw)
   except StageStop:raise Stop('STAGED_LINK')
   if raw.startswith('/') or actual!=links[name]:raise Stop('STAGED_LINK')
   try:real=p.resolve(strict=True)
   except (OSError,RuntimeError):raise Stop('STAGED_LINK')
   expected=root/final[name];selected=records[final[name]]
   if not real.is_relative_to(root) or real!=expected or digest(real)!=selected['sha256'] or real.stat().st_size!=selected['bytes']:raise Stop('STAGED_LINK_MEMBER_PIN')
 # Only two prescribed usrmerge aliases. Unknown same-target aliases are rejected.
 aliases={'lib':'usr/lib','lib64':'usr/lib64'}
 for p in root.rglob('*'):
  name=p.relative_to(root).as_posix()
  if p.is_symlink():
   try:real=p.resolve(strict=True)
   except (OSError,RuntimeError):raise Stop('STAGED_LINK')
   if not real.is_relative_to(root):raise Stop('STAGED_LINK_ESCAPE')
   if name in aliases:
    if p.readlink().as_posix()!=aliases[name]:raise Stop('STAGED_ALIAS')
   elif name not in links:raise Stop('UNRECORDED_SYSROOT_LINK')
  elif p.is_file() and str(p.resolve()) not in allowed:raise Stop('UNRECORDED_SYSROOT_FILE')
 return allowed

def command_plan(clang,linker,resource,sysroot,source,out):
 s=pathlib.Path(sysroot);o=pathlib.Path(out);gcc=s/'usr/lib/gcc/x86_64-linux-gnu/13';lib=s/'usr/lib/x86_64-linux-gnu'
 flags=[str(clang),'--target=x86_64-linux-gnu','--sysroot='+str(s),'-nostdinc','-isystem',str(pathlib.Path(resource)/'include'),'-isystem',str(s/'usr/include/x86_64-linux-gnu'),'-isystem',str(s/'usr/include'),'-I'+str(s/'usr/include/glib-2.0'),'-I'+str(lib/'glib-2.0/include'),'-O2','-g0','-pthread','-fno-pie','-Wall','-Wextra','-Werror']
 pre=flags+['-M','-MF',str(o/'producer.d'),'-MT','producer.o',str(source)]
 compile=flags+['-c',str(source),'-o',str(o/'producer.o')]
 # No clang driver discovery, no host GCC, no default startup search or implicit compiler runtime.
 link=[str(linker),'-m','elf_x86_64','--sysroot='+str(s),'-dynamic-linker','/lib64/ld-linux-x86-64.so.2','--trace','--fatal-warnings','-o',str(o/'producer'),str(lib/'crt1.o'),str(lib/'crti.o'),str(gcc/'crtbegin.o'),str(o/'producer.o'),'-L'+str(gcc),'-L'+str(lib),'--start-group','-lgio-2.0','-lgobject-2.0','-lglib-2.0','-lc','-lgcc','-lgcc_s','--end-group',str(gcc/'crtend.o'),str(lib/'crtn.o')]
 return pre,compile,link

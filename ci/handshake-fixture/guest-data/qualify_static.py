"""Read ELF as data using pinned host readelf, never ldd/loader/guest execution."""
import pathlib,subprocess,re,time,tempfile,resource
from guest_data_only import Stop,link_target
ALIASES={'lib':'usr/lib','lib64':'usr/lib64'}
def canonical(name):
 first,sep,tail=name.partition('/')
 return ALIASES.get(first,first)+(sep+tail if sep else '')
def resolve(name,records):
 seen=set();name=canonical(name)
 while name in records and records[name]['type']=='symlink':
  if name in seen:raise Stop('ELF_LINK_CYCLE')
  seen.add(name);name=canonical(records[name]['normalized_target'])
 if name not in records or records[name]['type']!='file':raise Stop('ELF_MEMBER_MISSING')
 return name
def dynamic_paths(text):
 return {kind:re.findall(r'\('+kind+r'\).*?\[(.*?)\]',text) for kind in ['RPATH','RUNPATH']}
def needed_target(name,soname,paths,mapped):
 # Model direct dependency lookup only: explicit supported ORIGIN tags then fixed sysroot default.
 # No assumed sibling fallback, LD_LIBRARY_PATH, cache, cwd, host or inherited RPATH model.
 tags=paths['RUNPATH'] if paths['RUNPATH'] else paths['RPATH']
 dirs=[]
 for tag in tags:
  for part in tag.split(':'):
   if part in ('$ORIGIN','${ORIGIN}'):
    directory=str(pathlib.PurePosixPath(name).parent)
   else:raise Stop('UNSUPPORTED_DYNAMIC_SEARCH_PATH')
   if directory not in dirs:dirs.append(directory)
 dirs.append('usr/lib/x86_64-linux-gnu')
 for directory in dirs:
  try:return resolve(directory+'/'+soname,mapped),dirs
  except Stop:pass
 raise Stop('DSO_TARGET_MISSING')
def script_refs(text,name,mapped):
 # Small reject-by-default grammar, not a general linker interpreter.
 text=re.sub(r'/\*.*?\*/','',text,flags=re.S)
 tokens=re.findall(r'[A-Za-z0-9_./+${}=-]+|[(),;]|\S',text);index=0;refs=[]
 def group():
  nonlocal index
  if index>=len(tokens) or tokens[index]!='(':raise Stop('SCRIPT_GRAMMAR')
  index+=1
  while index<len(tokens) and tokens[index]!=')':
   token=tokens[index];index+=1
   if token in [',',';']:continue
   if token=='AS_NEEDED':group();continue
   if not re.fullmatch(r'(?:/[A-Za-z0-9_./+-]+|[A-Za-z0-9_.+-]+)',token):raise Stop('SCRIPT_REFERENCE_UNSUPPORTED')
   if token=='-lgcc':candidates=['usr/lib/gcc/x86_64-linux-gnu/13/libgcc.a']
   elif token.startswith('-'):raise Stop('SCRIPT_LIBRARY_FLAG_UNSUPPORTED')
   else:candidates=[token.lstrip('/')] if token.startswith('/') else [str(pathlib.PurePosixPath(name).parent/token),'usr/lib/x86_64-linux-gnu/'+token]
   if '..' in pathlib.PurePosixPath(token).parts:raise Stop('SCRIPT_REFERENCE_ESCAPE')
   resolved=None
   for candidate in candidates:
    try:resolved=resolve(candidate,mapped);break
    except Stop:pass
   if resolved is None:raise Stop('SCRIPT_REFERENCE_MISSING')
   refs.append({'reference':token,'resolved':resolved})
  if index>=len(tokens):raise Stop('SCRIPT_GRAMMAR')
  index+=1
 while index<len(tokens):
  token=tokens[index];index+=1
  if token in [',',';']:continue
  if token in ['GROUP','INPUT']:group()
  elif token=='OUTPUT_FORMAT':
   if tokens[index:index+3]!=['(','elf64-x86-64',')']:raise Stop('SCRIPT_OUTPUT_FORMAT')
   index+=3
  else:raise Stop('SCRIPT_DIRECTIVE_UNSUPPORTED')
 if not refs:raise Stop('SCRIPT_NO_REFERENCES')
 return refs
def qualify(records,stage,deadline):
 # Reviewed usrmerge aliasing only; no physical host symlink traversal.
 mapped={}
 for name,row in records.items():
  key=canonical(name)
  if key in mapped:raise Stop('USRMERGE_COLLISION')
  mapped[key]=row
 elf={};scripts=[]
 for key,row in mapped.items():
  if row['type']!='file':continue
  p=stage/row['path']
  with p.open('rb') as f:magic=f.read(4)
  if magic!=b'\x7fELF':
   if key.endswith('.so'):
    text=p.read_text(errors='replace')
    # Review references, no linker execution or claim the script is closed.
    script={'path':key,'sha256':row['sha256'],'status':'SCRIPT_UNQUALIFIED','references':[]}
    try:script['references']=script_refs(text,key,mapped);script['status']='SCRIPT_REFERENCES_BOUND_NOT_LINK_PROOF'
    except Stop as error:script['failure_code']=error.args[0]
    scripts.append(script)
   continue
  remaining=deadline-time.monotonic()
  if remaining<=0:raise Stop('DEADLINE')
  def output_limit():resource.setrlimit(resource.RLIMIT_FSIZE,(2*1024*1024,2*1024*1024))
  with tempfile.TemporaryFile() as output:
   result=subprocess.run(['/usr/bin/readelf','-h','-d','-l',str(p)],stdout=output,stderr=subprocess.DEVNULL,timeout=remaining,env={'PATH':'/usr/bin:/bin','LC_ALL':'C'},preexec_fn=output_limit)
   if result.returncode:raise Stop('READELF_PARSE')
   output.seek(0);text=output.read(2*1024*1024+1).decode('utf-8','replace')
  if len(text)>2*1024*1024:raise Stop('READELF_OUTPUT_CAP')
  if 'Advanced Micro Devices X86-64' not in text:raise Stop('ELF_ARCH')
  needed=re.findall(r'\(NEEDED\).*?\[(.*?)\]',text);interpreter=re.search(r'Requesting program interpreter: (.*?)\]',text)
  elf[key]={'path':key,'package':row['package'],'sha256':row['sha256'],'needed':needed,'dynamic_paths':dynamic_paths(text),'interpreter':interpreter.group(1) if interpreter else None}
 edges=[];missing=[]
 for name,row in elf.items():
  for soname in row['needed']:
   if not re.fullmatch(r'[A-Za-z0-9_.+-]+',soname):raise Stop('SONAME')
   try:target,search=needed_target(name,soname,row['dynamic_paths'],mapped)
   except Stop as error:missing.append({'from':name,'needed':soname,'reason':error.args[0]});continue
   if target not in elf:missing.append({'from':name,'needed':soname,'reason':'TARGET_NOT_ELF'});continue
   edges.append({'from':name,'needed':soname,'resolved':target,'search_dirs':search})
  if row['interpreter']:
   try:target=resolve(row['interpreter'].lstrip('/'),mapped)
   except Stop:missing.append({'from':name,'reason':'INTERPRETER_MISSING'});continue
   edges.append({'from':name,'interpreter':row['interpreter'],'resolved':target})
 for script in scripts:
  if script['status']=='SCRIPT_UNQUALIFIED':missing.append({'from':script['path'],'reason':script.get('failure_code','SCRIPT_UNQUALIFIED')})
 return {'scope':'EXISTING_MEMBER_STATIC_ELF_ONLY_NO_PRODUCER','elf':list(elf.values()),'edges':edges,'missing':missing,'linker_scripts':scripts,'producer_built':False,'runtime_asset_ready':False,'aliases':ALIASES}

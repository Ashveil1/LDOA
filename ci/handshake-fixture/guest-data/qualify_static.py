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
    scripts.append({'path':key,'sha256':row['sha256'],'status':'LINKER_SCRIPT_REVIEW_REQUIRED','absolute_refs':re.findall(r'/[A-Za-z0-9_./+-]+',text)})
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
  elf[key]={'path':key,'package':row['package'],'sha256':row['sha256'],'needed':needed,'interpreter':interpreter.group(1) if interpreter else None}
 edges=[];missing=[]
 for name,row in elf.items():
  for soname in row['needed']:
   if not re.fullmatch(r'[A-Za-z0-9_.+-]+',soname):raise Stop('SONAME')
   try:target=resolve('usr/lib/x86_64-linux-gnu/'+soname,mapped)
   except Stop:missing.append({'from':name,'needed':soname,'reason':'DSO_TARGET_MISSING'});continue
   if target not in elf:missing.append({'from':name,'needed':soname,'reason':'TARGET_NOT_ELF'});continue
   edges.append({'from':name,'needed':soname,'resolved':target})
  if row['interpreter']:
   try:target=resolve(row['interpreter'].lstrip('/'),mapped)
   except Stop:missing.append({'from':name,'reason':'INTERPRETER_MISSING'});continue
   edges.append({'from':name,'interpreter':row['interpreter'],'resolved':target})
 return {'scope':'EXISTING_MEMBER_STATIC_ELF_ONLY_NO_PRODUCER','elf':list(elf.values()),'edges':edges,'missing':missing,'linker_scripts':scripts,'producer_built':False,'runtime_asset_ready':False,'aliases':ALIASES}

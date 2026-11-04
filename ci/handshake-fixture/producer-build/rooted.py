"""Rooted static producer closure, never execute ELF or package runtime assets."""
import pathlib,re,sys,time
from contracts import Stop,digest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'guest-data'))
from qualify_static import dynamic_paths,needed_target,resolve,canonical

def rooted_closure(producer,sysroot,member,readelf,deadline,read):
 records={canonical(r['path']):r for r in member['files']};elfroot=pathlib.Path(sysroot).resolve();queue=[('producer',pathlib.Path(producer).resolve())];seen={};edges=[]
 while queue:
  name,path=queue.pop()
  if name in seen:continue
  text=read([str(readelf),'-h','-l','-d','--version-info',str(path)],deadline)
  if 'Advanced Micro Devices X86-64' not in text or 'ELF64' not in text:raise Stop('PRODUCER_ELF_ARCH')
  interpreter=re.search(r'Requesting program interpreter: (.*?)\]',text)
  if name=='producer' and (not interpreter or interpreter.group(1)!='/lib64/ld-linux-x86-64.so.2'):raise Stop('PRODUCER_INTERPRETER')
  needed=re.findall(r'\(NEEDED\).*?\[(.*?)\]',text);paths=dynamic_paths(text)
  seen[name]={'path':name,'sha256':digest(path),'bytes':path.stat().st_size,'needed':needed,'dynamic_paths':paths,'version_info':text[text.find('Version symbols section'):] if 'Version symbols section' in text else 'NO_VERSION_SYMBOLS_REPORTED'}
  for soname in needed:
   if not re.fullmatch(r'[A-Za-z0-9_.+-]+',soname):raise Stop('SONAME')
   target,_=needed_target(name,soname,paths,records);row=records[target];p=elfroot/row['path']
   if not p.resolve().is_relative_to(elfroot) or digest(p)!=row.get('sha256'):raise Stop('ROOTED_MEMBER_PIN')
   edges.append({'from':name,'needed':soname,'resolved':target});queue.append((target,p))
  if interpreter:
   target=resolve(interpreter.group(1).lstrip('/'),records);row=records[target];p=elfroot/row['path']
   if not p.resolve().is_relative_to(elfroot) or digest(p)!=row.get('sha256'):raise Stop('ROOTED_LOADER_PIN')
   edges.append({'from':name,'interpreter':interpreter.group(1),'resolved':target});queue.append((target,p))
 return {'scope':'PRODUCER_ROOTED_STATIC_ELF_NOT_RUNTIME','files':list(seen.values()),'edges':edges,'guest_executed':False,'runtime_asset_ready':False}

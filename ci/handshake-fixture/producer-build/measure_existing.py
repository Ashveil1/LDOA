"""PROPOSED runner measurement: read/hash existing files only, no executable invocation."""
import pathlib,hashlib,json,os,time,stat,sys
ROOT=pathlib.Path('/usr/local/lib/android/sdk/ndk/29.0.14206865')
class Stop(RuntimeError):pass
def h(p):
 d=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(65536),b''):d.update(b)
 return d.hexdigest()
def row(p,root,scope):
 real=p.resolve(strict=True)
 if not real.is_relative_to(root) or not real.is_file():raise Stop('NDK_PATH_ESCAPE_OR_TYPE')
 st=real.stat()
 if st.st_uid!=0 or st.st_mode&(stat.S_IWGRP|stat.S_IWOTH):raise Stop('NDK_FILE_OS_OWNERSHIP')
 return {'path':str(p),'realpath':str(real),'sha256':h(real),'bytes':st.st_size,'scope':scope}
def measure(root=ROOT):
 root=root.resolve(strict=True);deadline=time.monotonic()+120
 properties=root/'source.properties';text=properties.read_text()
 if not any(line.strip()=='Pkg.Revision = 29.0.14206865' for line in text.splitlines()):raise Stop('NDK_REVISION')
 host=root/'toolchains/llvm/prebuilt/linux-x86_64';tools=[host/'bin/clang',host/'bin/ld.lld']
 resources=sorted((host/'lib/clang').glob('*/include'))
 if len(resources)!=1:raise Stop('NDK_RESOURCE_AMBIGUOUS')
 files=[row(properties,root,'NDK_DESCRIPTOR')]+[row(p,root,'HOST_COMPILER_OR_LINKER') for p in tools]
 headers=[];total=0
 for p in sorted(resources[0].rglob('*')):
  if time.monotonic()>=deadline:raise Stop('MEASUREMENT_DEADLINE')
  if not p.is_file():continue
  if len(headers)>=4096:raise Stop('RESOURCE_INVENTORY_CAP')
  r=row(p,root,'NDK_RESOURCE_HEADER');total+=r['bytes']
  if total>64*1024*1024:raise Stop('RESOURCE_INVENTORY_CAP')
  headers.append(r)
 # Descriptor candidates are evidence only. No guessed file or executable --version.
 descriptors=[]
 for p in sorted(host.iterdir()):
  if p.is_file() and p.name in ['AndroidVersion.txt','NOTICE','NOTICE.toolchain'] and p.stat().st_size<=4*1024*1024:descriptors.append(row(p,root,'TOOLCHAIN_DESCRIPTOR'))
 return {'scope':'EXISTING_NDK_READ_ONLY_MEASUREMENT_NOT_TOOL_APPROVAL','ndk_revision':'29.0.14206865','files':files+headers+descriptors,'resource_dir':str(resources[0].parent),'header_count':len(headers),'header_bytes':total,'tool_executed':False,'compiler_executed':False,'fetch':False,'host_dependencies':'NOT_YET_QUALIFIED','status':'MEASURED_NOT_APPROVED'}
SAFE_STOPS=frozenset(['NDK_PATH_ESCAPE_OR_TYPE','NDK_FILE_OS_OWNERSHIP','NDK_REVISION','NDK_RESOURCE_AMBIGUOUS','MEASUREMENT_DEADLINE','RESOURCE_INVENTORY_CAP'])
def receipt(measurement=measure):
 try:return measurement(),0
 except Stop as error:
  reason=error.args[0] if error.args and error.args[0] in SAFE_STOPS else 'UNCLASSIFIED_SAFE_STOP'
 except (OSError,RuntimeError,ValueError):reason='EXISTING_FILE_MEASUREMENT_FAILED'
 except Exception:reason='UNEXPECTED_MEASUREMENT_FAILURE'
 return {'scope':'NDK_MEASUREMENT_STOP','reason':reason,'tool_executed':False,'compiler_executed':False,'fetch':False},1
def cli(destination='ndk-measurement-receipt.json',measurement=measure,stream=None):
 result,code=receipt(measurement)
 text=json.dumps(result,sort_keys=True)
 # A fixed local receipt and identical printed receipt. No untrusted exception text.
 try:pathlib.Path(destination).write_text(text+'\n')
 except OSError:
  result={'scope':'NDK_MEASUREMENT_STOP','reason':'RECEIPT_WRITE_FAILED','measurement_reason':result.get('reason'),'tool_executed':False}
  text=json.dumps(result,sort_keys=True);code=1
 print(text,file=stream or sys.stdout)
 return code
if __name__=='__main__':raise SystemExit(cli())

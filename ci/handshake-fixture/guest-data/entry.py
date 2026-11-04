"""One-shot data-only stage wrapper. Durable JSON stdout, always private-root cleanup."""
import pathlib,os,sys,hashlib,json,shutil,subprocess,platform,tempfile,signal
HERE=pathlib.Path(__file__).resolve().parent
from tool_gate import qualify_tools,unchanged
PIN='57921bab9f0c78ba163536b5c90dfdb1244bd6ee9be3e7959f8d8cbc4ee47179'
def cleanup(root):
 if root.is_symlink():raise RuntimeError('ROOT_SYMLINK')
 shutil.rmtree(root)
 if root.exists():raise RuntimeError('CLEANUP_FAILED')
def main():
 parent=pathlib.Path(os.environ['RUNNER_TEMP']).resolve();root=parent/'ldoa-guest-data-one';errors=[]
 os_release=pathlib.Path('/etc/os-release').read_text()
 if 'ID=ubuntu' not in os_release or 'VERSION_ID="22.04"' not in os_release:errors.append('UBUNTU2204_REQUIRED')
 mem={k:int(v.split()[0])*1024 for k,v in (line.split(':',1) for line in pathlib.Path('/proc/meminfo').read_text().splitlines())}
 if platform.machine()!='x86_64' or (os.cpu_count() or 0)<4 or mem.get('MemAvailable',0)<12*1024**3 or shutil.disk_usage(parent).free<13*1024**3:errors.append('RESOURCE_GATE')
 tools=[]
 try:tools=qualify_tools()
 except Exception:errors.append('HOST_TOOL_QUALIFICATION_FAILED')
 if hashlib.sha256((HERE/'package-pins.json').read_bytes()).hexdigest()!=PIN:errors.append('PACKAGE_PROPOSAL_PIN')
 print(json.dumps({'scope':'GUEST_DATA_PREFLIGHT','errors':errors,'runtime_bound_tools':tools,'root_preexisting':root.exists()}),flush=True)
 bindings=json.loads((HERE/'source-bindings.json').read_text())
 for name,pin in bindings.items():
  if hashlib.sha256((HERE/name).read_bytes()).hexdigest()!=pin:errors.append('SOURCE_BINDING')
 if errors or root.exists():return 1
 code=1
 try:
  env={'PATH':'/usr/bin:/bin','LC_ALL':'C','PYTHONDONTWRITEBYTECODE':'1','HOME':str(parent)}
  child=subprocess.Popen([sys.executable,str(HERE/'guest_data_only.py'),'--pins',str(HERE/'package-pins.json'),'--pins-sha256',PIN,'--private-root',str(root)],env=env,start_new_session=True)
  try:code=child.wait(timeout=600)
  except subprocess.TimeoutExpired:
   os.killpg(child.pid,signal.SIGKILL);child.wait();code=124
 finally:
  try:unchanged(tools)
  except Exception:code=1
  # Only source-owned structural fields are printed. No raw exception text or archive file bytes.
  try:
   for name in ['download-receipts.json','member-binding.json','static-elf.json']:
    p=root/name
    if p.is_file():
     print(json.dumps({'scope':'GUEST_DATA_DURABLE_RECEIPT','name':name,'data':json.loads(p.read_text())},sort_keys=True),flush=True)
  finally:
   if root.exists():cleanup(root)
   print(json.dumps({'scope':'GUEST_DATA_CLEANUP','owned_root_absent':not root.exists(),'returncode':code,'compiler_executed':False,'guest_executed':False,'runtime_asset_ready':False}),flush=True)
 return code
if __name__=='__main__':raise SystemExit(main())

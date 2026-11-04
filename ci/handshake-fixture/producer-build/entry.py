"""SOURCE PROPOSAL ONLY. Separate producer BUILD; requires reviewed tool policy and staged A1 bytes."""
import argparse,pathlib,sys,json,subprocess,time,hashlib,os,signal,resource
from rooted import rooted_closure
from budget import check as check_deadline
from contracts import Stop,digest,depfile,approved_file,trace_inputs,bind_manifest,command_plan
HERE=pathlib.Path(__file__).resolve().parent

def run(command,deadline,out,env):
 remaining=deadline-time.monotonic()
 if remaining<=0:raise Stop('BUILD_DEADLINE')
 with out.open('wb') as stdout,out.with_suffix('.stderr').open('wb') as stderr:
  def limits():resource.setrlimit(resource.RLIMIT_FSIZE,(8*1024*1024,8*1024*1024))
  child=subprocess.Popen(command,stdout=stdout,stderr=stderr,env=env,start_new_session=True,preexec_fn=limits)
  try:code=child.wait(timeout=remaining)
  except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait();raise Stop('BUILD_DEADLINE')
 if code:raise Stop('HOST_BUILD_COMMAND_FAILED')
 if out.stat().st_size>8*1024*1024 or out.with_suffix('.stderr').stat().st_size>8*1024*1024:raise Stop('BUILD_LOG_CAP')

def tool_gate(policy):
 # No default observed/local/old hash accepted. Policy must be independently reviewed before execution.
 if policy.get('status')!='REVIEWED_EXACT_RUNNER_TOOL_AND_RESOURCE_POLICY':raise Stop('TOOL_POLICY_UNRESOLVED')
 if policy.get('ndk_version')!='29.0.14206865':raise Stop('NDK_VERSION')
 allowed={}
 for row in policy['files']:
  p=pathlib.Path(row['path']).resolve(strict=True)
  if not p.is_relative_to('/usr/local/lib/android/sdk/ndk/29.0.14206865'):raise Stop('TOOL_OUTSIDE_NDK')
  if digest(p)!=row['sha256']:raise Stop('HOST_TOOL_RESOURCE_PIN')
  allowed[str(p)]={'sha256':row['sha256'],'scope':row['scope']}
 for key in ['clang','linker','resource_dir','readelf']:
  if key not in policy:raise Stop('TOOL_POLICY_FIELD')
 for key in ['clang','linker']:
  if str(pathlib.Path(policy[key]).resolve(strict=True)) not in allowed:raise Stop('TOOL_NOT_BOUND')
 resource_root=pathlib.Path(policy['resource_dir']).resolve(strict=True)
 for p in (resource_root/'include').rglob('*'):
  if p.is_file() and str(p.resolve()) not in allowed:raise Stop('NDK_RESOURCE_HEADER_UNBOUND')
 # Host DT_NEEDED/loader trust is a required explicit policy decision, not inferred from tool SHA.
 if policy.get('host_dependencies')!='EXPLICIT_TRUSTED_RUNNER_OS_DYNAMIC_LOADER_AND_LIBRARIES':raise Stop('HOST_TOOL_DEPS_UNRESOLVED')
 readelf=pathlib.Path(policy['readelf'])
 try:
  if str(readelf.resolve(strict=True))!='/usr/bin/x86_64-linux-gnu-readelf' or digest(readelf)!=policy.get('readelf_sha256'):raise Stop('READELF_POLICY')
 except OSError:raise Stop('READELF_MISSING')
 return allowed

def main():
 p=argparse.ArgumentParser();p.add_argument('--sysroot',required=True);p.add_argument('--member-binding',required=True);p.add_argument('--member-sha256',required=True);p.add_argument('--tool-policy',required=True);p.add_argument('--tool-policy-sha256',required=True);p.add_argument('--out',required=True);p.add_argument('--deadline-monotonic',required=True,type=float);a=p.parse_args()
 deadline=check_deadline(a.deadline_monotonic)
 if digest(a.member_binding)!=a.member_sha256 or digest(a.tool_policy)!=a.tool_policy_sha256:raise Stop('AUTHORITY_INPUT_PIN')
 member=json.loads(pathlib.Path(a.member_binding).read_text());policy=json.loads(pathlib.Path(a.tool_policy).read_text())
 if member.get('producer_recipe_sha256')!='8101830e250b9786dc269f18c7263304ed17b5d4496fdeadc6a25ef9c8990b13' or len(member.get('excluded_optional_inputs',[]))!=12:raise Stop('A1_RECIPE_BASELINE')
 allowed=bind_manifest(a.sysroot,member);allowed.update(tool_gate(policy))
 source=HERE.parent/'native/producer.c';header=HERE.parent/'native/request_protocol.h'
 source_policy=policy.get('source_files',{})
 for f in [source,header]:
  if digest(f)!=source_policy.get(f.name):raise Stop('PRODUCER_SOURCE_POLICY')
  allowed[str(f.resolve())]={'sha256':digest(f),'scope':'CURRENT_PRODUCER_SOURCE'}
 out=pathlib.Path(a.out);out.mkdir(mode=0o700)
 check_deadline(deadline) # Preserve wrapper deadline, never restart after staging.
 env={'PATH':'/usr/bin:/bin','HOME':str(out),'TMPDIR':str(out),'LC_ALL':'C'}
 pre,compile,link=command_plan(policy['clang'],policy['linker'],policy['resource_dir'],a.sysroot,source,out)
 receipt={'compiler_executed':False,'scope':'PRODUCER_BUILD_QUALIFICATION_NOT_RUNTIME','complete':False,'runtime_asset_ready':False,'guest_executed':False,'commands':[pre,compile,link],'stages':[],'deadline_monotonic':deadline}
 try:
  receipt['compiler_executed']=True
  run(pre,deadline,out/'preprocess.stdout',env);deps=[approved_file(n,allowed) for n in depfile((out/'producer.d').read_text())];receipt['headers']=deps;receipt['stages'].append('PREPROCESS_INPUTS_BOUND')
  run(compile,deadline,out/'compile.stdout',env);receipt['object_sha256']=digest(out/'producer.o');receipt['stages'].append('COMPILED_OBJECT')
  run(link,deadline,out/'link.stdout',env);receipt['link_inputs']=trace_inputs((out/'link.stdout').read_text(),allowed,out/'producer.o');receipt['stages'].append('LINK_TRACE_BOUND')
  receipt['producer_sha256']=digest(out/'producer');receipt['producer_bytes']=(out/'producer').stat().st_size
  def read(command,remaining_deadline):
   run(command,remaining_deadline,out/'readelf.stdout',env)
   return (out/'readelf.stdout').read_text()
  receipt['rooted_closure']=rooted_closure(out/'producer',a.sysroot,member,policy['readelf'],deadline,read)
  receipt['stages'].append('PRODUCER_ROOTED_STATIC_CLOSURE_BOUND')
  for name,row in allowed.items():
   check_deadline(deadline)
   if digest(name)!=row['sha256']:raise Stop('POST_BUILD_INPUT_CHANGED')
  check_deadline(deadline)
 finally:
  (out/'producer-build-receipt.json').write_text(json.dumps(receipt,indent=2))
def cli():
 try:main()
 except Stop as error:
  # Preflight can fail before main creates output. A fresh output receives a fixed failure receipt.
  if '--out' in sys.argv:
   out=pathlib.Path(sys.argv[sys.argv.index('--out')+1])
   if not out.exists():
    out.mkdir(mode=0o700)
    (out/'producer-build-receipt.json').write_text(json.dumps({'scope':'PRODUCER_BUILD_PREFLIGHT_STOP','reason':error.args[0],'complete':False,'compiler_executed':False,'guest_executed':False,'runtime_asset_ready':False}))
  return 1
 return 0
if __name__=='__main__':raise SystemExit(cli())

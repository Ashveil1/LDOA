"""Offline workflow source generator. No integration/runner/privilege invocation."""
from pathlib import Path
import hashlib,base64,json
R=Path(__file__).resolve().parent
helper=(R/'snapshot_contract.py').read_text()+'\n'+(R/'binding_contract.py').read_text().replace('class Stop(RuntimeError):pass','')+'\n'+(R/'fd_lifecycle.py').read_text()
compile(helper,'standalone_helper.py','exec');(R/'standalone_helper.py').write_text(helper)
digest=hashlib.sha256(helper.encode()).hexdigest();payload=base64.b64encode(helper.encode()).decode()
installer='''import os,stat,sys,json,hashlib,base64,time,re
EXPECTED_DIGEST=__DIGEST__
PAYLOAD=__PAYLOAD__
FLAGS=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
def fail():raise RuntimeError('INSTALL_BOUNDARY')
if os.geteuid()!=0 or len(sys.argv)!=10:fail()
data=base64.b64decode(PAYLOAD,validate=True)
if hashlib.sha256(data).hexdigest()!=EXPECTED_DIGEST:raise RuntimeError('HELPER_DIGEST')
keys=['ImageOS','ImageVersion','runner_environment','run_id','run_attempt','repository','workflow_sha','head_sha']
context=dict(zip(keys,sys.argv[1:9]))
binding=json.loads(sys.argv[9])
__BINDING_CODE__
try:verified_binding=bind(binding['actual'],binding['expected'])
except Exception:fail()
if binding['actual']['head_sha']!=context['head_sha'] or binding['actual']['workflow_sha']!=context['workflow_sha'] or binding['actual']['repository']!=context['repository']:fail()
if context['ImageOS']!='ubuntu22' or not re.fullmatch(r'\\d{8}\\.\\d+\\.\\d+',context['ImageVersion']):fail()
if context['runner_environment']!='github-hosted' or context['repository']!='Ashveil1/LDOA':fail()
if any(not re.fullmatch(r'[1-9][0-9]*',context[k]) for k in ['run_id','run_attempt']):fail()
if any(not re.fullmatch(r'[a-f0-9]{40}',context[k]) for k in ['workflow_sha','head_sha']):fail()
deadline=time.monotonic()+1200
root=os.open('/',FLAGS)
try:
 for component in ['', 'opt']:
  if component:
   child=os.open(component,FLAGS,dir_fd=root);os.close(root);root=child
  st=os.fstat(root)
  guard=st.st_uid==0 and not bool(stat.S_IMODE(st.st_mode)&0o022)
  print(json.dumps({'scope':'SNAPSHOT_INSTALL_ANCESTOR_STAT','label':'ROOT' if component=='' else 'OPT','uid':st.st_uid,'gid':st.st_gid,'mode':format(stat.S_IMODE(st.st_mode),'04o'),'guard':guard,'before_installation_effects':True},sort_keys=True),flush=True)
  if not guard:fail()
 # No existing directory accepted. Exact fixed installation, no user-selected paths.
 os.mkdir('ldoa-qualification',0o755,dir_fd=root)
 parent=os.open('ldoa-qualification',FLAGS,dir_fd=root)
 identity=os.fstat(parent);success=False
 try:
  for name,content,mode in [('ndk29_snapshot.py',data,0o555),('context.json',json.dumps({'managed_image':context,'dispatch_binding':verified_binding},sort_keys=True).encode(),0o444),('deadline',repr(deadline).encode(),0o444)]:
   fd=os.open(name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=parent)
   try:
    view=memoryview(content)
    while view:
     n=os.write(fd,view)
     if n<=0:fail()
     view=view[n:]
    os.fsync(fd);os.fchmod(fd,mode)
   finally:os.close(fd)
  # Read-back authenticated helper before first invocation. Not helper-self attestation.
  fd=os.open('ndk29_snapshot.py',os.O_RDONLY|os.O_NOFOLLOW,dir_fd=parent)
  try:
   st=os.fstat(fd);h=hashlib.sha256()
   if st.st_uid!=0 or stat.S_IMODE(st.st_mode)!=0o555 or st.st_size!=len(data):fail()
   while True:
    block=os.read(fd,65536)
    if not block:break
    h.update(block)
   if h.hexdigest()!=EXPECTED_DIGEST:fail()
  finally:os.close(fd)
  print(json.dumps({'scope':'LITERAL_HELPER_INSTALLED','helper_sha256':EXPECTED_DIGEST,'managed_image':context}),flush=True)
  success=True
 finally:
  if not success:
   try:
    current=os.stat('ldoa-qualification',dir_fd=root,follow_symlinks=False)
    if (current.st_dev,current.st_ino)!=(identity.st_dev,identity.st_ino):fail()
    for name in os.listdir(parent):
     if name not in {'ndk29_snapshot.py','context.json','deadline'}:fail()
     st=os.stat(name,dir_fd=parent,follow_symlinks=False)
     if not stat.S_ISREG(st.st_mode) or st.st_uid!=0:fail()
     os.unlink(name,dir_fd=parent)
    os.rmdir('ldoa-qualification',dir_fd=root)
    print(json.dumps({'scope':'INSTALL_FAILURE_CLEANUP','status':'ABSENT'}),flush=True)
   except Exception:
    print(json.dumps({'scope':'INSTALL_FAILURE_CLEANUP','status':'FAILED'}),flush=True)
  os.close(parent)
finally:os.close(root)
'''.replace('__DIGEST__',repr(digest)).replace('__PAYLOAD__',repr(payload)).replace('__BINDING_CODE__',(R/'binding_contract.py').read_text())
compile(installer,'installer.py','exec');(R/'installer.literal.py').write_text(installer)
# Emergency cleanup code is also literal trusted stdlib code, independent of installed helper.
cleanup='''import os,stat,hashlib,json
FLAGS=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
EXPECTED_DIGEST=__DIGEST__
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
 for component in ['', 'opt']:
  if component:
   child=os.open(component,FLAGS,dir_fd=root);os.close(root);root=child
  st=os.fstat(root)
  if st.st_uid!=0 or stat.S_IMODE(st.st_mode)&0o022:fail()
 try:parent=os.open('ldoa-qualification',FLAGS,dir_fd=root)
 except FileNotFoundError:print('QUALIFICATION_INSTALL_ABSENT');raise SystemExit(0)
 try:
  st=os.fstat(parent);named=os.stat('ldoa-qualification',dir_fd=root,follow_symlinks=False)
  if st.st_uid!=0 or stat.S_IMODE(st.st_mode)!=0o755 or (st.st_dev,st.st_ino)!=(named.st_dev,named.st_ino):fail()
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
'''.replace('__DIGEST__',repr(digest))
compile(cleanup,'cleanup.py','exec');(R/'cleanup.literal.py').write_text(cleanup)
def block(code):return '\n'.join('          '+x if x.strip() else '' for x in code.splitlines())
gate=(R/'binding_contract.py').read_text()+"\nimport os,json\nactual={'workflow_sha':os.environ['ACTUAL_WORKFLOW_SHA'],'head_sha':os.environ['ACTUAL_HEAD'],'ref':os.environ['ACTUAL_REF'],'workflow_ref':os.environ['ACTUAL_WORKFLOW_REF'],'repository':os.environ['ACTUAL_REPOSITORY']}\nexpected={'head_sha':os.environ['EXPECTED_HEAD'],'ref':os.environ['EXPECTED_REF']}\nresult=bind(actual,expected)\nprint(json.dumps({'scope':'PRE_EFFECT_DISPATCH_BINDING','dispatch_binding':result},sort_keys=True),flush=True)\nwith open(os.environ['GITHUB_OUTPUT'],'a') as out:out.write('binding='+json.dumps(result,separators=(',',':'))+'\\n')\n"
compile(gate,'gate.py','exec');(R/'gate.literal.py').write_text(gate)
workflow='''# SOURCE CANDIDATE ONLY; unresolved exacthead gate prevents execution. Not registered.
name: isolated-ndk29-snapshot-candidate
on:
  workflow_dispatch:
    inputs:
      expected_head:
        required: true
        type: string
      expected_ref:
        required: true
        type: string
permissions:
  contents: read
jobs:
  snapshot:
    if: false
    runs-on: ubuntu-22.04
    timeout-minutes: 25
    steps:
      # No checkout/repository actions or tests before inventory. Helper is workflow literal.
      - name: Exact managed dispatch gate before privileged effects
        id: binding
        env:
          EXPECTED_HEAD: ${{ inputs.expected_head }}
          EXPECTED_REF: ${{ inputs.expected_ref }}
          ACTUAL_HEAD: ${{ github.sha }}
          ACTUAL_REF: ${{ github.ref }}
          ACTUAL_WORKFLOW_SHA: ${{ github.workflow_sha }}
          ACTUAL_WORKFLOW_REF: ${{ github.workflow_ref }}
          ACTUAL_REPOSITORY: ${{ github.repository }}
        run: |
          /usr/bin/python3 -I - <<'GATE'
__GATE__
          GATE
      - name: Install exact workflow-literal helper and managed context
        id: install
        env:
          MANAGED_ENVIRONMENT: ${{ runner.environment }}
          MANAGED_RUN_ID: ${{ github.run_id }}
          MANAGED_RUN_ATTEMPT: ${{ github.run_attempt }}
          MANAGED_REPOSITORY: ${{ github.repository }}
          MANAGED_WORKFLOW_SHA: ${{ github.workflow_sha }}
          MANAGED_HEAD_SHA: ${{ github.sha }}
          DISPATCH_BINDING: ${{ steps.binding.outputs.binding }}
        run: |
          sudo -n /usr/bin/python3 -I - "$ImageOS" "$ImageVersion" "$MANAGED_ENVIRONMENT" "$MANAGED_RUN_ID" "$MANAGED_RUN_ATTEMPT" "$MANAGED_REPOSITORY" "$MANAGED_WORKFLOW_SHA" "$MANAGED_HEAD_SHA" "$DISPATCH_BINDING" <<'INSTALL'
__INSTALL__
          INSTALL
          echo 'installed=true' >> "$GITHUB_OUTPUT"
      - name: Snapshot only, no tool consumption
        run: sudo -n /usr/bin/timeout --signal=TERM --kill-after=5s 1230s /usr/bin/python3 -I /opt/ldoa-qualification/ndk29_snapshot.py
      - name: Owned snapshot/helper emergency cleanup
        if: always() && steps.install.outputs.installed == 'true'
        run: |
          sudo -n /usr/bin/python3 -I - <<'CLEANUP'
__CLEANUP__
          CLEANUP
# No checkout/tests/tool subprocess/compiler/version/readelf/ldd/fetch/build/upload/cache/runtime.
'''.replace('__INSTALL__',block(installer)).replace('__CLEANUP__',block(cleanup)).replace('__GATE__',block(gate))
(R/'SNAPSHOT-WORKFLOW.INERT.yml').write_text(workflow)
(R/'literal-bindings.json').write_text(json.dumps({'standalone_helper.py':digest,'installer.literal.py':hashlib.sha256(installer.encode()).hexdigest(),'cleanup.literal.py':hashlib.sha256(cleanup.encode()).hexdigest(),'gate.literal.py':hashlib.sha256(gate.encode()).hexdigest(),'SNAPSHOT-WORKFLOW.INERT.yml':hashlib.sha256(workflow.encode()).hexdigest()},indent=2)+'\n')
print('LITERAL_SOURCE_GENERATED',len(helper),digest)

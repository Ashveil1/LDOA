"""Pure currentjob snapshot gate candidate. No reusable observed pins or legacy status bypass."""
from host_contract import Stop
import re
HELPER='5463f0c133c1d75923a442637fff2a6841ffd7f0b3beb50d48508c24d8899038'
ROOT='/ldoa-qualification/ndk29-snapshot/'
def tool_gate_candidate(envelope,job,authority,static,pre,post):
 if set(job)!={'run_id','run_attempt','head_sha','workflow_sha','repository','ImageOS','ImageVersion','runner_environment'}:raise Stop('JOB_CONTEXT_FIELDS')
 # authority is separate explicit futurejob review evidence, never inferred from receipts.
 if authority!={'status':'SEPARATELY_REVIEWED_THIS_JOB_SNAPSHOT_AND_HOST_OS_CONTRACT','job':job}:raise Stop('JOB_AUTHORITY_UNRESOLVED')
 if envelope.get('helper_sha256')!=HELPER or envelope.get('job')!=job:raise Stop('SNAPSHOT_JOB_BINDING')
 image=envelope.get('receipt',{}).get('managed_image',{})
 if any(image.get(k)!=job.get(k) for k in ['run_id','run_attempt','head_sha','workflow_sha','repository','ImageOS','ImageVersion','runner_environment']):raise Stop('SNAPSHOT_CONTEXT')
 receipt=envelope['receipt']
 if receipt.get('status')!='SNAPSHOT_ONLY_NOT_APPROVED' or receipt.get('snapshot_only')!=True:raise Stop('SNAPSHOT_STATUS')
 # Historical successful receipts say cleanupABSENT: their bytes cannot be consumed in a laterjob.
 if receipt.get('cleanup')=='ABSENT' or envelope.get('lifecycle')!='SEALED_LIVE_THIS_JOB_BEFORE_CONSUMER':raise Stop('SNAPSHOT_DELETED_OR_NOT_LIVE')
 if pre!=post or not pre:raise Stop('HOST_PRE_POST_CHANGED')
 if static.get('status')!='SEPARATELY_REVIEWED_RESOLUTION_SEMANTICS_AND_CLOSURE':raise Stop('HOST_SEMANTICS_UNRESOLVED')
 rows=receipt.get('files',[]);tools=[r for r in rows if r.get('scope')=='TOOL'];headers=[r for r in rows if r.get('scope')=='HEADER']
 targets={r.get('logical'):r for r in tools};prefix='toolchains/llvm/prebuilt/linux-x86_64/'
 names=[prefix+'bin/clang',prefix+'bin/ld.lld']
 if set(targets)!=set(names) or not headers or len(headers)>4096:raise Stop('SNAPSHOT_INVENTORY')
 resource=receipt.get('counts',{}).get('resource')
 if resource!=prefix+'lib/clang/21':raise Stop('SNAPSHOT_RESOURCE')
 allowed={}
 for r in rows:
  name=r.get('canonical','')
  if not name or name.startswith('/') or '..' in name.split('/') or not isinstance(r.get('sha256'),str) or not re.fullmatch('[a-f0-9]{64}',r['sha256']):raise Stop('SNAPSHOT_ROW')
  allowed[ROOT+name]={'sha256':r['sha256'],'scope':'CURRENT_JOB_SEALED_SNAPSHOT_NOT_PUBLISHER_PIN'}
 return {'status':'CANDIDATE_GATE_BOUND_NOT_COMPILER_AUTHORITY','clang':ROOT+targets[names[0]]['canonical'],'linker':ROOT+targets[names[1]]['canonical'],'resource_dir':ROOT+resource,'allowed':allowed,'host_os_bindings':pre,'compiler_execution_authorized':False,'legacy_tool_gate_unchanged':True}

"""Pure proposed dispatch binding, no I/O or effect."""
import re
class Stop(RuntimeError):pass
REPO='Ashveil1/LDOA'
PATH='.github/workflows/isolated-ndk29-snapshot-candidate.yml'
def bind(actual,expected):
 if set(actual)!={'workflow_sha','head_sha','ref','workflow_ref','repository'} or set(expected)!={'head_sha','ref'}:raise Stop('BINDING_FIELDS')
 if not re.fullmatch('[0-9a-f]{40}',actual['workflow_sha']):raise Stop('WORKFLOW_CONTEXT_SHAPE')
 for name in ['head_sha']:
  if not re.fullmatch('[0-9a-f]{40}',expected[name]) or actual[name]!=expected[name]:raise Stop('BINDING_SHA')
 ref=expected['ref']
 if not isinstance(ref,str) or not ref.startswith(('refs/heads/','refs/tags/')):raise Stop('BINDING_REF')
 short=ref.split('/',2)[2]
 if not short or len(ref)>512 or ref.endswith(('/', '.')) or '..' in ref or '@{' in ref or '\\' in ref or short=='@':raise Stop('BINDING_REF')
 if any(ord(c)<33 or ord(c)==127 or c in '~^:?*[' for c in ref):raise Stop('BINDING_REF')
 parts=short.split('/')
 if any(not x or x.startswith('.') or x.endswith('.lock') for x in parts):raise Stop('BINDING_REF')
 # Branch names have stricter leading-dash restriction than generic refnames.
 if ref.startswith('refs/heads/') and short.startswith('-'):raise Stop('BINDING_REF')
 if actual['repository']!=REPO or actual['ref']!=ref or actual['workflow_ref']!=REPO+'/'+PATH+'@'+ref:raise Stop('BINDING_CONTEXT')
 # workflow_sha is recorded opaque. No guessed equality or last-touched semantics.
 return {'actual':dict(actual),'expected':dict(expected),'status':'CONTEXT_MATCH_EXTERNAL_CONTROLLER_APPROVAL_STILL_REQUIRED'}

import os,json
actual={'workflow_sha':os.environ['ACTUAL_WORKFLOW_SHA'],'head_sha':os.environ['ACTUAL_HEAD'],'ref':os.environ['ACTUAL_REF'],'workflow_ref':os.environ['ACTUAL_WORKFLOW_REF'],'repository':os.environ['ACTUAL_REPOSITORY']}
expected={'head_sha':os.environ['EXPECTED_HEAD'],'ref':os.environ['EXPECTED_REF']}
result=bind(actual,expected)
print(json.dumps({'scope':'PRE_EFFECT_DISPATCH_BINDING','dispatch_binding':result},sort_keys=True),flush=True)
with open(os.environ['GITHUB_OUTPUT'],'a') as out:out.write('binding='+json.dumps(result,separators=(',',':'))+'\n')

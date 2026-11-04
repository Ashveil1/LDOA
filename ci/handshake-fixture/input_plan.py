"""Pure input-DAG validation. Does not fetch, build, install or run a target."""
import pathlib,json,hashlib
KINDS=frozenset({'source','metadata','generated_lock','payload','tool_observation','build_observation','runtime_observation'})
def validate_plan(plan):
 nodes=plan['nodes'];ids=[n['id'] for n in nodes]
 if len(ids)!=len(set(ids)):raise ValueError('DUPLICATE_NODE')
 byid={n['id']:n for n in nodes};visiting=set();done=set()
 def visit(key):
  if key in visiting:raise ValueError('CYCLE')
  if key in done:return
  if key not in byid:raise ValueError('MISSING_DEPENDENCY_NODE')
  n=byid[key]
  if n['kind'] not in KINDS:raise ValueError('UNKNOWN_KIND')
  if n['state'] not in {'SOURCE_PRESENT','METADATA_OBSERVED','MISSING','FUTURE_OBSERVATION','INPUT_BOUND'}:raise ValueError('INVALID_STATE')
  if n['kind'] in {'generated_lock','payload','tool_observation','build_observation','runtime_observation'} and n['state'] not in {'MISSING','FUTURE_OBSERVATION','INPUT_BOUND'}:raise ValueError('UNMEASURED_READY_STATE')
  if n['state']=='INPUT_BOUND' and n['id'] not in {'native_lock','native_payloads'}:raise ValueError('UNSUPPORTED_BOUND_INPUT')
  visiting.add(key)
  for dep in n['requires']:visit(dep)
  visiting.remove(key);done.add(key)
 for key in byid:visit(key)
 return {'node_count':len(nodes),'topology':'ACYCLIC','missing':[n['id'] for n in nodes if n['state'] in {'MISSING','FUTURE_OBSERVATION'}],'build_ready':False,'runtime_ready':False}
def missing_paths(root,plan):
 root=pathlib.Path(root)
 return [{'id':n['id'],'path':n['path']} for n in plan['nodes'] if n.get('path') and not (root/n['path']).is_file()]

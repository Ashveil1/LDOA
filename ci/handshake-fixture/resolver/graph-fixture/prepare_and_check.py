"""Offline fixture preparation/check only. Never downloads or launches Gradle."""
import pathlib,zipfile,json,sys
ROOT=pathlib.Path(__file__).resolve().parent

def prepare():
 for name,body in [('leaf',''),('external','<dependencies><dependency><groupId>fixture</groupId><artifactId>leaf</artifactId><version>1</version></dependency></dependencies>'),('bom','<packaging>pom</packaging><dependencyManagement><dependencies><dependency><groupId>fixture</groupId><artifactId>leaf</artifactId><version>1</version></dependency></dependencies></dependencyManagement>')]:
  d=ROOT/'repo'/'fixture'/name/'1';d.mkdir(parents=True,exist_ok=True)
  (d/f'{name}-1.pom').write_text(f'<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>{name}</artifactId><version>1</version>{body}</project>')
  if name!='bom':
   with zipfile.ZipFile(d/f'{name}-1.jar','w') as z:z.writestr('fixture.txt','synthetic, never executed')
 source=(ROOT.parent/'local-repositories.init.gradle').read_text()
 (ROOT/'graph-only.init.gradle').write_text(source[source.index('// GRAPH_INVENTORY_START:'):])

def assess(data,expected_failure,producer_absent):
 rows=data.get('configurations',[])
 probe=next((r for r in rows if r.get('project')==':consumer' and r.get('configuration')=='probe'),{})
 components=probe.get('components',[]);edges=probe.get('edges',[]);ids={c.get('id') for c in components}
 bom=next((c for c in components if c.get('id')=='fixture:bom:1'),{})
 bom_variants=bom.get('selected_variants',[])
 platform=lambda v:v.get('attributes',{}).get('org.gradle.category')=='platform'
 bom_incoming=[e for e in edges if e.get('selected',{}).get('id')=='fixture:bom:1']
 checks={
  'scope_incomplete':data.get('complete_build_input_lock') is False and data.get('external_artifact_selection')=='UNQUALIFIED',
  'probe_graph_success':probe.get('status')=='GRAPH_RESOLVED_ARTIFACTS_UNQUALIFIED',
  'external_transitive_bom_nodes':{'fixture:external:1','fixture:leaf:1','fixture:bom:1'}<=ids,
  'local_project_deferred':any(c.get('kind')=='LOCAL_PROJECT_DEFERRED' and 'producer' in c.get('id','') for c in components),
  'bom_component_platform_variant':any(platform(v) for v in bom_variants),
  'bom_incoming_platform_variant':bool(bom_incoming) and any(platform(e.get('selected_variant',{})) for e in bom_incoming),
  'bom_constraint_to_leaf':any(e.get('constraint') and e.get('from',{}).get('id')=='fixture:bom:1' and e.get('selected',{}).get('id')=='fixture:leaf:1' for e in edges),
  'file_declaration_unmaterialized':bool(probe.get('file_inputs')) and all(f.get('files_materialized') is False for f in probe.get('file_inputs',[])),
  'external_to_leaf_edge':any(e.get('from',{}).get('id')=='fixture:external:1' and e.get('selected',{}).get('id')=='fixture:leaf:1' for e in edges),
  'producer_output_absent':producer_absent,
  'strict_failure_count':data.get('graph_failure_count',0)>0 if expected_failure else data.get('graph_failure_count')==0,
  'negative_edge_or_positive_only':any(r.get('configuration')=='unresolvedProbe' and any(e.get('status')=='UNRESOLVED' for e in r.get('edges',[])) for r in rows) if expected_failure else not any(r.get('configuration')=='unresolvedProbe' for r in rows)
 }
 return checks

def check(expected_failure):
 data=json.loads((ROOT/'selected-inputs.json').read_text())
 # Export reviewed structural inventory BEFORE evaluating any assertion. No arbitrary errors/descriptions.
 print(json.dumps({'scope':'FIXTURE_GRAPH_EVIDENCE','expected_failure':expected_failure,'inventory':data},sort_keys=True),flush=True)
 checks=assess(data,expected_failure,not (ROOT/'producer'/'build').exists())
 print(json.dumps({'scope':'FIXTURE_SEMANTIC_ASSERTIONS','expected_failure':expected_failure,'checks':checks,'passed':all(checks.values())},sort_keys=True),flush=True)
 if not all(checks.values()):raise RuntimeError('FIXTURE_SEMANTIC_ASSERTIONS_FAILED')
 print('FIXTURE_GRAPH_ASSERTIONS_PASS; complete_build_input_lock=false',flush=True)
if __name__=='__main__':
 if sys.argv[1]=='prepare':prepare()
 else:check(sys.argv[1]=='check-failure')

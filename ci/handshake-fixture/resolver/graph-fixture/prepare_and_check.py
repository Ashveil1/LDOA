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

def check(expected_failure):
 data=json.loads((ROOT/'selected-inputs.json').read_text());rows=data['configurations']
 assert not data['complete_build_input_lock'] and data['external_artifact_selection']=='UNQUALIFIED'
 probe=next(r for r in rows if r['project']==':consumer' and r['configuration']=='probe')
 assert probe['status']=='GRAPH_RESOLVED_ARTIFACTS_UNQUALIFIED',probe
 components=probe['components']; ids={c['id'] for c in components}
 assert {'fixture:external:1','fixture:leaf:1','fixture:bom:1'}<=ids,ids
 assert any(c['kind']=='LOCAL_PROJECT_DEFERRED' and 'producer' in c['id'] for c in components)
 assert any(v['attributes'].get('org.gradle.category') in ('platform','enforced-platform','regular-platform') for c in components if c['id']=='fixture:bom:1' for v in c['selected_variants'])
 assert any(e['constraint'] for e in probe['edges'])
 assert probe['file_inputs'] and not probe['file_inputs'][0]['files_materialized']
 assert any(e['from']['id']=='fixture:external:1' and e.get('selected',{}).get('id')=='fixture:leaf:1' for e in probe['edges'])
 assert not (ROOT/'producer'/'build').exists(),'producer output unexpectedly materialized'
 if expected_failure:
  assert data['graph_failure_count']>0
  assert any(r['configuration']=='unresolvedProbe' and any(e['status']=='UNRESOLVED' for e in r['edges']) for r in rows)
 else:assert data['graph_failure_count']==0
 print('FIXTURE_GRAPH_ASSERTIONS_PASS; complete_build_input_lock=false')
if __name__=='__main__':
 if sys.argv[1]=='prepare':prepare()
 else:check(sys.argv[1]=='check-failure')

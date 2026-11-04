"""Source contracts and independent graph model. NOT Gradle/Groovy execution."""
import pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parent

def model(configurations):
 rows=[];failures=0
 for c in sorted(configurations,key=lambda c:c['name']):
  if not c['resolvable']:continue
  row={'configuration':c['name'],'components':c.get('components',[]),'edges':c.get('edges',[]),'files_materialized':False}
  failures+=sum(e['status']!='RESOLVED' for e in row['edges']);rows.append(row)
 return {'configurations':rows,'graph_failure_count':failures,'complete_build_input_lock':False}
class Inventory(unittest.TestCase):
 def test_source_no_artifact_materialization_or_skip(self):
  s=(ROOT/'local-repositories.init.gradle').read_text().split('// GRAPH_INVENTORY_START:',1)[1]
  for forbidden in ['.resolve()', 'getResolvedConfiguration(', 'getResolvedArtifacts(', 'artifactView', 'getFiles()', 'lenient', 'withVariantReselection', 'debugAndroidTest']:
   self.assertNotIn(forbidden,s)
  for required in ['configuration.isCanBeResolved()', 'graph.getAllComponents()', 'graph.getAllDependencies()', 'UnresolvedDependencyResult','dependency.isConstraint()', 'dependency.getResolvedVariant()', 'component.getVariants()', 'requested_attributes:', 'UNQUALIFIED_FILE_INPUT','LOCAL_PROJECT_DEFERRED', 'complete_build_input_lock:false', 'rows.add(row)', 'catch (Exception failure)', 'if (failureCount) throw new GradleException']:
   self.assertIn(required,s)
  self.assertLess(s.index('capturedOutput.text='),s.index('if (failureCount) throw'))
 def test_every_config_and_failure_does_not_skip_later(self):
  result=model([{'name':'a','resolvable':True,'edges':[{'status':'UNRESOLVED'}]},{'name':'z','resolvable':True},{'name':'hidden','resolvable':False}])
  self.assertEqual([r['configuration'] for r in result['configurations']],['a','z']);self.assertEqual(result['graph_failure_count'],1);self.assertFalse(result['complete_build_input_lock'])
 def test_local_external_platform_file_and_transitive_preserved(self):
  cs=[{'id':':producer','kind':'LOCAL_PROJECT_DEFERRED'},{'id':'external:1','kind':'EXTERNAL_MODULE'},{'id':'leaf:1','kind':'EXTERNAL_MODULE'},{'id':'bom:1','category':'platform'}]
  edges=[{'status':'RESOLVED','from':':producer','to':'external:1'},{'status':'RESOLVED','from':'external:1','to':'leaf:1'},{'status':'RESOLVED','constraint':True,'from':'bom:1','to':'leaf:1'}]
  r=model([{'name':'probe','resolvable':True,'components':cs,'edges':edges}])
  self.assertEqual(r['configurations'][0]['components'],cs);self.assertEqual(r['configurations'][0]['edges'],edges);self.assertFalse(r['configurations'][0]['files_materialized'])
 def test_fixture_uses_verbatim_inventory_and_poison_producer(self):
  script=(ROOT/'graph-fixture/prepare_and_check.py').read_text();build=(ROOT/'graph-fixture/build.gradle').read_text()
  self.assertIn("source.index('// GRAPH_INVENTORY_START:')",script)
  for token in ['PRODUCER_TASK_EXECUTED',"['alpha','beta']",'graphFixtureUnresolved',"platform('fixture:bom:1')","probe files("]:
   self.assertIn(token,build)
if __name__=='__main__':unittest.main()

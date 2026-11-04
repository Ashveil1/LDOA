"""Independent assertion-objective model, NOT real Gradle output."""
import importlib.util,pathlib,unittest,copy,io,contextlib,tempfile,json
ROOT=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('checker',ROOT/'graph-fixture/prepare_and_check.py');checker=importlib.util.module_from_spec(spec);spec.loader.exec_module(checker)
def synthetic():
 variant={'attributes':{'org.gradle.category':'platform'}}
 components=[{'id':'project :producer','kind':'LOCAL_PROJECT_DEFERRED'},{'id':'fixture:external:1'},{'id':'fixture:leaf:1'},{'id':'fixture:bom:1','selected_variants':[variant]}]
 edges=[{'from':{'id':'fixture:external:1'},'selected':{'id':'fixture:leaf:1'}},{'from':{'id':'fixture:bom:1'},'selected':{'id':'fixture:leaf:1'},'constraint':True},{'selected':{'id':'fixture:bom:1'},'selected_variant':variant}]
 return {'complete_build_input_lock':False,'external_artifact_selection':'UNQUALIFIED','graph_failure_count':0,'configurations':[{'project':':consumer','configuration':'probe','status':'GRAPH_RESOLVED_ARTIFACTS_UNQUALIFIED','components':components,'edges':edges,'file_inputs':[{'files_materialized':False}]}]}
class Observability(unittest.TestCase):
 def test_full_positive_and_negative_objective(self):
  d=synthetic();self.assertTrue(all(checker.assess(d,False,True).values()))
  d['graph_failure_count']=1;d['configurations'].append({'configuration':'unresolvedProbe','edges':[{'status':'UNRESOLVED'}]});self.assertTrue(all(checker.assess(d,True,True).values()))
 def test_component_and_edge_platform_both_required(self):
  d=synthetic();d['configurations'][0]['components'][-1]['selected_variants']=[]
  c=checker.assess(d,False,True);self.assertFalse(c['bom_component_platform_variant']);self.assertTrue(c['bom_incoming_platform_variant'])
  d=synthetic();d['configurations'][0]['edges'][-1]['selected_variant']={'attributes':{}}
  self.assertFalse(checker.assess(d,False,True)['bom_incoming_platform_variant'])
 def test_no_early_assert_masks_other_checks_and_evidence(self):
  d=synthetic();d['configurations'][0]['components'][-1]['selected_variants']=[]
  with tempfile.TemporaryDirectory() as td:
   old=checker.ROOT;checker.ROOT=pathlib.Path(td);(checker.ROOT/'selected-inputs.json').write_text(json.dumps(d));stream=io.StringIO()
   try:
    with contextlib.redirect_stdout(stream),self.assertRaisesRegex(RuntimeError,'FIXTURE_SEMANTIC_ASSERTIONS_FAILED'):checker.check(False)
   finally:checker.ROOT=old
  lines=stream.getvalue().splitlines();self.assertEqual(json.loads(lines[0])['scope'],'FIXTURE_GRAPH_EVIDENCE');self.assertEqual(len(json.loads(lines[1])['checks']),12)
 def test_core_plugin_present_and_no_weakened_category(self):
  self.assertIn("apply plugin: 'java-base'",(ROOT/'graph-fixture/build.gradle').read_text())
  self.assertIn("=='platform'",(ROOT/'graph-fixture/prepare_and_check.py').read_text())
if __name__=='__main__':unittest.main()

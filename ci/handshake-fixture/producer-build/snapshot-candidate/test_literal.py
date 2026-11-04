import unittest,pathlib,hashlib,json,ast
R=pathlib.Path(__file__).resolve().parent
class Literal(unittest.TestCase):
 def test_standalone_imports_no_repo(self):
  data=(R/'standalone_helper.py').read_text();tree=ast.parse(data)
  imports=[n.module for n in ast.walk(tree) if isinstance(n,ast.ImportFrom)]
  self.assertEqual(imports,[])
  ns={'__name__':'synthetic'};exec(compile(data,'standalone','exec'),ns);self.assertTrue(callable(ns['snapshot_only']))
 def test_literal_hashes(self):
  pins=json.loads((R/'literal-bindings.json').read_text())
  for n,h in pins.items():self.assertEqual(hashlib.sha256((R/n).read_bytes()).hexdigest(),h)
 def test_installer_literal_digest_context_and_gate(self):
  data=(R/'installer.literal.py').read_text();ast.parse(data)
  self.assertIn(hashlib.sha256((R/'standalone_helper.py').read_bytes()).hexdigest(),data)
  self.assertNotIn('UNRESOLVED_FULL_COMMIT_BEFORE_EXECUTION',data);self.assertNotIn("context['workflow_sha']!=context['head_sha']",data);self.assertIn('os.O_EXCL',data);self.assertIn('INSTALL_FAILURE_CLEANUP',data)
 def test_deterministic_generation(self):
  import subprocess
  names=['standalone_helper.py','installer.literal.py','cleanup.literal.py','gate.literal.py','SNAPSHOT-WORKFLOW.INERT.yml','literal-bindings.json']
  before={n:(R/n).read_bytes() for n in names}
  subprocess.run(['python3',str(R/'build_literal.py')],check=True,capture_output=True)
  self.assertEqual(before,{n:(R/n).read_bytes() for n in names})
 def test_gate_before_sudo_and_required_inputs(self):
  y=(R/'SNAPSHOT-WORKFLOW.INERT.yml').read_text();self.assertLess(y.index('Exact managed dispatch gate'),y.index('sudo -n'))
  self.assertIn('expected_head:',y);self.assertIn('expected_ref:',y);self.assertNotIn('expected_workflow_sha:',y)
 def test_no_checkout_and_cleanup_owned_success(self):
  y=(R/'SNAPSHOT-WORKFLOW.INERT.yml').read_text();self.assertNotIn('uses:',y);self.assertIn('if: false',y);self.assertIn("steps.install.outputs.installed == 'true'",y)
  for k in ['ImageOS','ImageVersion','runner.environment','github.run_id','github.run_attempt','github.repository','github.workflow_sha','github.sha']:self.assertIn(k,y)
if __name__=='__main__':unittest.main()

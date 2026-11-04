"""Mocked orchestration/deadline tests, no Gradle or payloads."""
import pathlib,tempfile,types,unittest
from unittest.mock import patch
from resolve_once import run_graph_stages
class Stages(unittest.TestCase):
 def setUp(self):
  quiet=patch('resolve_once.print',create=True);quiet.start();self.addCleanup(quiet.stop)
 def test_shared_budget_order_and_flags(self):
  with tempfile.TemporaryDirectory() as td:
   work=pathlib.Path(td);fixture=work/'graph-fixture';fixture.mkdir();(fixture/'selected-inputs.json').write_text('{}')
   calls=[];ticks=iter([0,10,20,30,40,50,60]);codes=iter([0,0,0,1,0,0])
   def run(command,**kw):calls.append((command,kw));return types.SimpleNamespace(returncode=next(codes))
   self.assertEqual(run_graph_stages('/private/gradle',work,{},work/'broker.sock',work,lambda:next(ticks),run),0)
   self.assertEqual([kw['timeout'] for cmd,kw in calls],[1190,1180,1170,1160,1150,1140])
   for cmd,kw in calls:
    self.assertIn('--inside',cmd)
   gradle=[cmd for cmd,kw in calls if '/private/gradle' in cmd]
   self.assertEqual(len(gradle),3)
   for cmd in gradle:
    for flag in ['--no-daemon','--max-workers=2','--no-configuration-cache']:self.assertIn(flag,cmd)
   self.assertIn('--offline',gradle[0]);self.assertIn('--offline',gradle[1]);self.assertNotIn('--offline',gradle[2])
   self.assertFalse((fixture/'selected-inputs.json').exists())
 def test_failed_fixture_check_never_runs_actual(self):
  with tempfile.TemporaryDirectory() as td:
   codes=iter([0,0,1]);calls=[]
   def run(command,**kw):calls.append(command);return types.SimpleNamespace(returncode=next(codes))
   with self.assertRaisesRegex(RuntimeError,'fixture_check_success'):run_graph_stages('/private/gradle',pathlib.Path(td),{},'broker',td,lambda:0,run)
   self.assertEqual(len(calls),3)
 def test_second_fixture_assertion_gates_actual(self):
  with tempfile.TemporaryDirectory() as td:
   work=pathlib.Path(td);fixture=work/'graph-fixture';fixture.mkdir();(fixture/'selected-inputs.json').write_text('{}')
   codes=iter([0,0,0,1,1]);calls=[]
   def run(command,**kw):calls.append(command);return types.SimpleNamespace(returncode=next(codes))
   with self.assertRaisesRegex(RuntimeError,'fixture_check_unresolved'):run_graph_stages('/private/gradle',work,{},'broker',td,lambda:0,run)
   self.assertEqual(len(calls),5)
 def test_deadline_applies_before_every_invocation(self):
  ticks=iter([0,1201]);calls=[]
  with self.assertRaisesRegex(RuntimeError,'SHARED_GRAPH_STAGE_DEADLINE'):run_graph_stages('gradle',pathlib.Path('/unused'),{},'broker','tmp',lambda:next(ticks),lambda *a,**k:calls.append(a))
  self.assertFalse(calls)
 def test_no_arbitrary_error_description_in_inventory(self):
  s=pathlib.Path(__file__).with_name('local-repositories.init.gradle').read_text()
  for token in ['failure.toString()', 'getFailure().toString()', 'reason.getDescription()', 'dependency.toString()']:self.assertNotIn(token,s)
  for token in ['UNRESOLVED_DEPENDENCY','GRAPH_INSPECTION_EXCEPTION','reason.getCause().toString()']:self.assertIn(token,s)
if __name__=='__main__':unittest.main()

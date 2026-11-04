import unittest
from host_contract import canonical,HostPathStop,closure
from static_diagnostic import StaticPhaseStop
from test_host_contract import elf,row,P
import test_host_contract as fixture
class PathDiagnostic(unittest.TestCase):
 def test_categories_no_values(self):
  for path,category in [(None,'NOT_STRING'),('relative','NOT_ABSOLUTE'),('/'+'a'*512,'LENGTH'),('/bad secret','CHARSET'),('/a/./b','DOT_COMPONENT'),('/a/../b','PARENT_COMPONENT')]:
   with self.assertRaises(HostPathStop) as cm:canonical(path,'RUNPATH_EXPANDED')
   diag=StaticPhaseStop(cm.exception,'CLOSURE').checkpoint
   self.assertEqual(diag['reason'],'HOST_PATH');self.assertEqual(diag['path_category'],{'context':'RUNPATH_EXPANDED','category':category});self.assertNotIn('secret',str(diag))
 def test_origin_parent_contract_still_stops(self):
  m=fixture.Host().mapping();m[P+'clang-21']=row(P+'clang-21',elf(['libc.so.6'],'/lib64/ld-linux-x86-64.so.2',runpath='$ORIGIN/../lib'))
  with self.assertRaises(HostPathStop) as cm:closure({'clang21':P+'clang-21','lld':P+'lld'},m.get)
  self.assertEqual(cm.exception.path_category,{'context':'RUNPATH_EXPANDED','category':'PARENT_COMPONENT'})
 def test_start_row_alias_contexts_and_acceptance_unchanged(self):
  self.assertEqual(canonical('/a//b/'),'/a/b')
  for context in ['START','ROW_CANONICAL','ALIAS_COMPONENT']:
   with self.assertRaises(HostPathStop) as cm:canonical('/a/../b',context)
   self.assertEqual(cm.exception.path_category['context'],context)

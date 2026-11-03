import copy,json,pathlib,unittest,subprocess
from test_evidence import Evidence
import check_case as c
class Manual(Evidence):
 def test_finite_snapshot_refusals(self):
  f=self.e/'original/managed-0-capture-bracket.json';base=json.loads(f.read_text())
  for field,value in [('complete',False),('valid',False),('start_ns',9),('numbers',[float('nan')]*53),('numbers',[]),('end_ns',3000000000)]:
   v=copy.deepcopy(base);v['pre'][field]=value;f.write_text(json.dumps(v))
   with self.subTest(field=field),self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_changed_snapshot_is_explicit_not_certified(self):
  f=self.e/'original/managed-0-capture-bracket.json';v=json.loads(f.read_text());v['post']['numbers'][4]=1;v['java_state']='CHANGED';f.write_text(json.dumps(v));self.assertIn('MANUAL_REVIEW_REQUIRED',c.check(self.e/'original','original','a'*64))
 def test_query_missing_and_png_dimensions_refuse(self):
  f=self.e/'original/managed-0-capture-bracket.json';base=json.loads(f.read_text())
  for k,v in [('query_complete',0),('query_zero_cleanup',0),('bitmap',[1,1]),('manual_review','COMPLETE'),('presentation_binding','VERIFIED')]:
   b=copy.deepcopy(base);b[k]=v;f.write_text(json.dumps(b))
   with self.assertRaises(ValueError):c.check(self.e/'original','original','a'*64)
 def test_source_order_and_unchanged_legacy(self):
  root=pathlib.Path(__file__).parents[2];path='app/src/androidTest/java/dev/phonelinux/proto/MobileDesktopEntryTest.kt';s=(root/path).read_text();old=subprocess.check_output(['git','show','099074f:'+path],cwd=root,text=True)
  start=s.index('    private fun renderQualification(');end=s.index('\n    private ',start+10);a=old.index('    private fun renderQualification(');b=old.index('\n    private ',a+10);self.assertEqual(s[start:end],old[a:b])
  managed=s[s.index('    private fun managedComparison('):s.index('    /** Explicitly gated managed')]
  self.assertNotIn('renderQualification(rootPid',managed);self.assertNotIn('mapRect(',managed);self.assertNotIn('sx/sy',managed)
  self.assertLess(managed.index('File(out,"managed-$i.png")'),managed.index('check(snapBefore.valid()'))
  self.assertIn('sections0-5 session/provisioning',managed)
if __name__=='__main__':unittest.main()

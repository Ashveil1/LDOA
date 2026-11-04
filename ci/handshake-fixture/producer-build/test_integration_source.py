import unittest,tempfile,pathlib,hashlib,time
from unittest.mock import patch
from stage_manifest import stage
from contracts import bind_manifest,Stop
import budget,entry
class Integration(unittest.TestCase):
 def prepare(self,d):
  r=pathlib.Path(d);src=r/'src';src.mkdir();(src/'x').write_bytes(b'x');(src/'y').write_bytes(b'y')
  m={'files':[{'path':n,'type':'file','bytes':1,'sha256':hashlib.sha256(n.encode()).hexdigest()} for n in ['x','y']]+[{'path':'lib/a','type':'symlink','target':'/x'},{'path':'lib/b','type':'symlink','target':'a'}]}
  stage(src,r/'out',m,time.monotonic()+5);return r/'out',m
 def test_canonical_absolute_chain_exact_hashes(self):
  with tempfile.TemporaryDirectory() as d:
   root,m=self.prepare(d);self.assertEqual(len(bind_manifest(root,m)),2)
 def test_other_approved_inroot_file_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root,m=self.prepare(d);p=root/'usr/lib/a';p.unlink();p.symlink_to('../../y')
   with self.assertRaisesRegex(Stop,'STAGED_LINK'):bind_manifest(root,m)
 def test_shortcut_same_final_target_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root,m=self.prepare(d);p=root/'usr/lib/b';p.unlink();p.symlink_to('../../x')
   with self.assertRaisesRegex(Stop,'STAGED_LINK'):bind_manifest(root,m)
 def test_unknown_alias_same_target_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root,m=self.prepare(d);(root/'extra').symlink_to('x')
   with self.assertRaisesRegex(Stop,'UNRECORDED_SYSROOT_LINK'):bind_manifest(root,m)
 def test_changed_final_bytes_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root,m=self.prepare(d);(root/'x').write_bytes(b'z')
   with self.assertRaisesRegex(Stop,'MEMBER_BYTES'):bind_manifest(root,m)
 def test_no_reset_remaining_budget(self):
  deadline=budget.start(lambda:100);self.assertEqual(deadline,1300)
  self.assertEqual(budget.check(deadline,lambda:1100),1300)
  with self.assertRaisesRegex(Stop,'TOTAL_DEADLINE'):budget.check(deadline,lambda:1300)
 def test_invalid_nan_infinite_extended_deadline(self):
  for value in [float('nan'),float('inf'),1301]:
   with self.assertRaisesRegex(Stop,'TOTAL_DEADLINE_INVALID'):budget.check(value,lambda:100)
 def test_expired_before_inputs_no_tool_access(self):
  args=['entry.py','--sysroot','/none','--member-binding','/none','--member-sha256','h','--tool-policy','/none','--tool-policy-sha256','h','--out','/none','--deadline-monotonic','0']
  with patch('sys.argv',args),patch('entry.digest',side_effect=AssertionError('input accessed')):
   with self.assertRaisesRegex(Stop,'TOTAL_DEADLINE'):entry.main()
 def test_pipeline_begins_before_reacquire_and_same_deadline(self):
  seen=[];clean=[];times=iter([100,100,110,110,120,120,130,130,140])
  def step(deadline):seen.append(deadline)
  r=budget.qualify_pipeline(step,step,step,step,lambda:clean.append(True),lambda:next(times))
  self.assertEqual(seen,[1300]*4);self.assertEqual(clean,[True]);self.assertFalse(r['complete'])
 def test_pipeline_timeout_prevents_next_stage_and_cleans(self):
  seen=[];clean=[];times=iter([100,100,1300])
  def step(deadline):seen.append(deadline)
  with self.assertRaisesRegex(Stop,'TOTAL_DEADLINE'):budget.qualify_pipeline(step,step,step,step,lambda:clean.append(True),lambda:next(times))
  self.assertEqual(seen,[1300]);self.assertEqual(clean,[True])
 def test_pipeline_failure_cleans(self):
  clean=[]
  def fail(deadline):raise Stop('SYNTHETIC_FAIL')
  with self.assertRaisesRegex(Stop,'SYNTHETIC_FAIL'):budget.qualify_pipeline(fail,fail,fail,fail,lambda:clean.append(True),lambda:100)
  self.assertEqual(clean,[True])
if __name__=='__main__':unittest.main()

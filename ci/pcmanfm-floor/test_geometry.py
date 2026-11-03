import unittest
import subprocess,time,tempfile,pathlib,os
from unittest.mock import patch
import geometry as g
from geometry import parse_geometry,parse_hints,parse_monitors,qualifies
class Policy(unittest.TestCase):
 def test_geometry(self):
  self.assertEqual((0,0,1080,1949),parse_geometry('Absolute upper-left X: 0\nAbsolute upper-left Y: 0\nWidth: 1080\nHeight: 1949\n'))
 def test_missing_fails(self):
  with self.assertRaises(AssertionError):parse_geometry('Width: 1080')
  with self.assertRaises(AssertionError):parse_hints('WM_NORMAL_HINTS: not found')
 def test_monitor_bijection(self):
  self.assertEqual((0,0,1080,1949),parse_monitors('Monitors: 1\n 0: +default 1080/286x1949/516+0+0 default'))
  with self.assertRaises(AssertionError):parse_monitors('Monitors: 2')
 def test_original_must_reproduce_exactly(self):
  self.assertTrue(qualifies('original',(0,0,1280,1949),(0,0,1080,1949),(0,0,1080,1949),[(1280,1949)]*2))
  self.assertFalse(qualifies('original',(0,0,1080,1949),(0,0,1080,1949),(0,0,1080,1949),[(1080,1949)]*2))
 def test_patch_mismatch_origin_monitor_not_skipped(self):
  self.assertFalse(qualifies('patched',(1,0,1080,1949),(0,0,1080,1949),(0,0,1080,1949),[(1080,1949)]*2))
  self.assertFalse(qualifies('patched',(0,0,1080,1949),(0,0,1080,1949),(0,0,1280,1949),[(1080,1949)]*2))
  self.assertTrue(qualifies('patched',(0,0,900,1400),(0,0,900,1400),(0,0,900,1400),[(900,1400)]*2))
class Faults(unittest.TestCase):
 def test_deadline_inside_command(self):
  p=subprocess.Popen(['sleep','3'])
  began=time.monotonic()
  try:
   with self.assertRaises(subprocess.TimeoutExpired):g.wait_command(p,time.monotonic()+.08)
   self.assertLess(time.monotonic()-began,.6)
  finally:p.kill();p.wait()
 def test_identity_registration_failure(self):
  registry=[];known={}
  with patch.object(g,'stat',side_effect=OSError('injected proc failure')):
   with self.assertRaises(OSError):g.launch(['sleep','3'],registry,known)
  self.assertEqual(len(registry),1);self.assertIsNotNone(registry[0][0].poll())
  self.assertIn('UNKNOWN',' '.join(g.cleanup_known(registry,known)))
 def test_unknown_does_not_suppress_safe_cleanup(self):
  reg=[];known={};p,s=g.launch(['sleep','3'],reg,known)
  known[99999999]={'start':'unknown'}
  real=g.stat
  def lookup(pid):
   if pid==99999999:raise PermissionError('injected unknown')
   return real(pid)
  with patch.object(g,'stat',side_effect=lookup):failures=g.cleanup_known(reg,known)
  self.assertTrue(failures);self.assertIsNotNone(p.poll())
 def test_early_leader_exit_group_child_seen(self):
  reg=[];known={}
  p,s=g.launch(['sh','-c','sleep 3 & exit 0'],reg,known);p.wait()
  members=g.scan_groups({s['pgid']})
  self.assertTrue(members)
  # New survivor is NOT silently authorized by its reused group number.
  self.assertTrue(all(pid not in known for pid in members))
  # Test teardown: trusted local child fixture identities independently read.
  for pid,member in members.items():
   if g.stat(pid)['start']==member['start']:os.kill(pid,9)
 def test_evidence_write_failure_preserves_primary(self):
  error=AssertionError('primary')
  def fail():raise OSError('full')
  self.assertIs(g.preserve_primary(error,fail),error);self.assertTrue(error.__notes__)
 def test_capture_association_change_rejected(self):
  with self.assertRaises(AssertionError):g.bracket_capture(unittest.mock.Mock(side_effect=[1,2]),lambda end:None,time.monotonic()+1,1)
  self.assertTrue(g.bracket_capture(lambda tag,end:1,lambda end:None,time.monotonic()+1,1)['bracket_equal'])
 def test_inflight_file_cap(self):
  with tempfile.TemporaryDirectory() as d:
   target=pathlib.Path(d)/'out';reg=[];known={}
   p,s=g.launch(['sh','-c','head -c 32768 /dev/zero > "$1"','sh',str(target)],reg,known,limit=4096)
   p.wait(timeout=2);self.assertNotEqual(p.returncode,0);self.assertLessEqual(target.stat().st_size,4096)
 def test_declared_case_caps(self):
  self.assertEqual(g.MAX_IMAGES,8);self.assertEqual(g.MAX_EVIDENCE_BYTES,96*1024*1024)
if __name__=='__main__':unittest.main()


import copy,pathlib,subprocess,tempfile,unittest
import geometry as g
import transport as t
class Preconditions(unittest.TestCase):
 def test_stdout_stderr_warning_separate(self):
  p=subprocess.run(['sh','-c','printf "xrandr: Failed to get size of gamma for output default\\n" >&2; printf "Monitors: 1\\n 0: +default 1280/339x1024/271+0+0  default\\n"'],capture_output=True,text=True)
  self.assertEqual(p.returncode,0);self.assertIn('gamma',p.stderr);self.assertEqual(g.parse_monitors(p.stdout),(0,0,1280,1024))
  with self.assertRaises(AssertionError):g.parse_monitors(p.stderr+p.stdout)
 def test_bad_monitor_records_rejected(self):
  for raw in ['Monitors: 0','Monitors: 2\n 0: +default 1280/339x1024/271+0+0 default','Monitors: 1\n 0: malformed','Monitors: 1\n 0: +default 1280/339x1024/271+0+0 default\nextra']:
   with self.subTest(raw=raw):
    with self.assertRaises(AssertionError):g.parse_monitors(raw)
 def test_raw_survives_parser_failure(self):
  geo='Absolute upper-left X: 0\nAbsolute upper-left Y: 48\nWidth: 1280\nHeight: 1024\n'
  record={'raw':{'desktop':geo,'root':geo,'monitors_stdout':'malformed','properties':'program specified minimum size: 1280 by 1024\nprogram specified maximum size: 1280 by 1024'},'parsed':False}
  saved=[]
  with self.assertRaises(AssertionError):g.parse_observation(record,lambda r:saved.append(copy.deepcopy(r)))
  self.assertEqual(saved[0],record);self.assertFalse(saved[0]['parsed']);self.assertIn('48',saved[0]['raw']['desktop'])
 def test_offset_still_fails(self):
  self.assertFalse(g.qualifies('patched',(0,48,1280,1024),(0,0,1280,1024),(0,0,1280,1024),[(1280,1024)]*2))
 def test_config_no_tray_identical_codepath(self):
  src=pathlib.Path(g.__file__).read_text();self.assertIn("cfg.write_text('<JWM><RootMenu/></JWM>')",src);self.assertNotIn('<Tray',src)
  self.assertTrue(t.allowed('original/jwmrc'));self.assertTrue(t.allowed('original/command-021.stderr.txt'))
 def test_actual_search_exit_policy(self):
  for script,accepted in [('exit 1',True),('printf warning >&2; exit 1',False),('printf output; exit 1',False),('exit 2',False)]:
   p=subprocess.run(['sh','-c',script],capture_output=True,text=True)
   self.assertEqual(g.command_ok(p.returncode,p.stdout,p.stderr,empty=True),accepted)
  self.assertFalse(g.command_ok(1,'','',empty=False))
if __name__=='__main__':unittest.main()


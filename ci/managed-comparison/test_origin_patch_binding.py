import hashlib,pathlib,re,subprocess,tempfile,tarfile,unittest
import directory_plan,paired_repro
class PatchBinding(unittest.TestCase):
 def test_exact_full_patch_and_all_consumers(self):
  root=pathlib.Path(__file__).parents[2];d=root/'ci/managed-comparison';patch=(d/'default-size.patch').read_bytes();pin=hashlib.sha256(patch).hexdigest()
  self.assertEqual(pin,'056bd20e1914f35cfe8924ab0a6044dd2c5e9f5366eef9104276ed1ccfe8ae63')
  self.assertEqual(directory_plan.PATCH,pin);self.assertEqual(paired_repro.SOURCE['default-size.patch'],pin)
  for n in ['build-and-test.sh','prepare_incoming.py','test_prepare_incoming.py']:self.assertIn(pin,(d/n).read_text())
  kotlin=root/'app/src/androidTest/java/dev/phonelinux/proto/ManagedIncomingForTest.kt'
  self.assertIn('check(files.getString("source/default-size.patch")=="'+pin+'")',kotlin.read_text())
  # All tracked execution and fixture files, plus this new fixture. Avoid embedding
  # the retired literal here so a repository-wide exact-hash scan can enforce it.
  retired='ae69d35d1215efc6e521c7fbe81ceacb'+'261fd0c4917c2828ffb385bded7d6829'
  paths=subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
  for rel in paths:
   p=root/rel
   if p.is_file() and p.suffix in {'.kt','.py','.sh','.yml','.json'}:
    self.assertNotIn(retired,p.read_text(errors='replace'),rel)
  text=patch.decode();self.assertIn('gtk_window_set_default_size((GtkWindow*)desktop, geom.width, geom.height)',text);self.assertIn('+++ b/src/origin_policy.h',text);self.assertIn('origin_arm_initial(self)',text)
  self.assertIn('gtk_widget_in_destruction',text);self.assertIn('gdk_window_get_origin',text);self.assertIn('ORIGIN_DEADLINE_US=2000000',text);self.assertIn('ORIGIN_EVENT_CAP=32',text)
 def test_original_remains_unpatched_and_workflow_unchanged(self):
  root=pathlib.Path(__file__).parents[2];s=(root/'ci/managed-comparison/build-and-test.sh').read_text()
  self.assertIn('if [[ $case == patched ]]; then patch -p1',s)
  self.assertIn("assert man[0]==man[1], 'resource-prefix confound'",s)
  self.assertEqual(hashlib.sha256((root/'.github/workflows/mobile-desktop.yml').read_bytes()).hexdigest(),'bc56d97ab810ab701d8d0f7e40347685d4454c5e5923c206aea1827009e5a8bb')
if __name__=='__main__':unittest.main()

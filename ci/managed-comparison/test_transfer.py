import pathlib,unittest,subprocess,os
import test_copy_identity
P=pathlib.Path(__file__).parent
class Transfer(test_copy_identity.Copy):
 def test_fresh_no_files(self):
  x,a=self.execute('fresh');self.assertEqual(x.returncode,0,x.stderr);self.assertTrue((a/'files/managed-incoming/payload/bin/fixture').is_file());self.assertIn("here=$(stat -Lc '%d:%i:%u:%g' .)",(a.parent/'calls').read_text());self.assertIn('[ "$here" = "$home" ] || exit 76',(a.parent/'calls').read_text())
 def test_existing_safe_files(self):
  x,a=self.execute('safe');self.assertEqual(x.returncode,0,x.stderr);self.assertEqual((a/'files/keep').read_text(),'keep')
 def test_symlink_parent(self):
  x,a=self.execute('link');self.assertNotEqual(x.returncode,0);self.assertFalse((a.parent/'incoming/managed-incoming').exists())
 def test_nondirectory_parent(self):
  x,a=self.execute('file');self.assertNotEqual(x.returncode,0);self.assertEqual((a/'files').read_text(),'keep')
 def test_existing_incoming(self):
  x,a=self.execute('existing');self.assertNotEqual(x.returncode,0);self.assertEqual((a/'files/managed-incoming/keep').read_text(),'keep');self.assertFalse((a/'files/managed-incoming/payload').exists())
 def test_dangling_incoming(self):
  x,a=self.execute('dangling');self.assertNotEqual(x.returncode,0);self.assertTrue((a/'files/managed-incoming').is_symlink())
 def test_wrong_runas_context(self):
  x,a=self.execute('wrong-cwd');self.assertEqual(x.returncode,76);self.assertFalse((a/'files').exists())
 def test_refusal_preflight_before_push(self):
  s=(P/'incoming-transfer.sh').read_text();pre=s.index('test ! -e files/managed-incoming');tmp=s.index('test ! -e /data/local/tmp/ldoa-managed-incoming');push=s.index('adb -s "$SERIAL" push')
  self.assertLess(pre,push);self.assertLess(tmp,push);self.assertIn('test ! -L /data/local/tmp/ldoa-managed-incoming',s)
  # Allow exactly host-private cleanup, never destructive device cleanup.
  device=s.replace('rm -f "$COPY_BODY"','').replace('rm -rf "$PLAN"','')
  self.assertNotIn('rm ',device);self.assertNotIn('mkdir -p',s)
  self.assertIn('guard tmp-scaffold',s);self.assertIn('guard tmp-inventory',s)
 def test_copy_failure_exact(self):
  x,a=self.execute('copy-fail');self.assertEqual(x.returncode,37);self.assertTrue((a/'files/managed-incoming').is_dir());self.assertFalse((a/'files/managed-incoming/payload/bin/fixture').exists())
  # Failed exclusive destination cannot be silently reused on retry.
  line=next(l for l in (P/'incoming-transfer.sh').read_text().splitlines() if '&& cp -R' in l)
  self.assertNotEqual(subprocess.run(['sh','-c',line],cwd=a,capture_output=True).returncode,0)
if __name__=='__main__':unittest.main()

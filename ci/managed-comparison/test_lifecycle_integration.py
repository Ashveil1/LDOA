import pathlib,unittest
class Wiring(unittest.TestCase):
 def test_actual_wrapper_and_shared_order(self):
  root=pathlib.Path(__file__).parents[2]
  s=(root/'app/src/androidTest/java/dev/phonelinux/proto/MobileDesktopEntryTest.kt').read_text();s=s[s.index('    private fun managedComparison('):s.index('    /** Explicitly gated managed')]
  shared=(root/'app/src/testShared/java/dev/phonelinux/proto/ManagedXtermLifecycle.kt').read_text()
  self.assertIn('lifecycle.query(transition,op)',s)
  self.assertNotIn('requireGuest(result,"managed restore")',s)
  self.assertLess(shared.index('emit("LIFE_QUERY'),shared.index('check(q.rc==0'))
  self.assertLess(shared.index('restoreObligation=true;'),shared.index('action()'))
  self.assertIn('lifecycle.finish(primary)',s);self.assertIn('lifecycle.restore { restoreXterm() }',s)
  self.assertIn('getwindowfocus',s);self.assertIn('check(inputFocus==xtId)',s)
  self.assertNotIn('val xt=RenderProbeParse.searchIds',s)
  self.assertIn('selectWm(wmOwners)',s)
  self.assertLess(s.index('lifecycle.prepare(i,'),s.index('val rootRaw=guest'))
  self.assertIn('lifecycle.requireUnmapped(i,"PRE_CAPTURE")',s)
  self.assertIn('lifecycle.observe(i,"POST_CAPTURE")',s)
  self.assertIn('cap=16384',s)
if __name__=='__main__':unittest.main()

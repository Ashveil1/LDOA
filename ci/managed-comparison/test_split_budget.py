import unittest,pathlib,yaml,subprocess,hashlib
class Budget(unittest.TestCase):
 def test_all_steps_bounded_and_total(self):
  jobs=yaml.safe_load((pathlib.Path(__file__).parents[2]/'.github/workflows/mobile-desktop.yml').read_text())['jobs']
  self.assertEqual(set(jobs),{'repro_first','repro_second'})
  for job in jobs.values():
   self.assertEqual(job['timeout-minutes'],130)
   caps=[s['timeout-minutes'] for s in job['steps']]
   self.assertEqual(caps,[1,4,1,1,8,15,32,2,63,2])
   self.assertEqual(sum(caps),129);self.assertEqual(130-sum(caps),1)
   dependency=job['steps'][4]['run'];self.assertIn('timeout -k 5 120',dependency);self.assertIn('timeout -k 10 300',dependency)
   self.assertGreater(8*60,(120+5)+(300+10))
  self.assertEqual(jobs['repro_second']['needs'],'repro_first')
  self.assertEqual(sum(j['timeout-minutes'] for j in jobs.values()),260)
 def test_only_reallocated_caps_and_all_hash_consumers(self):
  root=pathlib.Path(__file__).parents[2];rel='.github/workflows/mobile-desktop.yml'
  old=subprocess.check_output(['git','show','22d480913d3b746659d2bc2d0d576ac20218804c:'+rel],cwd=root,text=True)
  new=(root/rel).read_text()
  self.assertEqual(new,old.replace('uses: actions/checkout@v4\n        timeout-minutes: 2','uses: actions/checkout@v4\n        timeout-minutes: 4').replace('timeout-minutes: 65','timeout-minutes: 63'))
  pin=hashlib.sha256(new.encode()).hexdigest()
  self.assertIn(pin,(root/'ci/managed-comparison/test_origin_patch_binding.py').read_text())
  retired='9adaaa17fcc393c677a507b0a3253fb35b2'+'7d03d719883132ac3ec067a1d7f60'
  for rel in subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0'):
   p=root/rel
   if p.is_file() and p.suffix in {'.py','.sh','.kt','.yml','.json'}:self.assertNotIn(retired,p.read_text(errors='replace'),rel)

if __name__=='__main__':unittest.main()

import unittest,pathlib,yaml
class Budget(unittest.TestCase):
 def test_all_steps_bounded_and_total(self):
  jobs=yaml.safe_load((pathlib.Path(__file__).parents[2]/'.github/workflows/mobile-desktop.yml').read_text())['jobs']
  self.assertEqual(set(jobs),{'repro_first','repro_second'})
  for job in jobs.values():
   self.assertEqual(job['timeout-minutes'],130)
   caps=[s['timeout-minutes'] for s in job['steps']]
   self.assertEqual(caps,[1,2,1,1,8,15,32,2,65,2])
   self.assertEqual(sum(caps),129);self.assertEqual(130-sum(caps),1)
   dependency=job['steps'][4]['run'];self.assertIn('timeout -k 5 120',dependency);self.assertIn('timeout -k 10 300',dependency)
   self.assertGreater(8*60,(120+5)+(300+10))
  self.assertEqual(jobs['repro_second']['needs'],'repro_first')
if __name__=='__main__':unittest.main()

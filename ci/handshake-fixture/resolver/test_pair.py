import unittest,pathlib,tempfile,os,subprocess,socket
class Pair(unittest.TestCase):
 def test_exact_pair_boundary(self):
  here=pathlib.Path(__file__).parent
  with tempfile.TemporaryDirectory() as d:
   broker=socket.socket(socket.AF_UNIX);path=str(pathlib.Path(d)/'host-broker.sock');broker.bind(path);broker.listen(1);broker.settimeout(.1)
   r=subprocess.run(['unshare','-Urn','python3',str(here/'confinement.py'),'--target','python3',str(here/'test_pair_target.py')],env=dict(os.environ,HS_TEST_BROKER_PATH=path),capture_output=True,text=True,timeout=10)
   self.assertEqual(r.returncode,0,r.stderr);print('PAIR_BOUNDARY',r.stdout.strip())
   with self.assertRaises(socket.timeout):broker.accept()
   broker.close()
if __name__=='__main__':unittest.main()

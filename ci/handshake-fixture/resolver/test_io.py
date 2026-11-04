import unittest,urllib.error,socket,ssl,errno,tempfile,pathlib,json,unittest.mock
from transport import Store,failure_info
class IO(unittest.TestCase):
 def test_fixed_type_categories(self):
  cases=[(TimeoutError('secret URL'),'UPSTREAM_TIMEOUT'),(socket.gaierror(-2,'secret hostname'),'UPSTREAM_DNS'),(ssl.SSLError('secret certificate'),'UPSTREAM_TLS'),(ConnectionResetError(errno.ECONNRESET,'secret'),'UPSTREAM_CONNECTION'),(OSError(errno.EIO,'secret'),'UPSTREAM_IO_OTHER')]
  for error,expected in cases:
   for e in [error,urllib.error.URLError(error)]:self.assertEqual(failure_info(e),{'reason':expected})
  self.assertEqual(failure_info(urllib.error.URLError('timeout DNS TLS secret')),{'reason':'UPSTREAM_IO_OTHER'})
 def test_attempt_before_io_no_retry(self):
  with tempfile.TemporaryDirectory() as d:
   s=Store(pathlib.Path(d)/'store');path='a/b/c/fail.pom'
   class Opener:
    calls=0
    def open(self,req,timeout):
     self.calls+=1;assert timeout==20
     assert s.origins[path]==[{'root':'google','state':'ATTEMPTED'}]
     raise urllib.error.URLError(socket.gaierror(-2,'secret hostname query token'))
   o=Opener()
   with unittest.mock.patch('urllib.request.build_opener',return_value=o):
    with self.assertRaises(urllib.error.URLError):s.get(path)
    with self.assertRaises(ValueError):s.get(path)
   self.assertEqual(o.calls,1);self.assertEqual(s.failures[path]['reason'],'UPSTREAM_DNS');self.assertEqual(s.origins[path],[{'root':'google','state':'REFUSED','reason':'UPSTREAM_DNS'}]);self.assertNotIn('secret',json.dumps(s.summary()));self.assertEqual(s.wire,0)
if __name__=='__main__':unittest.main()

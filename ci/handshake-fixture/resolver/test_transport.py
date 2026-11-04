import unittest,tempfile,pathlib,sys,threading,subprocess,json,socket,os,urllib.request
from transport import Store,UnixHTTP,Handler,NoRedirect
class Limits(unittest.TestCase):
 def test_paths(self):
  with tempfile.TemporaryDirectory() as d:
   s=Store(pathlib.Path(d)/'s',lambda p,c:(b'x','synthetic'))
   for p in ['../a.pom','a/b/../d.pom','a/b/c/d.exe','https://evil/x.pom','a/b/c/d.pom?x','a/b/c/%2e.pom']:
    with self.assertRaises(ValueError):s.get(p)
 def test_caps_and_no_retry(self):
  with tempfile.TemporaryDirectory() as d:
   calls=[]
   def upstream(p,c):calls.append(p);return b'12345','synthetic'
   s=Store(pathlib.Path(d)/'s',upstream,total=8,entries=2,artifact=4,metadata=5)
   self.assertEqual(s.get('a/b/c/d.pom'),b'12345');self.assertEqual(s.get('a/b/c/d.pom'),b'12345');self.assertEqual(len(calls),1)
   with self.assertRaises(ValueError):s.get('a/b/c/e.pom')
   with self.assertRaises(ValueError):s.get('a/b/c/e.pom')
   self.assertEqual(len(calls),2);s.close();self.assertEqual(json.loads((s.root/'transport-receipt.json').read_text())['bytes'],5)
 def test_artifact_and_entry_cap(self):
  with tempfile.TemporaryDirectory() as d:
   s=Store(pathlib.Path(d)/'s',lambda p,c:(b'12345','synthetic'),artifact=4,entries=1)
   with self.assertRaises(ValueError):s.get('a/b/c/one.jar')
   with self.assertRaisesRegex(ValueError,'ENTRY_CAP'):s.get('a/b/c/two.jar')
 def test_cache_mutation(self):
  with tempfile.TemporaryDirectory() as d:
   s=Store(pathlib.Path(d)/'s',lambda p,c:(b'x','synthetic'));s.get('a/b/c/x.pom');(s.root/'a/b/c/x.pom').write_bytes(b'y')
   with self.assertRaisesRegex(ValueError,'CACHE_MUTATION'):s.get('a/b/c/x.pom')
 def test_redirect_denial(self):
  with self.assertRaises(ValueError):NoRedirect().redirect_request(None,None,302,'',{},'https://evil.invalid')
 def test_real_namespace_denial_and_bridge(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);s=Store(root/'store',lambda p,c:(b'<project>synthetic</project>','synthetic'))
   server=UnixHTTP(str(root/'bridge.sock'),Handler);server.store=s;threading.Thread(target=server.serve_forever,daemon=True).start()
   here=pathlib.Path(__file__).parent
   cmd=['unshare','--user','--map-root-user','--net','python3',str(here/'confinement.py'),'--inside',str(root/'bridge.sock'),'python3',str(here/'test_target.py')]
   write=root/'write';write.mkdir();env=dict(os.environ,HS_TARGET_WRITE_ROOT=str(write),HS_TEST_OUTSIDE=str(root/'must-not-write'))
   r=subprocess.run(cmd,capture_output=True,text=True,timeout=15,env=env)
   server.shutdown();server.server_close();s.close();self.assertEqual(r.returncode,0,r.stderr);print('ACTUAL_DENY_RESULT',r.stdout.strip());self.assertEqual(s.total,28)
if __name__=='__main__':unittest.main()

class RealHTTP(unittest.TestCase):
 def test_http_denials_and_success(self):
  import transport,http.server
  class Origin(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    if self.path.endswith('redirect.pom'):
     self.send_response(302);self.send_header('Location','https://evil.invalid/');self.end_headers();return
    data=b'12345'
    self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
   def log_message(self,*a):pass
  server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Origin);threading.Thread(target=server.serve_forever,daemon=True).start()
  original=transport.ROOTS;transport.ROOTS=('http://127.0.0.1:'+str(server.server_port)+'/',)
  try:
   with tempfile.TemporaryDirectory() as d:
    s=Store(pathlib.Path(d)/'s',total=9,metadata=5)
    self.assertEqual(s.get('a/b/c/ok.pom'),b'12345')
    with self.assertRaises(ValueError):s.get('a/b/c/over.pom')
    with self.assertRaises(ValueError):s.get('a/b/c/redirect.pom')
    self.assertEqual(s.total,5);self.assertEqual(s.wire,5)
  finally:transport.ROOTS=original;server.shutdown();server.server_close()

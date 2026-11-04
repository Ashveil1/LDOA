import unittest,tempfile,pathlib,threading,http.server,http.client,socket,transport,json
class Semantics(unittest.TestCase):
 def test_real_get_head_negative_cache_and_refusals(self):
  calls=[]
  class Origin(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    calls.append(self.path)
    if 'fallback' in self.path and self.server.first:self.send_response(404);self.end_headers();return
    if 'mixed' in self.path:self.send_response(404 if self.server.first else 403);self.end_headers();return
    if 'missing' in self.path:self.send_response(404);self.end_headers();return
    if 'forbidden' in self.path:self.send_response(403);self.end_headers();return
    if 'redirect' in self.path:self.send_response(302);self.send_header('Location','http://evil.invalid/token?secret');self.end_headers();return
    self.send_response(200)
    if 'cap' in self.path:self.send_header('Content-Length','100')
    elif 'truncated' in self.path:self.send_header('Content-Length','8')
    else:self.send_header('Content-Length','2')
    self.end_headers();self.wfile.write(b'OK');self.close_connection=True
   def log_message(self,*a):pass
  origins=[http.server.ThreadingHTTPServer(('127.0.0.1',0),Origin) for _ in range(2)]
  for i,o in enumerate(origins):o.first=i==0
  for o in origins:threading.Thread(target=o.serve_forever,daemon=True).start()
  old=transport.ROOTS;transport.ROOTS=tuple('http://127.0.0.1:'+str(o.server_port)+'/' for o in origins)
  try:
   with tempfile.TemporaryDirectory() as d:
    root=pathlib.Path(d);store=transport.Store(root/'store',total=32,metadata=10)
    server=transport.UnixHTTP(str(root/'broker.sock'),transport.Handler);server.store=store;threading.Thread(target=server.serve_forever,daemon=True).start()
    def request(method,name):
     c=http.client.HTTPConnection('local');c.sock=socket.socket(socket.AF_UNIX);c.sock.connect(str(root/'broker.sock'));c.request(method,'/a/b/c/'+name+'.pom');r=c.getresponse();status=r.status;data=r.read();c.close();return status,data
    self.assertEqual(request('HEAD','ok'),(200,b''));self.assertEqual(request('GET','ok'),(200,b'OK'));self.assertEqual(calls.count('/a/b/c/ok.pom'),1)
    self.assertEqual(request('GET','fallback'),(200,b'OK'));self.assertEqual(calls.count('/a/b/c/fallback.pom'),2)
    self.assertEqual(store.origins['a/b/c/fallback.pom'],[{'root':'google','status':404},{'root':'central','status':200}])
    for method in ['HEAD','GET','HEAD']:self.assertEqual(request(method,'missing')[0],404)
    self.assertEqual(calls.count('/a/b/c/missing.pom'),2) # one call to each fixed root, never retry
    for name,reason in [('mixed','UPSTREAM_HTTP_STATUS'),('forbidden','UPSTREAM_HTTP_STATUS'),('redirect','UPSTREAM_REDIRECT_REFUSED'),('cap','CONTENT_LENGTH_CAP'),('truncated','TRUNCATED_RESPONSE')]:
     self.assertEqual(request('HEAD',name)[0],502);before=len(calls);self.assertEqual(request('GET',name)[0],502);self.assertEqual(len(calls),before)
     self.assertEqual(store.failures['a/b/c/'+name+'.pom']['reason'],reason)
    self.assertEqual(store.failures['a/b/c/forbidden.pom']['upstream_status'],403)
    # Negative responses consume the distinct-attempt cap, not cache/body bytes.
    store.entrycap=len(store.receipts)+len(store.failed)
    self.assertEqual(request('HEAD','new')[0],502);self.assertEqual(store.http['<OVERFLOW>']['reason'],'ENTRY_CAP')
    server.shutdown();server.server_close();store.close();text=json.dumps(store.summary());self.assertNotIn('secret',text);self.assertNotIn('evil',text)
    self.assertEqual(store.total,4);self.assertEqual(store.wire,6);self.assertEqual(store.summary()['cached_responses'],2)
    print('HTTP_SEMANTICS',json.dumps(store.summary(),sort_keys=True))
  finally:
   transport.ROOTS=old
   for o in origins:o.shutdown();o.server_close()
if __name__=='__main__':unittest.main()

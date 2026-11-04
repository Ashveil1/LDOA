"""Fixed bounded Maven transport. Host process only; target gets no external network."""
import http.server,socketserver,urllib.request,urllib.error,urllib.parse,pathlib,hashlib,json,threading,re,os
ROOTS=('https://dl.google.com/dl/android/maven2/','https://repo.maven.apache.org/maven2/')
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*a,**kw):raise ValueError('UPSTREAM_REDIRECT_REFUSED')
class Store:
 def __init__(self,root,upstream=None,total=512*1024**2,entries=4096,artifact=64*1024**2,metadata=2*1024**2):
  self.root=pathlib.Path(root);self.root.mkdir(mode=0o700);self.upstream=upstream or self.fetch;self.totalcap=total;self.entrycap=entries;self.artcap=artifact;self.metacap=metadata;self.total=0;self.wire=0;self.receipts={};self.failed=set();self.lock=threading.Lock()
 def fetch(self,path,cap):
  opener=urllib.request.build_opener(NoRedirect())
  for base in ROOTS:
   url=base+path
   try:
    with opener.open(urllib.request.Request(url,headers={'Accept-Encoding':'identity'}),timeout=20) as r:
     if r.status!=200 or r.url!=url or r.headers.get('Content-Encoding','identity')!='identity':raise ValueError('UPSTREAM_RESPONSE_SCOPE')
     n=r.headers.get('Content-Length')
     remaining=self.totalcap-self.wire
     if remaining<=0:raise ValueError('WIRE_TOTAL_CAP')
     if n is None or int(n)<0 or int(n)>min(cap,remaining):raise ValueError('CONTENT_LENGTH_CAP')
     chunks=[];left=int(n)
     while left:
      b=r.read(min(left,65536));self.wire+=len(b)
      if not b:raise ValueError('TRUNCATED_RESPONSE')
      chunks.append(b);left-=len(b)
     data=b''.join(chunks)
     if len(data)>cap:raise ValueError('FILE_CAP')
     if n and len(data)!=int(n):raise ValueError('TRUNCATED_RESPONSE')
     return data,url
   except urllib.error.HTTPError as e:
    if e.code==404:continue
    raise
  raise ValueError('NOT_FOUND_BOTH_OFFICIAL_ROOTS')
 def get(self,path):
  if not re.fullmatch(r'[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+){3,}',path) or any(p in ('.','..') for p in path.split('/')):raise ValueError('PATH_SCOPE')
  if not path.endswith(('.pom','.module','.jar','.aar','.sha1','.sha256','.sha512','.md5','maven-metadata.xml')):raise ValueError('EXTENSION_SCOPE')
  with self.lock:
   if path in self.failed:raise ValueError('NO_RETRY_FAILED_PATH')
   if path in self.receipts:
    cached=(self.root/path).read_bytes()
    if len(cached)!=self.receipts[path]['bytes'] or hashlib.sha256(cached).hexdigest()!=self.receipts[path]['sha256']:raise ValueError('CACHE_MUTATION')
    return cached
   if len(self.receipts)+len(self.failed)>=self.entrycap:raise ValueError('ENTRY_CAP')
   cap=self.artcap if path.endswith(('.jar','.aar')) else self.metacap;cap=min(cap,self.totalcap-self.total)
   if cap<=0:raise ValueError('TOTAL_CAP')
   self.failed.add(path) # First attempt consumes this path even on exception.
   data,url=self.upstream(path,cap)
   if len(data)>cap:raise ValueError('FILE_OR_TOTAL_CAP')
   dest=self.root/path;dest.parent.mkdir(parents=True,exist_ok=True)
   fd=os.open(dest,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
   with os.fdopen(fd,'wb') as f:f.write(data)
   self.total+=len(data);self.failed.remove(path);self.receipts[path]={'path':path,'url':url,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
   return data
 def close(self):
  (self.root/'transport-receipt.json').write_text(json.dumps({'bytes':self.total,'wire_bytes':self.wire,'entries':list(self.receipts.values()),'failed_paths':sorted(self.failed),'bounded':True},indent=2))
class UnixHTTP(socketserver.ThreadingMixIn,socketserver.UnixStreamServer):daemon_threads=True
class Handler(http.server.BaseHTTPRequestHandler):
 def do_HEAD(self):self.reply(False)
 def do_GET(self):self.reply(True)
 def reply(self,body):
  try:
   if not self.path.startswith('/') or '?' in self.path or '%' in self.path:raise ValueError('PATH_SCOPE')
   data=self.server.store.get(self.path[1:]);self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers()
   if body:self.wfile.write(data)
  except Exception:self.send_error(502,'BOUNDED_TRANSPORT_REFUSED')
 def log_message(self,*a):pass

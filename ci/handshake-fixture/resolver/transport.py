"""Fixed bounded Maven transport. Host process only; target gets no external network."""
import socket,ssl,errno
import http.server,socketserver,urllib.request,urllib.error,urllib.parse,pathlib,hashlib,json,threading,re,os
ROOTS=('https://dl.google.com/dl/android/maven2/','https://repo.maven.apache.org/maven2/')
class Missing(ValueError):pass
class Refused(ValueError):
 def __init__(self,reason,status=None):self.reason=reason;self.upstream_status=status;super().__init__(reason)
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*a,**kw):raise ValueError('UPSTREAM_REDIRECT_REFUSED')
class Store:
 def __init__(self,root,upstream=None,total=512*1024**2,entries=4096,artifact=64*1024**2,metadata=2*1024**2):
  self.root=pathlib.Path(root);self.root.mkdir(mode=0o700);self.upstream=upstream or self.fetch;self.totalcap=total;self.entrycap=entries;self.artcap=artifact;self.metacap=metadata;self.total=0;self.wire=0;self.receipts={};self.failed=set();self.failures={};self.http={};self.origins={};self.lock=threading.Lock()
 def fetch(self,path,cap):
  opener=urllib.request.build_opener(NoRedirect())
  self.origins[path]=[]
  for index,base in enumerate(ROOTS):
   root_id=('google','central')[index]
   url=base+path
   attempt={'root':root_id,'state':'ATTEMPTED'};self.origins[path].append(attempt)
   try:
    with opener.open(urllib.request.Request(url,headers={'Accept-Encoding':'identity'}),timeout=20) as r:
     attempt.update(state='HTTP_RESPONSE',status=r.status)
     if r.status!=200 or r.url!=url or r.headers.get('Content-Encoding','identity')!='identity':raise ValueError('UPSTREAM_RESPONSE_SCOPE')
     n=r.headers.get('Content-Length')
     remaining=self.totalcap-self.wire
     if remaining<=0:raise ValueError('WIRE_TOTAL_CAP')
     if n is None or int(n)<0 or int(n)>min(cap,remaining):raise ValueError('CONTENT_LENGTH_CAP')
     chunks=[];left=int(n)
     while left:
      try:b=r.read(min(left,65536))
      except http.client.IncompleteRead as e:
       self.wire+=len(e.partial);raise ValueError('TRUNCATED_RESPONSE')
      self.wire+=len(b)
      if not b:raise ValueError('TRUNCATED_RESPONSE')
      chunks.append(b);left-=len(b)
     data=b''.join(chunks)
     if len(data)>cap:raise ValueError('FILE_CAP')
     if n and len(data)!=int(n):raise ValueError('TRUNCATED_RESPONSE')
     return data,url
   except urllib.error.HTTPError as e:
    status=e.code;attempt.update(state='HTTP_RESPONSE',status=status);e.close() # Do not consume or log upstream error response bodies.
    if status==404:continue
    raise Refused('UPSTREAM_HTTP_STATUS',status)
   except Exception as e:
    attempt.update(state='REFUSED',reason=failure_info(e)['reason']);raise
  raise Missing('VERIFIED_BOTH_ROOTS_404')
 def get(self,path):
  if not re.fullmatch(r'[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+){3,}',path) or any(p in ('.','..') for p in path.split('/')):raise ValueError('PATH_SCOPE')
  if not path.endswith(('.pom','.module','.jar','.aar','.sha1','.sha256','.sha512','.md5','maven-metadata.xml')):raise ValueError('EXTENSION_SCOPE')
  with self.lock:
   if path in self.failed:
    failure=self.failures.get(path,{})
    if failure.get('reason')=='VERIFIED_BOTH_ROOTS_404':raise Missing('VERIFIED_BOTH_ROOTS_404')
    raise Refused('NO_RETRY_FAILED_PATH')
   if path in self.receipts:
    cached=(self.root/path).read_bytes()
    if len(cached)!=self.receipts[path]['bytes'] or hashlib.sha256(cached).hexdigest()!=self.receipts[path]['sha256']:raise ValueError('CACHE_MUTATION')
    return cached
   if len(self.receipts)+len(self.failed)>=self.entrycap:raise ValueError('ENTRY_CAP')
   cap=self.artcap if path.endswith(('.jar','.aar')) else self.metacap;cap=min(cap,self.totalcap-self.total)
   if cap<=0:raise ValueError('TOTAL_CAP')
   self.failed.add(path) # First attempt consumes this path even on exception.
   try:data,url=self.upstream(path,cap)
   except Exception as e:
    self.failures[path]=failure_info(e);raise
   if len(data)>cap:raise ValueError('FILE_OR_TOTAL_CAP')
   dest=self.root/path;dest.parent.mkdir(parents=True,exist_ok=True)
   fd=os.open(dest,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
   with os.fdopen(fd,'wb') as f:f.write(data)
   self.total+=len(data);self.failed.remove(path);self.receipts[path]={'path':path,'url':url,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
   return data
 def record_http(self,path,method,status,reason,upstream_status=None):
  # Bound diagnostic cardinality. No raw rejected query, header, body or exception text.
  if not re.fullmatch(r'[A-Za-z0-9_.+/-]+',path) or '?' in path or '%' in path:path='<REJECTED_PATH>'
  key=path if path in self.http or len(self.http)<self.entrycap else '<OVERFLOW>'
  with self.lock:
   row=self.http.setdefault(key,{'path':key,'methods':{},'status':status,'reason':reason,'first_reason':reason})
   row['methods'][method]=row['methods'].get(method,0)+1;row['status']=status;row['reason']=reason
   if upstream_status is not None:row['upstream_status']=upstream_status
 def summary(self):
  return {'scope':'MAVEN_TRANSPORT_RECEIPT','failures':[{'path':p,**f} for p,f in self.failures.items()],'origin_statuses':[{'path':p,'roots':rows} for p,rows in self.origins.items()],'cached_bytes':self.total,'response_body_bytes':self.wire,'cached_responses':len(self.receipts),'failed_paths':len(self.failed),'http':list(self.http.values())}
 def close(self):
  (self.root/'transport-receipt.json').write_text(json.dumps({'bytes':self.total,'wire_bytes':self.wire,'entries':list(self.receipts.values()),'failed_paths':sorted(self.failed),'failures':self.failures,'http':list(self.http.values()),'bounded':True},indent=2))
def io_reason(e):
 # Unwrap only typed URLError.reason, never parse or retain its message.
 cause=e.reason if isinstance(e,urllib.error.URLError) and isinstance(e.reason,BaseException) else e
 if isinstance(cause,(TimeoutError,socket.timeout)):return 'UPSTREAM_TIMEOUT'
 if isinstance(cause,socket.gaierror):return 'UPSTREAM_DNS'
 if isinstance(cause,ssl.SSLError):return 'UPSTREAM_TLS'
 if isinstance(cause,ConnectionError) or isinstance(cause,OSError) and cause.errno in {errno.ECONNREFUSED,errno.ECONNRESET,errno.ECONNABORTED,errno.ENETUNREACH,errno.EHOSTUNREACH,errno.EPIPE}:return 'UPSTREAM_CONNECTION'
 return 'UPSTREAM_IO_OTHER'
def failure_info(e):
 if isinstance(e,Missing):return {'reason':'VERIFIED_BOTH_ROOTS_404'}
 if isinstance(e,Refused):return {'reason':e.reason,'upstream_status':e.upstream_status}
 allowed={'PATH_SCOPE','EXTENSION_SCOPE','UPSTREAM_REDIRECT_REFUSED','UPSTREAM_RESPONSE_SCOPE','WIRE_TOTAL_CAP','CONTENT_LENGTH_CAP','TRUNCATED_RESPONSE','FILE_CAP','CACHE_MUTATION','ENTRY_CAP','TOTAL_CAP','FILE_OR_TOTAL_CAP'}
 reason=str(e) if isinstance(e,ValueError) and str(e) in allowed else (io_reason(e) if isinstance(e,(OSError,urllib.error.URLError)) else 'INTERNAL_REFUSAL')
 return {'reason':reason}
class UnixHTTP(socketserver.ThreadingMixIn,socketserver.UnixStreamServer):daemon_threads=True
class Handler(http.server.BaseHTTPRequestHandler):
 def do_HEAD(self):self.reply(False)
 def do_GET(self):self.reply(True)
 def reply(self,body):
  try:
   if not self.path.startswith('/') or '?' in self.path or '%' in self.path:raise ValueError('PATH_SCOPE')
   data=self.server.store.get(self.path[1:]);self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers()
   if body:self.wfile.write(data)
  except Exception as e:
   info=failure_info(e);status=404 if isinstance(e,Missing) else 502
   self.server.store.record_http(self.path[1:],self.command,status,info['reason'],info.get('upstream_status'))
   self.send_error(status,'VERIFIED_MISSING' if status==404 else 'BOUNDED_TRANSPORT_REFUSED')
  else:self.server.store.record_http(self.path[1:],self.command,200,'CACHED_RESPONSE')
 def log_message(self,*a):pass

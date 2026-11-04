#!/usr/bin/env python3
"""Exact official digest/size transport source. Not invoked before download scope approval."""
import pathlib,urllib.request,urllib.parse,hashlib,os
class ScopedRedirect(urllib.request.HTTPRedirectHandler):
 def __init__(self,host):self.host=host
 def redirect_request(self,req,fp,code,msg,headers,newurl):
  parsed=urllib.parse.urlparse(newurl)
  if parsed.scheme!="https" or parsed.hostname!=self.host:raise ValueError("REDIRECT_SCOPE")
  return super().redirect_request(req,fp,code,msg,headers,newurl)
# Caller only passes a row from reviewed fixed lock, never workflow inputs.
def fetch(row,dest):
 if dest.exists():raise ValueError('NO_RETRY_OR_OVERWRITE')
 digest=hashlib.new('sha256' if 'sha256' in row and row['sha256']!='NOT_OBSERVED_METADATA_ONLY' else 'sha1');observed=hashlib.sha256();size=0
 try:
  origin=urllib.parse.urlparse(row['url'])
  if origin.scheme!='https':raise ValueError('HTTPS_REQUIRED')
  opener=urllib.request.build_opener(ScopedRedirect(origin.hostname))
  with opener.open(row['url'],timeout=60) as response,dest.open('xb') as out:
   if urllib.parse.urlparse(response.geturl()).hostname!=origin.hostname:raise ValueError('FINAL_HOST_SCOPE')
   while True:
    b=response.read(1048576)
    if not b:break
    size+=len(b)
    if size>row['bytes']:raise ValueError('SIZE_CAP')
    digest.update(b);observed.update(b);out.write(b)
  expected=row.get(digest.name)
  if size!=row['bytes'] or digest.hexdigest()!=expected:raise ValueError('DIGEST_OR_SIZE')
  return {'url':row['url'],'bytes':size,'official_digest':expected,'observed_sha256':observed.hexdigest()}
 except Exception:
  dest.unlink(missing_ok=True);raise

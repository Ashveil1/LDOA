"""Private Gradle 8.9, fixed official SHA256/size. Provision only after authority checkpoint."""
import urllib.request,urllib.parse,pathlib,hashlib,zipfile,os,json
START='https://services.gradle.org/distributions/gradle-8.9-bin.zip'
SIZE=136114148
SHA='d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab'
GH='https://github.com/gradle/gradle-distributions/releases/download/v8.9.0/gradle-8.9-bin.zip'
DIRECT='https://downloads.gradle.org/distributions/gradle-8.9-bin.zip'
class Chain(urllib.request.HTTPRedirectHandler):
 def __init__(self):self.urls=[START]
 def redirect_request(self,req,fp,code,msg,headers,url):
  p=urllib.parse.urlsplit(url);old=self.urls[-1]
  asset=p.scheme=='https' and p.hostname=='release-assets.githubusercontent.com' and p.path.startswith('/github-production-release-asset/696192900/') and not p.username and not p.fragment and p.port in [None,443]
  if not ((old==START and url in [GH,DIRECT]) or (old==GH and asset)) or len(self.urls)>=3:raise ValueError('GRADLE_REDIRECT_SCOPE')
  self.urls.append(url);return super().redirect_request(req,fp,code,msg,headers,url)
def extract_checked(archive,dest,expected_size=SIZE,expected_sha=SHA):
 archive=pathlib.Path(archive);dest=pathlib.Path(dest)
 if archive.stat().st_size!=expected_size or hashlib.sha256(archive.read_bytes()).hexdigest()!=expected_sha:raise ValueError('GRADLE_PIN')
 with zipfile.ZipFile(archive) as z:
  seen=set();total=0
  for i in z.infolist():
   p=pathlib.PurePosixPath(i.filename);mode=i.external_attr>>16
   if not p.parts or p.parts[0]!='gradle-8.9' or '..' in p.parts or p.is_absolute() or '\\' in i.filename or i.filename in seen or (mode&0o170000)==0o120000:raise ValueError('GRADLE_ZIP_SCOPE')
   seen.add(i.filename);total+=i.file_size
   if total>384*1024**2 or len(seen)>1024:raise ValueError('GRADLE_EXPANSION_CAP')
  if 'gradle-8.9/bin/gradle' not in seen:raise ValueError('GRADLE_LAYOUT')
  dest.mkdir(mode=0o700)
  for i in z.infolist():
   p=dest/i.filename
   if i.is_dir():p.mkdir(parents=True,exist_ok=True);continue
   p.parent.mkdir(parents=True,exist_ok=True)
   with z.open(i) as src,p.open('xb') as out:
    while True:
     b=src.read(1048576)
     if not b:break
     out.write(b)
   p.chmod(0o700 if i.filename=='gradle-8.9/bin/gradle' else 0o600)
 return dest/'gradle-8.9/bin/gradle'
def provision(root):
 root=pathlib.Path(root);archive=root/'gradle-8.9-bin.zip';chain=Chain();opener=urllib.request.build_opener(chain)
 with opener.open(urllib.request.Request(START,headers={'Accept-Encoding':'identity'}),timeout=30) as r,archive.open('xb') as out:
  if r.url!=chain.urls[-1] or r.status!=200 or r.headers.get('Content-Encoding','identity')!='identity' or int(r.headers.get('Content-Length',-1))!=SIZE:raise ValueError('GRADLE_FINAL_RESPONSE')
  n=0
  while True:
   b=r.read(min(1048576,SIZE+1-n))
   if not b:break
   n+=len(b)
   if n>SIZE:raise ValueError('GRADLE_SIZE_CAP')
   out.write(b)
 executable=extract_checked(archive,root/'gradle-private');archive.unlink()
 (root/'gradle-provision-receipt.json').write_text(json.dumps({'start':START,'final_host':urllib.parse.urlsplit(chain.urls[-1]).hostname,'bytes':SIZE,'sha256':SHA,'redirects':len(chain.urls)-1}))
 return executable

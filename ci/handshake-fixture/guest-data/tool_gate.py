"""Runtime binding of trusted runner OS tools, NOT pre-known runner binary SHA pins."""
import pathlib,subprocess,hashlib,os,stat
from guest_data_only import Stop
POLICY=[('/usr/bin/dpkg-deb','dpkg','1.21.1ubuntu','usr/bin/dpkg-deb'),('/usr/bin/readelf','binutils-x86-64-linux-gnu','2.38-4ubuntu','usr/bin/x86_64-linux-gnu-readelf')]
def qualify_one(path,package,version_prefix,member,query=subprocess.check_output):
 p=pathlib.Path(path);real=p.resolve(strict=True)
 if str(real) not in ['/usr/bin/dpkg-deb','/usr/bin/x86_64-linux-gnu-readelf']:raise Stop('TOOL_REALPATH')
 st=real.stat()
 if st.st_uid!=0 or st.st_mode&(stat.S_IWGRP|stat.S_IWOTH) or not stat.S_ISREG(st.st_mode):raise Stop('TOOL_OS_OWNERSHIP')
 version=query(['/usr/bin/dpkg-query','-W','-f=${Version}',package],timeout=10).decode().strip()
 owner=query(['/usr/bin/dpkg-query','-S',str(real)],timeout=10).decode().strip()
 if not version.startswith(version_prefix) or not owner.startswith(package+': '):raise Stop('TOOL_PACKAGE_VERSION_OWNER')
 sums=pathlib.Path('/var/lib/dpkg/info')/(package+'.md5sums')
 expected=next((line.split()[0] for line in sums.read_text().splitlines() if line.split(maxsplit=1)[-1]==member),None)
 data=real.read_bytes()
 if expected is None or hashlib.md5(data).hexdigest()!=expected:raise Stop('TOOL_OS_PACKAGE_FILE_INTEGRITY')
 return {'path':path,'realpath':str(real),'package':package,'version':version,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'package_file_md5_matched':True,'trust':'RUNNER_OS_IMAGE_RUNTIME_BOUND_NOT_INDEPENDENT_PACKAGE_SIGNATURE'}
def qualify_tools():return [qualify_one(*row) for row in POLICY]
def unchanged(rows):
 for row in rows:
  p=pathlib.Path(row['path'])
  if str(p.resolve(strict=True))!=row['realpath'] or hashlib.sha256(p.read_bytes()).hexdigest()!=row['sha256']:raise Stop('HOST_TOOL_CHANGED')

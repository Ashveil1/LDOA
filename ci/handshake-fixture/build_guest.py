#!/usr/bin/env python3
"""Offline extract-only staging. Refuses incomplete discovery locks. No apt/dpkg scripts."""
import argparse,hashlib,json,pathlib,subprocess,tarfile,tempfile,os

def safe_path(name):
 p=pathlib.PurePosixPath(name)
 return not p.is_absolute() and '..' not in p.parts and '\x00' not in name

def verify(lock):
 if lock.get('schema')!=1 or lock.get('complete') is not True or lock.get('scope')!='FROZEN_HANDSHAKE_BUILD_AND_RUNTIME_CLOSURE':raise ValueError('INCOMPLETE_LOCK')
 if lock.get('missing_or_ambiguous') or lock.get('blockers'):raise ValueError('BLOCKED_LOCK')
 for p in lock['packages']:
  if set(p)!={'package','version','url','sha256','bytes','cached_filename'}:raise ValueError('PACKAGE_SCHEMA')
  if not p['url'].startswith('https://archive.ubuntu.com/ubuntu/pool/') or len(p['sha256'])!=64 or p['bytes']<=0:raise ValueError('PACKAGE_PIN')
 return lock

def extract_deb(deb,out):
 # Do not use dpkg -i/chroot/apt or run package maintainers. Inspect data archive first.
 members=subprocess.check_output(['ar','t',str(deb)],text=True).splitlines()
 data=[m for m in members if m.startswith('data.tar.')]
 if len(data)!=1:raise ValueError('DEB_DATA_MEMBER')
 with tempfile.TemporaryFile() as f:
  subprocess.run(['ar','p',str(deb),data[0]],stdout=f,check=True);f.seek(0)
  with tarfile.open(fileobj=f,mode='r:*') as tf:
   items=tf.getmembers()
   for item in items:
    if not safe_path(item.name) or not (item.isfile() or item.isdir() or item.issym() or item.islnk()):raise ValueError('UNSAFE_DEB_MEMBER')
    if item.issym() or item.islnk():
     # Absolute symlinks need explicit manifest review; no implicit host resolution.
     if not safe_path(item.linkname):raise ValueError('UNREVIEWED_LINK')
   # Python's data filter prevents extraction through previously staged symlink paths.
   tf.extractall(out,members=items,filter='data')

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--lock',required=True);ap.add_argument('--cache',required=True);ap.add_argument('--out',required=True);a=ap.parse_args()
 lock=verify(json.loads(pathlib.Path(a.lock).read_text()));out=pathlib.Path(a.out)
 if out.exists():raise ValueError('OUTPUT_EXISTS')
 out.mkdir(mode=0o700)
 for p in lock['packages']:
  deb=pathlib.Path(a.cache)/p['cached_filename'];b=deb.read_bytes()
  if len(b)!=p['bytes'] or hashlib.sha256(b).hexdigest()!=p['sha256']:raise ValueError('DEB_PIN_MISMATCH')
  extract_deb(deb,out)
 # Building producer and ELF traversal are intentionally not implemented until complete lock.
 raise ValueError('BUILD_CLOSURE_AND_ELF_GRAPH_NOT_REVIEWED')
if __name__=='__main__':main()

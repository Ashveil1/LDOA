"""Strict narrow target-guest ABI receipt gate, no general package resolver."""
import re
REQUIRED={'libX11.so.6','libglib-2.0.so.0','libfm-gtk3.so.4','libgtk-3.so.0','libgdk-3.so.0','libpangocairo-1.0.so.0','libpango-1.0.so.0','libatk-1.0.so.0','libcairo.so.2','libgdk_pixbuf-2.0.so.0','libfm.so.4','libgio-2.0.so.0','libgobject-2.0.so.0','libc.so.6'}
def validate(header,ldd,rc,eof,overflow,guest_arch,manifest_verified):
 if not(manifest_verified and eof and not overflow and rc==0 and guest_arch=='amd64'):raise ValueError('incomplete/untrusted ABI capture')
 if len(header.encode())+len(ldd.encode())>262144:raise ValueError('capture cap')
 for token in ['ELF64',"2's complement, little endian",'Advanced Micro Devices X86-64']:
  if token not in header:raise ValueError('ELF arch')
 interp=re.findall(r'Requesting program interpreter: ([^\]]+)\]',header)
 if interp!=['/lib64/ld-linux-x86-64.so.2']:raise ValueError('unexpected interpreter')
 if re.search(r'not found|undefined symbol|wrong ELF|version .*not found|cannot open|error',ldd,re.I):raise ValueError('loader failure')
 needed=set(re.findall(r'\(NEEDED\).*?\[([^\]]+)\]',header))
 if needed!=REQUIRED:raise ValueError('NEEDED drift')
 resolved=dict(re.findall(r'^\s*(\S+) => (/\S+) \(0x[0-9a-f]+\)',ldd,re.M))
 if not needed<=resolved.keys():raise ValueError('missing resolved dependency')
 if any(not p.startswith(('/lib/','/lib64/','/usr/lib/')) or '..' in p.split('/') for p in resolved.values()):raise ValueError('unexpected guest dependency path')
 return resolved # Caller still hashes target guest paths/versions; not host ldd.

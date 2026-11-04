#!/usr/bin/env python3
"""Create symlink-free runtime asset from static reviewed ELF graph, no target execution."""
import argparse,pathlib,json,hashlib,zipfile,re
ap=argparse.ArgumentParser();ap.add_argument('--compiled',required=True);ap.add_argument('--out',required=True);a=ap.parse_args();compiled=pathlib.Path(a.compiled);out=pathlib.Path(a.out)
if out.exists():raise ValueError('OUTPUT_EXISTS')
graph=json.loads((compiled/'runtime-graph.json').read_text());entries={};sysroot=compiled/'sysroot'
# Preserve loader path requested by PT_INTERP and library SONAME paths; duplicate bytes rather than links.
entries['producer']=compiled/'producer'
for edge in graph['edges']:
 name=edge['needed']
 if not re.fullmatch(r'[a-zA-Z0-9_.+-]+',name):raise ValueError('SONAME')
 entries['usr/lib/x86_64-linux-gnu/'+name]=sysroot/edge['resolved'].lstrip('/')
entries['lib64/ld-linux-x86-64.so.2']=sysroot/'usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2'
# libc soname must resolve in expected default lib path; no inherited host LD_LIBRARY_PATH.
manifest=[]
for name,p in sorted(entries.items()):
 data=p.read_bytes();manifest.append({'name':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'mode':448 if name=='producer' else 384})
if sum(r['bytes'] for r in manifest)>32*1024*1024:raise ValueError('RUNTIME_ASSET_CAP')
with zipfile.ZipFile(out,'x',zipfile.ZIP_STORED) as z:
 for name,p in sorted(entries.items()):
  info=zipfile.ZipInfo(name,date_time=(2024,1,1,0,0,0));info.external_attr=(0o100700 if name=='producer' else 0o100600)<<16;z.writestr(info,p.read_bytes())
outmanifest={'schema':1,'scope':'SYMLINK_FREE_HANDSHAKE_RUNTIME_ASSET_COMPILED_NOT_EXECUTED','entries':manifest,'total_bytes':sum(r['bytes'] for r in manifest),'zip_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'zip_bytes':out.stat().st_size,'dirs':['dev','proc','sys','tmp','run','run/ldoa-handshake','usr','usr/lib','usr/lib/x86_64-linux-gnu','lib64','empty-gio-modules'],'source_graph_sha256':hashlib.sha256((compiled/'runtime-graph.json').read_bytes()).hexdigest()}
out.with_suffix('.manifest.json').write_text(json.dumps(outmanifest,indent=2)+'\n')

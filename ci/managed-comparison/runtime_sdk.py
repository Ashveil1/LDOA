import os,pathlib,re,json,sys
from paired_repro import binding
case=sys.argv[1]
assert case in ('original','patched')
p=pathlib.Path(os.environ['ANDROID_HOME'])/'emulator/source.properties'
assert p.is_file() and not p.is_symlink() and p.stat().st_size<=4096
rows=re.findall(r'^(Pkg\.(?:Revision|BuildId))\s*=\s*(.*?)\s*$',p.read_text(),re.M)
assert len(rows)==2 and dict(rows)=={'Pkg.Revision':'37.2.13','Pkg.BuildId':'16740729'},'SDK drift/missing/duplicate properties'
r={'schema':1,'case':case,'binding':binding(os.environ,'first' if case=='original' else 'second'),'properties':dict(rows)}
out=pathlib.Path(os.environ['GITHUB_WORKSPACE'])/'comparison-evidence'/'runtime-sdk.json'
assert out.parent.is_dir() and not out.exists() and not out.is_symlink()
out.write_text(json.dumps(r,sort_keys=True)+'\n')
print('SDK1 '+json.dumps(r,sort_keys=True))

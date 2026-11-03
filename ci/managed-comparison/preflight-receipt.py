import sys,pathlib,re
p=pathlib.Path(sys.argv[1]);assert p.is_file() and not p.is_symlink() and p.stat().st_size<=1024,'missing/unsafe/overflow receipt'
b=p.read_bytes();labels=['context-identity','parent-nonlink','parent-type','incoming-absent','incoming-nonlink','tmp-absent','tmp-nonlink']
lines=b.decode('ascii').splitlines();assert lines[0]=='schema=1' and len(lines)==8,'incomplete'
for l,label in zip(lines[1:],labels):assert re.fullmatch('guard='+label+r' rc=(0|[1-9][0-9]{0,2})',l),'unsafe/order'
print(b.decode('ascii'),end='')

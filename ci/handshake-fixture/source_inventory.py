"""One source identity scheme for review and future payload binding. No top-level I/O."""
import pathlib,hashlib
EXCLUDED=frozenset({'generated','.gradle','.cxx','build','private-run','__pycache__'})
def inventory(root):
 root=pathlib.Path(root)
 rows=[]
 for p in sorted(root.rglob('*')):
  rel=p.relative_to(root)
  if EXCLUDED.intersection(rel.parts):continue
  if p.is_symlink():raise ValueError('SOURCE_SYMLINK_REFUSED')
  if p.is_file():rows.append({'path':rel.as_posix(),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
 return rows
def source_pin(rows):
 if rows!=sorted(rows,key=lambda r:r['path']):raise ValueError('SOURCE_INVENTORY_ORDER')
 names=[r['path'] for r in rows]
 if len(names)!=len(set(names)):raise ValueError('SOURCE_INVENTORY_DUPLICATE')
 for r in rows:
  if '\x00' in r['path'] or '\n' in r['path'] or len(r['sha256'])!=64 or any(c not in '0123456789abcdef' for c in r['sha256']):raise ValueError('SOURCE_INVENTORY_FORMAT')
 return hashlib.sha256(''.join(r['path']+'\0'+r['sha256']+'\n' for r in rows).encode('utf-8')).hexdigest()

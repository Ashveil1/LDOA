"""Pure selected-byte staging contract. No network, decompressor, compiler or build invocation."""
import pathlib,hashlib,os,shutil,json,time
class Stop(RuntimeError):pass
def normalized(name):
 p=pathlib.PurePosixPath(name)
 if p.is_absolute() or '..' in p.parts or not p.parts:raise Stop('MEMBER_PATH')
 return str(p)
def canonical(name):
 first,sep,tail=name.partition('/')
 return {'lib':'usr/lib','lib64':'usr/lib64'}.get(first,first)+(sep+tail if sep else '')
def target(name,value):
 parts=[] if value.startswith('/') else list(pathlib.PurePosixPath(name).parent.parts)
 for part in pathlib.PurePosixPath(value).parts:
  if part in ('/','.'):continue
  if part=='..':
   if not parts:raise Stop('LINK_ESCAPE')
   parts.pop()
  else:parts.append(part)
 return canonical('/'.join(parts))
def stage(source,output,manifest,deadline,clock=time.monotonic):
 source=pathlib.Path(source).resolve(strict=True);output=pathlib.Path(output)
 # Fresh output only. Input root must come from A1 verified extraction, not arbitrary host library tree.
 if output.exists() or output.is_symlink():raise Stop('STAGE_OUTPUT_EXISTS')
 rows={};links={};expected={}
 for r in manifest['files']:
  name=normalized(r['path']);key=canonical(name)
  if key in rows:raise Stop('USRMERGE_COLLISION')
  rows[key]=r
  if r['type']=='symlink':links[key]=target(key,r['target'])
  elif r['type']=='file':expected[key]=r
  else:raise Stop('MEMBER_TYPE')
 for name,link in links.items():
  seen={name}
  while link in links:
   if link in seen:raise Stop('LINK_CYCLE')
   seen.add(link);link=links[link]
  if link not in expected:raise Stop('LINK_TARGET_MISSING')
 output.mkdir(mode=0o700)
 try:
  for name,r in expected.items():
   if clock()>=deadline:raise Stop('TOTAL_DEADLINE')
   p=source/normalized(r['path']);real=p.resolve(strict=True)
   if p.is_symlink() or not real.is_relative_to(source):raise Stop('INPUT_PATH_ESCAPE')
   data=p.read_bytes()
   if len(data)!=r['bytes'] or hashlib.sha256(data).hexdigest()!=r['sha256']:raise Stop('MEMBER_BYTES')
   dest=output/name;dest.parent.mkdir(mode=0o700,parents=True,exist_ok=True);dest.write_bytes(data);dest.chmod(0o600)
  # Symlinks only after all regular files; no copy can traverse staged symlinks.
  for name,link in links.items():
   if clock()>=deadline:raise Stop('TOTAL_DEADLINE')
   dest=output/name;dest.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
   dest.symlink_to(os.path.relpath(output/link,dest.parent))
  for name,link in [('lib','usr/lib'),('lib64','usr/lib64')]:
   if (output/name).exists() or (output/name).is_symlink():raise Stop('ALIAS_COLLISION')
   (output/link).mkdir(mode=0o700,parents=True,exist_ok=True);(output/name).symlink_to(link)
  return {'scope':'EXACT_SELECTED_MEMBER_STAGE_NOT_COMPILE_READY','files':len(expected),'links':len(links),'compiler_executed':False}
 except Exception:
  shutil.rmtree(output);raise

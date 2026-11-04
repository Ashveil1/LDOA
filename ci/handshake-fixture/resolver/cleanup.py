"""Future cleanup restricted to one fixed fresh RUNNER_TEMP child, no broad SDK deletion."""
import pathlib,os,shutil,json
parent=pathlib.Path(os.environ['RUNNER_TEMP']).resolve();p=parent/'ldoa-build-inputs-one';ok=False
try:
 if p.is_symlink():raise ValueError('OWNED_DIRECTORY_SYMLINK')
 if p.exists():
  if p.parent.resolve()!=parent or not p.is_dir():raise ValueError('OWNED_DIRECTORY_TYPE')
  shutil.rmtree(p)
 ok=not p.exists()
finally:print(json.dumps({'scope':'BUILD_INPUT_CLEANUP','owned_child_absent':ok,'target_absence':'NOT_APPLICABLE_NOT_LAUNCHED'}))
raise SystemExit(0 if ok else 2)

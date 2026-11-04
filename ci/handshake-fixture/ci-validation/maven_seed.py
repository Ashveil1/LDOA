#!/usr/bin/env python3
"""Future exact allowlist download into private Maven tree, no Gradle fallback. Not invoked."""
import pathlib,json
from fetch_locked import fetch
def seed(lock,directory):
 receipts=[];seen=set()
 for row in lock['artifacts']+lock['metadata']+lock['bom_additions']:
  url=row['url']
  root=next((r for r in lock['repository_allowlist'] if url.startswith(r)),None)
  if root is None:raise ValueError('REPOSITORY_SCOPE')
  relative=pathlib.PurePosixPath(url[len(root):])
  if relative.is_absolute() or '..' in relative.parts:raise ValueError('REPOSITORY_PATH')
  if str(relative) in seen:continue
  seen.add(str(relative));dest=directory/relative;dest.parent.mkdir(parents=True,exist_ok=True)
  receipts.append(fetch(row,dest))
 return receipts

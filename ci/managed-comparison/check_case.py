import pathlib,sys,re,json,xml.etree.ElementTree as E

def check(root,case,pin):
 if case not in ('original','patched') or not re.fullmatch('[0-9a-f]{64}',pin):raise ValueError('expected case/pin required')
 root=pathlib.Path(root)
 for name,want in [('caller.txt','caller_exit=0\n'),('phase.txt','instrumentation_caller_rc=0\n')]:
  paths=list(root.rglob(name))
  if len(paths)!=1 or paths[0].read_text()!=want:raise ValueError('case caller/phase failure')
 guards=list(root.rglob('transfer-guards.txt'))
 labels=['context-identity','parent-nonlink','parent-type','incoming-absent','incoming-nonlink','tmp-absent','tmp-nonlink','tmp-scaffold','push','tmp-inventory','private-copy']
 if len(guards)!=1 or guards[0].read_text()!='schema=1\n'+''.join('guard='+l+' rc=0\n' for l in labels):raise ValueError('transfer identity/refusal/copy coverage')
 xs=list(root.rglob('managed-junit.xml'))
 if len(xs)!=1:raise ValueError('missing/duplicate actual JUnit listener XML')
 xml=E.parse(xs[0]).getroot();cs=list(xml.iter('testcase'))
 if len(cs)!=1 or cs[0].get('classname')!='dev.phonelinux.proto.MobileDesktopEntryTest' or cs[0].get('name')!='mobileEntryManagedDesktopComparison' or list(cs[0]):raise ValueError('wrong/skipped/failed testcase')
 if xml.get('tests')!='1' or xml.get('failures')!='0' or xml.get('skipped')!='0':raise ValueError('suite count/failure')
 raws=list(root.rglob('managed-comparison.txt'))
 if len(raws)!=1:raise ValueError('missing/duplicate comparison')
 text=raws[0].read_text()
 expected='case='+case+' manifest='+pin
 if not text.startswith(expected+'\n'):raise ValueError('raw expected case/manifest mismatch')
 provenance=list(root.rglob('managed-provenance.txt'))
 if len(provenance)!=1 or not re.fullmatch(re.escape(expected)+r' inventory=[0-9a-f]{64}\n',provenance[0].read_text()):raise ValueError('provenance expected case/manifest mismatch')
 rows=re.findall(r'^case=(\w+) transition=(\d+) requested=\d+x\d+ anchored=\d+/\d+ fit=(true|false)$',text,re.M)
 if len(rows)!=5 or [r[1] for r in rows]!=list('01234') or any(r[0]!=case for r in rows):raise ValueError('case transition agreement')
 if case=='patched' and any(r[2]!='true' for r in rows):raise ValueError('patched geometry mismatch')
 if case=='original' and not any(r[2]=='false' for r in rows):raise ValueError('original floor not reproduced')
 if 'CASE geometry observations complete' not in text:raise ValueError('incomplete comparison')
 if any(len(list(root.rglob('managed-'+str(i)+'.png')))!=1 for i in range(5)) or len(list(root.rglob('managed-input.png')))!=1:raise ValueError('missing/duplicate decisive pixels')
 for i in range(5):
  bs=list(root.rglob('managed-'+str(i)+'-capture-bracket.json'))
  if len(bs)!=1:raise ValueError('missing/duplicate desktop pixel bracket')
  b=json.loads(bs[0].read_text())
  ints=lambda v,n:isinstance(v,list) and len(v)==n and all(type(x) is int for x in v)
  geom=b.get('before');roi=b.get('roi_root');mapped=b.get('roi_bitmap')
  if not ints(geom,6) or any(x<=0 for x in geom[:4]) or not ints(roi,4) or min(roi[2:])<=0 or min(roi[:2])<0 or not ints(mapped,4) or min(mapped[:2])<0 or mapped[2]<=mapped[0] or mapped[3]<=mapped[1]:raise ValueError('nonvacuous desktop bracket geometry')
  if roi[0]+roi[2]>geom[0] or roi[1]+roi[3]>geom[1] or roi[0]<geom[4] or roi[1]<geom[5] or roi[0]+roi[2]>geom[4]+geom[2] or roi[1]+roi[3]>geom[5]+geom[3]:raise ValueError('ROI outside root/desktop')
  if type(b.get('desktop_pid')) is not int or b['desktop_pid']<=0 or not re.fullmatch(r'[1-9][0-9]*',str(b.get('desktop_start',''))) or type(b.get('desktop_xid')) is not int or b['desktop_xid']<=0:raise ValueError('missing desktop identity')
  if (str(b['desktop_pid']),str(b['desktop_start']))!=re.search(r'anchored=(\d+)/(\d+)',text).groups():raise ValueError('desktop identity drift')
  if b.get('schema')!=1 or b.get('case')!=case or b.get('manifest')!=pin or b.get('transition')!=i or b.get('xterm_unmapped') is not True or b.get('occluders')!=[] or geom!=b.get('after') or b.get('manual_pixels') is not True:raise ValueError('desktop pixel bracket binding/state')

 teardown=list(root.rglob('managed-teardown.txt'))
 if len(teardown)!=1 or teardown[0].read_text().strip()!='PASS original owned PID/start set absent after Stop':raise ValueError('unverified teardown')
 return 'PASS exact executed case/coverage; manual pixel/input review still REQUIRED'
if __name__=='__main__':
 try:print(check(sys.argv[1],sys.argv[2],sys.argv[3]))
 except Exception as e:print('REFUSED',e);sys.exit(1)

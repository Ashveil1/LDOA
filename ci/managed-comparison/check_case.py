import pathlib,sys,re,json,math,struct,xml.etree.ElementTree as E

def check(root,case,pin,*,inflight=False):
 if case not in ('original','patched') or not re.fullmatch('[0-9a-f]{64}',pin):raise ValueError('expected case/pin required')
 root=pathlib.Path(root)
 for name,want in ([('phase.txt','instrumentation_caller_rc=0\n')] if inflight else [('caller.txt','caller_exit=0\n'),('phase.txt','instrumentation_caller_rc=0\n')]):
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
  if bs[0].stat().st_size>8192:raise ValueError('bracket cap')
  ints=lambda v,n:isinstance(v,list) and len(v)==n and all(type(x) is int for x in v)
  geom=b.get('before');roi=b.get('roi_root')
  if not ints(geom,6) or any(x<=0 for x in geom[:4]) or not ints(roi,4) or min(roi[2:])<=0 or min(roi[:2])<0:raise ValueError('geometry')
  if any(abs(x)>1000000 for x in geom):raise ValueError('geometry cap')
  expected_roi=[geom[0]//5,geom[1]*35//100,geom[0]*3//5,geom[1]*3//10]
  if roi!=expected_roi:raise ValueError('fixed root ROI formula')
  def intersection(a,d):
   x=max(a[0],d[0]);y=max(a[1],d[1]);w=min(a[0]+a[2],d[0]+d[2])-x;h=min(a[1]+a[3],d[1]+d[3])-y
   return [x,y,w,h] if w>0 and h>0 else None
  desktop=[geom[4],geom[5],geom[2],geom[3]]
  ri=intersection([0,0,geom[0],geom[1]],desktop);bi=intersection(roi,desktop)
  contained=bi==roi;coverage='FULL' if contained else 'NONE' if bi is None else 'PARTIAL'
  for key,want in [('root_desktop_intersection',ri),('roi_desktop_intersection',bi)]:
   got=b.get(key)
   if key not in b or (got is not None and not ints(got,4)) or got!=want:raise ValueError('intersection')
  if type(b.get('root_roi_contained')) is not bool or b['root_roi_contained']!=contained or b.get('roi_coverage')!=coverage:raise ValueError('ROI coverage')
  fit=geom[0]==geom[2] and geom[1]==geom[3] and geom[4:]==[0,0]
  if (rows[i][2]=='true')!=fit:raise ValueError('raw fit disagreement')
  if case=='patched' and (not fit or not contained or coverage!='FULL'):raise ValueError('patched exact fit/coverage')
  if type(b.get('desktop_pid')) is not int or b['desktop_pid']<=0 or not re.fullmatch(r'[1-9][0-9]*',str(b.get('desktop_start',''))) or type(b.get('desktop_xid')) is not int or b['desktop_xid']<=0:raise ValueError('desktop identity')
  if (str(b['desktop_pid']),str(b['desktop_start']))!=re.search(r'anchored=(\d+)/(\d+)',text).groups():raise ValueError('desktop identity drift')
  fixed={'source_pin':'29d4460b26e69b39af023d255a255e2778251952','schema':3,'scope':'x-geometry-and-manual-fullscreen','presentation_binding':'UNVERIFIED','manual_review':'PENDING','legacy_renderQualification':'NOT_EXECUTED_MANUAL_SCOPE','x_occlusion_scope':'central-root-only'}
  if any(b.get(k)!=v for k,v in fixed.items()) or any(k in b for k in ['roi_bitmap','manual_pixels']):raise ValueError('manual scope/certification')
  if b.get('case')!=case or b.get('manifest')!=pin or b.get('transition')!=i or b.get('xterm_unmapped') is not True or b.get('occluders')!=[] or geom!=b.get('after'):raise ValueError('bracket binding/state')
  for phase in ['pre','post']:
   snap=b.get(phase,{})
   if snap.get('complete') is not True or snap.get('valid') is not True:raise ValueError('snapshot incomplete')
   start=snap.get('start_ns');end=snap.get('end_ns');v=snap.get('numbers')
   if type(start) is not int or type(end) is not int or not 0<start<=end<=start+2000000000:raise ValueError('snapshot time')
   if not isinstance(v,list) or len(v)!=53 or any(type(x) not in (int,float) or not math.isfinite(x) or abs(x)>1000000 for x in v):raise ValueError('snapshot finite shape')
   if min(v[:4])<=0 or v[36:41]!=[1,1,1,0,1] or v[52]!=1 or v[41]<0 or v[42] not in (0,1,2,3):raise ValueError('snapshot surface/view identity')
  cs=b.get('capture_start_ns');ce=b.get('capture_end_ns')
  if type(cs) is not int or type(ce) is not int or not b['pre']['end_ns']<=cs<=ce<=b['post']['start_ns']:raise ValueError('capture/snapshot ordering')
  state='UNCHANGED' if b['pre']['numbers']==b['post']['numbers'] else 'CHANGED'
  if b.get('java_state')!=state:raise ValueError('snapshot state label')
  q=b.get('query_calls')
  if type(q) is not int or not 0<q<=512 or b.get('query_complete')!=q or b.get('query_zero_cleanup')!=q:raise ValueError('query completeness')
  png=list(root.rglob('managed-'+str(i)+'.png'))[0].read_bytes()
  if len(png)<24 or png[:8]!=b'\x89PNG\r\n\x1a\n' or png[12:16]!=b'IHDR' or list(struct.unpack('>II',png[16:24]))!=b.get('bitmap'):raise ValueError('actual PNG header dimensions')

 teardown=list(root.rglob('managed-teardown.txt'))
 if len(teardown)!=1 or teardown[0].read_text().strip()!='PASS original owned PID/start set absent after Stop':raise ValueError('unverified teardown')
 return 'AUTOMATED_GEOMETRY_GATES_COMPLETE_MANUAL_REVIEW_REQUIRED'
if __name__=='__main__':
 try:
  if len(sys.argv) not in (4,5) or (len(sys.argv)==5 and sys.argv[4]!='--inflight'):raise ValueError('exact args')
  print(check(sys.argv[1],sys.argv[2],sys.argv[3],inflight=len(sys.argv)==5))
 except Exception as e:print('REFUSED',e);sys.exit(1)

import pathlib,sys,re,xml.etree.ElementTree as E

def check(root,case,pin):
 if case not in ('original','patched') or not re.fullmatch('[0-9a-f]{64}',pin):raise ValueError('expected case/pin required')
 root=pathlib.Path(root)
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
 if 'CASE geometry observations complete' not in text:raise ValueError('incomplete comparison')
 if any(len(list(root.rglob('managed-'+str(i)+'.png')))!=1 for i in range(5)) or len(list(root.rglob('managed-input.png')))!=1:raise ValueError('missing/duplicate decisive pixels')
 teardown=list(root.rglob('managed-teardown.txt'))
 if len(teardown)!=1 or teardown[0].read_text().strip()!='PASS original owned PID/start set absent after Stop':raise ValueError('unverified teardown')
 return 'PASS exact executed case/coverage; manual pixel/input review still REQUIRED'
if __name__=='__main__':
 try:print(check(sys.argv[1],sys.argv[2],sys.argv[3]))
 except Exception as e:print('REFUSED',e);sys.exit(1)

#!/usr/bin/env python3
"""Public-repository experiment evidence. Local X servers, NOT Android/FPS.
Root/PID/PGID identity checks are best effort, not escaped-descendant proof.
"""
import argparse,json,os,pathlib,re,resource,signal,subprocess,time

MAX_OBSERVATIONS=96
MAX_IMAGES=8
MAX_EVIDENCE_BYTES=96*1024*1024

def stat(pid):
    text=pathlib.Path('/proc',str(pid),'stat').read_text()
    f=text[text.rfind(')')+2:].split()
    return dict(pid=pid,ppid=int(f[1]),pgid=int(f[2]),start=f[19],state=f[0])

def identity(pid):return stat(pid)['start']

def scan_groups(groups):
    found={}
    for p in pathlib.Path('/proc').iterdir():
        if not p.name.isdigit():continue
        try:s=stat(int(p.name))
        except FileNotFoundError:continue
        # Permission/malformed /proc failure is UNKNOWN, never absence.
        if s['pgid'] in groups:found[s['pid']]=s
    return found

def parse_geometry(text):
    def value(key):
        m=re.search(r'^\s*'+key+r':\s*(-?\d+)\s*$',text,re.M)
        if not m:raise AssertionError('missing geometry '+key)
        return int(m[1])
    return tuple(value(k) for k in ['Absolute upper-left X','Absolute upper-left Y','Width','Height'])

def parse_monitors(text):
    lines=text.splitlines();assert lines and lines[0]=='Monitors: 1','single-monitor precondition'
    assert len(lines)==2,'unexpected monitor records'
    m=re.fullmatch(r'\s*0:\s+\S+\s+(\d+)/\d+x(\d+)/\d+([+-]\d+)([+-]\d+)\s+\S+',lines[1])
    assert m,'monitor geometry malformed'
    w,h,x,y=map(int,m.groups());return x,y,w,h

def parse_hints(text):
    values=[]
    for kind in ['minimum','maximum']:
        m=re.search(r'program specified '+kind+r' size:\s*(\d+) by (\d+)',text)
        if not m:raise AssertionError('missing '+kind+' hints')
        values.append(tuple(map(int,m.groups())))
    return values

def qualifies(case,desktop,root,monitor,hints):
    if root!=monitor or root[:2]!=(0,0) or desktop[:2]!=(0,0):return False
    if case=='original':return root==(0,0,1080,1949) and desktop==(0,0,1280,1949) and hints==[(1280,1949)]*2
    return desktop==root and hints==[root[2:]]*2

def filesize_limit(limit):
    def apply():
        resource.setrlimit(resource.RLIMIT_FSIZE,(limit,limit))
        resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    return apply

def launch(args,registry,known,*,persistent=False,limit=1024*1024,**kw):
    # Register the child handle before reading /proc. Even if identity lookup
    # fails, cleanup still owns a Popen handle. Unknown group lineage is fatal.
    p=subprocess.Popen(args,start_new_session=True,preexec_fn=filesize_limit(limit),**kw)
    s={'pid':p.pid,'pgid':p.pid,'start':None,'persistent':persistent}
    registry.append((p,s))
    try:s.update(stat(p.pid));known[p.pid]=s
    except BaseException:
        try:
            p.kill();p.wait(timeout=1)
        except BaseException:pass
        raise
    return p,s

def wait_command(p,end):
    left=end-time.monotonic()
    if left<=0:raise AssertionError('shared phase deadline exhausted')
    p.wait(timeout=min(5,left))
    assert time.monotonic()<=end,'shared phase deadline exceeded'

def cleanup_known(registry,known):
    failures=[]
    for p,s in registry:
        try:
            if s['start'] is None:
                failures.append('identity registration UNKNOWN pid='+str(p.pid))
                if p.poll() is None:p.kill();p.wait(timeout=1)
        except BaseException as t:failures.append(repr(t))
    # Never let one unknown group prevent independent safe identity cleanup.
    for pid,s in list(known.items()):
        try:
            current=stat(pid)
            if current['start']!=s['start']:
                failures.append('PID reused UNKNOWN '+str(pid));continue
            if current['state']!='Z':os.kill(pid,signal.SIGKILL)
        except FileNotFoundError:pass
        except BaseException as t:failures.append(repr(t))
    for p,_ in registry:
        try:p.wait(timeout=1)
        except BaseException as t:failures.append(repr(t))
    return failures

def note(primary,text):
    if hasattr(primary,'add_note'):primary.add_note(text)
    else:
        primary.__notes__=getattr(primary,'__notes__',[])+[text]

def preserve_primary(primary,write):
    try:write()
    except BaseException as evidence:note(primary,'evidence persistence failure '+repr(evidence))
    return primary

def bracket_capture(sample,capture,end,expected):
    before=sample('capture-before',end)
    assert before==expected,'capture geometry changed before image'
    capture(end)
    after=sample('capture-after',end)
    assert after==before,'capture geometry changed across image'
    return {'bracket_equal':True,'limitation':'transient changes between samples not excluded; pixel review required'}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--binary',required=True);ap.add_argument('--case',choices=['original','patched'],required=True);ap.add_argument('--out',required=True);a=ap.parse_args()
    out=pathlib.Path(a.out).resolve();out.mkdir(parents=True,exist_ok=False)
    for d in ['home','config','runtime']:(out/d).mkdir(mode=0o700)
    env=os.environ.copy();env.update(HOME=str(out/'home'),XDG_CONFIG_HOME=str(out/'config'),XDG_RUNTIME_DIR=str(out/'runtime'),LC_ALL='C')
    processes=[];logs=[];records=[];known={};images=[];seq=0;primary=None
    def persist(name,text):
        # Per-case retained occupancy gate (post-write), NOT whole-artifact cap.
        # Each child also has in-flight 1MiB RLIMIT_FSIZE per-file.
        assert len(text.encode())<=2*1024*1024,'text artifact cap exceeded'
        (out/name).write_text(text)
    def budget():
        total=sum(p.stat().st_size for p in out.rglob('*') if p.is_file())
        assert total<=MAX_EVIDENCE_BYTES,'evidence byte cap exceeded'
    def run(args,end,ev=env,empty=False,file_limit=1024*1024):
        left=end-time.monotonic();assert left>0,'shared phase deadline exhausted'
        # Bound output in files, not an unbounded capture_output allocation.
        nonlocal seq
        seq+=1;dest=out/('command-%03d.txt'%seq)
        with dest.open('wb') as f:
            p,ident=launch(args,processes,known,limit=file_limit,env=ev,stdout=f,stderr=subprocess.STDOUT)
            try:wait_command(p,end)
            except subprocess.TimeoutExpired:
                if p.poll() is None and identity(p.pid)==ident['start']:p.kill()
                p.wait(timeout=1);raise AssertionError('command phase timeout '+str(args))
        assert time.monotonic()<=end,'shared phase deadline exceeded'
        assert dest.stat().st_size<=1024*1024,'command output cap exceeded'
        text=dest.read_text()
        persist('command-%03d.json'%seq,json.dumps({'argv':args,'rc':p.returncode,'output':dest.name}))
        budget()
        if p.returncode and not(empty and p.returncode==1 and not text):raise AssertionError('command failed '+str(args))
        return text
    def remember():
        groups={s['pgid'] for _,s in processes}
        members=scan_groups(groups)
        for pid,s in members.items():
            if pid in known:assert known[pid]['start']==s['start'],'PID reuse - UNKNOWN'
            else:
                # Accept a newly observed member only while group leader identity
                # remains our original live root, or via known parent chain.
                leader=next((p for p,k in processes if k['pid']==s['pgid'] and k['start']==identity_if_present(k['pid'])),None)
                assert leader is not None and leader.poll() is None,'unattributable group member - UNKNOWN'
                known[pid]=s
        return members
    def identity_if_present(pid):
        try:return identity(pid)
        except FileNotFoundError:return None
    def live():
        # Only server/session roots, not completed one-shot commands.
        for p,s in processes:
            if s.get('persistent'):assert p.poll() is None and identity(p.pid)==s['start'],'owned root died/reused'
        remember()
    def spawn(args,ev=env,pass_fds=()):
        log=(out/('process-%d.log'%len(processes))).open('wb');logs.append(log)
        p,s=launch(args,processes,known,persistent=True,limit=1024*1024,env=ev,stdout=log,stderr=log,pass_fds=pass_fds)
        return p
    def display(server,args,ev):
        r,w=os.pipe();os.set_blocking(r,False)
        try:p=spawn([server,'-displayfd',str(w),*args],ev,(w,))
        finally:os.close(w)
        data=b'';end=time.monotonic()+8
        try:
            while time.monotonic()<end:
                live()
                try:data+=os.read(r,64)
                except BlockingIOError:pass
                if b'\n' in data:break
                time.sleep(.05)
        finally:os.close(r)
        assert re.fullmatch(rb'\d+\n',data),'missing displayfd result'
        return ':'+data.strip().decode()
    def capture(tag,end):
        assert len(images)<MAX_IMAGES,'image count cap exceeded'
        budget();remaining=MAX_EVIDENCE_BYTES-sum(p.stat().st_size for p in out.rglob('*') if p.is_file())
        # Tool filesize limits, plus command deadline, prevent runaway image writes.
        assert remaining>20*1024*1024,'insufficient image budget'
        base=out/('%02d-%s'%(len(images),tag));xwd=str(base)+'.xwd';png=str(base)+'.png'
        run(['timeout','-k','1','3','sh','-c','ulimit -f 20480; exec xwd -root -silent -out "$1"','sh',xwd],end,file_limit=20*1024*1024)
        run(['sh','-c','ulimit -f 20480; exec convert -limit disk 16MiB -limit memory 64MiB "$1" "$2"','sh',xwd,png],end,file_limit=20*1024*1024)
        assert pathlib.Path(png).is_file() and pathlib.Path(png).stat().st_size>0,'missing image'
        images.append({'tag':tag,'observation_id':len(records)-1,'xwd':pathlib.Path(xwd).name,'png':pathlib.Path(png).name,'monotonic_ns':time.monotonic_ns(),'geometry_association':'UNPROVEN until bracket receipt exists'})
        persist('images.json',json.dumps(images,indent=2));budget()
    try:
        outer=display('Xvfb',['-screen','0','2200x2400x24','-nolisten','tcp'],env)
        outerenv=dict(env,DISPLAY=outer)
        inner=display('Xephyr',['-screen','1280x1024','-resizeable','-nolisten','tcp','-ac'],outerenv);env['DISPLAY']=inner
        nested=run(['xdotool','search','--class','Xephyr'],time.monotonic()+5,outerenv).split();assert len(nested)==1,'ambiguous nested host window';host=nested[0]
        cfg=out/'jwmrc';cfg.write_text('<JWM><RootMenu/><Tray x="0" y="0" height="48"><Clock/></Tray></JWM>')
        spawn(['jwm','-f',str(cfg)]);desktop_root=spawn(['dbus-run-session','--',a.binary,'--desktop'])
        end=time.monotonic()+12;window=None;pid=None;start=None
        while time.monotonic()<end:
            live();ids=run(['xdotool','search','--class','pcmanfm'],end,empty=True).split();c=[]
            for wid in ids:
                prop=run(['xprop','-id',wid,'_NET_WM_WINDOW_TYPE','_NET_WM_PID','WM_CLASS'],end)
                if '= _NET_WM_WINDOW_TYPE_DESKTOP' in prop:
                    m=re.search(r'_NET_WM_PID\(CARDINAL\) = (\d+)',prop);assert m;c.append((wid,int(m[1])))
            if c:
                assert len(c)==1,'ambiguous desktop';window,pid=c[0];start=identity(pid)
                ancestor=pid;seen=set()
                while ancestor!=desktop_root.pid:
                    assert ancestor>1 and ancestor not in seen,'foreign desktop PID';seen.add(ancestor);ancestor=stat(ancestor)['ppid']
                break
            time.sleep(.1)
        assert window is not None,'no desktop (no skip)'
        def sample(tag,end):
            assert len(records)<MAX_OBSERVATIONS,'observation count cap exceeded: bounded evidence unavailable, NOT mechanism disproved'
            live();assert identity(pid)==start,'desktop PID reuse'
            prop=run(['xprop','-id',window,'_NET_WM_PID','WM_NORMAL_HINTS','_NET_WM_WINDOW_TYPE'],end)
            assert re.search(r'_NET_WM_PID\(CARDINAL\) = '+str(pid)+r'\b',prop)
            assert '= _NET_WM_WINDOW_TYPE_DESKTOP' in prop
            geom=parse_geometry(run(['xwininfo','-id',window],end));root=parse_geometry(run(['xwininfo','-root'],end));monitor=parse_monitors(run(['xrandr','--listmonitors'],end));hints=parse_hints(prop)
            record={'observation_id':len(records),'tag':tag,'monotonic_ns':time.monotonic_ns(),'pid':pid,'starttime':start,'window':window,'desktop':geom,'root':root,'monitor':monitor,'hints':hints}
            records.append(record);persist('geometry.json',json.dumps(records,indent=2));return geom,root,monitor,hints
        # Stable startup requires three equal complete samples over >=500ms.
        end=time.monotonic()+10;stable=[]
        while time.monotonic()<end:
            v=sample('initial',end)
            if v[0]==v[1]==v[2]==(0,0,1280,1024) and v[3]==[(1280,1024)]*2:stable.append(time.monotonic())
            else:stable=[]
            if len(stable)>=3 and stable[-1]-stable[0]>=.5:break
            time.sleep(.2)
        assert len(stable)>=3 and stable[-1]-stable[0]>=.5,'stable startup unavailable'
        def settled_capture(tag,expected):
            end=time.monotonic()+10
            association=bracket_capture(sample,lambda deadline:capture(tag,deadline),end,expected)
            persist(tag+'-capture-bracket.json',json.dumps(association))
        settled_capture('initial',v)
        targets=[(1080,1949)] if a.case=='original' else [(1080,1949),(900,1400),(1400,900),(1080,1949),(1280,1024)]
        for i,(w,h) in enumerate(targets):
            end=time.monotonic()+8;run(['xdotool','windowsize',host,str(w),str(h)],end,outerenv);ok=False
            while time.monotonic()<end:
                values=sample('transition-%d'%i,end)
                if values[1]==(0,0,w,h) and qualifies(a.case,*values):ok=True;break
                time.sleep(.1)
            assert ok,('EXACT ORIGINAL REPRODUCTION UNAVAILABLE in this theme/widget environment; NOT mechanism disproved' if a.case=='original' else 'PATCH CRITERIA NOT OBSERVED; inspect raw geometry')
            settled_capture('settled-%d'%i,values)
        persist('result.json',json.dumps({'case':a.case,'geometry_criteria_passed':True,'pixel_review':'REQUIRED; geometry brackets do not qualify rendered pixels','tray_geometry':'NOT asserted','nonzero_origin':'NOT covered','android_render_or_fps_proof':False}))
    except BaseException as t:
        primary=t
        preserve_primary(t,lambda:persist('failure.txt',repr(t)))
        try:
            if 'DISPLAY' in env and len(images)<MAX_IMAGES:capture('failure',time.monotonic()+4)
        except BaseException as evidence:note(t,'failure image: '+repr(evidence))
        raise
    finally:
        failures=[]
        # Scan before each signal; only previously attributed PID/starttime is
        # signalled, never a recycled numeric process group after leader exit.
        for sig in [signal.SIGTERM,signal.SIGKILL]:
            try:
                members=remember()
                for pid,s in members.items():
                    if pid not in known or known[pid]['start']!=s['start']:raise AssertionError('unattributed cleanup member')
                    if identity(pid)==s['start']:os.kill(pid,sig)
                until=time.monotonic()+2
                while time.monotonic()<until:
                    for p,_ in processes:p.poll()
                    if not scan_groups({s['pgid'] for _,s in processes}):break
                    time.sleep(.05)
            except BaseException as t:failures.append(repr(t))
            failures.extend(cleanup_known(processes,known))
        try:
            remaining=scan_groups({s['pgid'] for _,s in processes})
            if remaining:failures.append('survivors/unknown '+repr(remaining))
        except BaseException as t:failures.append('scan UNKNOWN '+repr(t))
        for log in logs:log.close()
        try:persist('cleanup.json',json.dumps({'failures':failures,'escaped_descendants':'not proven absent'},indent=2))
        except BaseException as t:
            if primary:note(primary,'cleanup evidence failure '+repr(t))
            else:raise
        # Terminal receipt is a conjunction, never result.json alone. A failed
        # receipt write must itself fail, without masking an existing primary.
        terminal={'case':a.case,'overall_case_passed':primary is None and not failures,
                  'cleanup_unknown':bool(failures),'primary':repr(primary) if primary else None,
                  'pixel_review':'REQUIRED; no rendered/Android/FPS qualification'}
        if primary:preserve_primary(primary,lambda:persist('overall.json',json.dumps(terminal,indent=2)))
        else:persist('overall.json',json.dumps(terminal,indent=2))
        if failures:
            if primary:note(primary,'cleanup UNKNOWN '+repr(failures))
            else:raise AssertionError('cleanup UNKNOWN '+repr(failures))

if __name__=='__main__':main()

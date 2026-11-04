import socket,os,subprocess,urllib.request,json
results={}
for name,target in [('internet4',('1.1.1.1',443)),('dns',('8.8.8.8',53)),('other_loopback',('127.0.0.1',18764))]:
 s=socket.socket();s.settimeout(1)
 try:s.connect(target);raise AssertionError('NETWORK_ESCAPE_'+name)
 except OSError:results[name]='DENIED'
 s.close()
try:socket.socket(socket.AF_UNIX);raise AssertionError('UNIX_ESCAPE')
except PermissionError:results['unix_socket']='DENIED'
s=socket.socket(socket.AF_INET6,socket.SOCK_STREAM);s.settimeout(1)
try:s.connect(('2606:4700:4700::1111',443));raise AssertionError('IPV6_ESCAPE')
except OSError:results['internet6']='DENIED'
s.close()
for command in [['unshare','-n','true'],['unshare','-U','true']]:
 r=subprocess.run(command,capture_output=True);assert r.returncode!=0;results[' '.join(command)]='DENIED'
u='http://127.0.0.1:18763/com/test/demo/1/demo-1.pom'
with urllib.request.urlopen(u,timeout=3) as r:assert r.read()==b'<project>synthetic</project>'
results['allowlisted_local_bridge']='PASS'
print(json.dumps(results,sort_keys=True))

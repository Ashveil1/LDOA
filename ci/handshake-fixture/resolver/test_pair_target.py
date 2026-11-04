import socket,os,ctypes,json,subprocess
lib=ctypes.CDLL(None,use_errno=True)
def pair(domain,typ,proto):
 fds=(ctypes.c_int*2)();out=lib.syscall(53,ctypes.c_ulonglong(domain),ctypes.c_ulonglong(typ),ctypes.c_ulonglong(proto),ctypes.byref(fds))
 if out:raise OSError(ctypes.get_errno(),'socketpair')
 return socket.socket(fileno=fds[0]),socket.socket(fileno=fds[1])
r={};a,b=pair(socket.AF_UNIX,socket.SOCK_STREAM,0)
a.sendall(b'anonymous');assert b.recv(9)==b'anonymous';r['exact_anonymous_stream']='PASS'
# Connected anonymous endpoints cannot be repointed at a named host socket.
for endpoint in [a,b]:
 try:endpoint.connect(os.environ['HS_TEST_BROKER_PATH']);raise AssertionError('PAIR_RETARGET_ESCAPE')
 except OSError as e:assert e.errno in [22,106],e
r['pair_retarget_named']='DENIED'
# A connected stream pair must not become a named listener or disconnect/reconnect.
for endpoint in [a,b]:
 endpoint.bind(os.environ['HS_TEST_BROKER_PATH']+'.escape'+str(endpoint.fileno()))
 try:endpoint.listen(1);raise AssertionError('PAIR_LISTEN_ESCAPE')
 except OSError:pass
 sockaddr=ctypes.create_string_buffer(b'\0\0'+b'\0'*14)
 out=lib.connect(endpoint.fileno(),sockaddr,16);assert out==-1
 endpoint.shutdown(socket.SHUT_RDWR)
 try:endpoint.connect(os.environ['HS_TEST_BROKER_PATH']);raise AssertionError('PAIR_SHUTDOWN_RETARGET')
 except OSError:pass
r['pair_listen_disconnect_shutdown_retarget']='DENIED'
r['pair_name_binding']='ALLOWED_NOT_LISTENABLE'
a.close();b.close()
x,y=pair(socket.AF_UNIX,socket.SOCK_STREAM,0);y.close()
x.bind(os.environ['HS_TEST_BROKER_PATH']+'.closedpeer')
for operation in [lambda:x.listen(1),lambda:x.connect(os.environ['HS_TEST_BROKER_PATH'])]:
 try:operation();raise AssertionError('CLOSED_PEER_RETARGET')
 except OSError:pass
x.close();r['closed_peer_listen_retarget']='DENIED'
for fam,typ,proto in [(socket.AF_UNIX,socket.SOCK_DGRAM,0),(socket.AF_UNIX,socket.SOCK_SEQPACKET,0),(socket.AF_UNIX,socket.SOCK_STREAM|socket.SOCK_CLOEXEC,0),(socket.AF_INET,socket.SOCK_STREAM,0),(socket.AF_UNIX,socket.SOCK_STREAM,1)]:
 try:pair(fam,typ,proto);raise AssertionError('PAIR_SCOPE_ESCAPE')
 except PermissionError:pass
r['other_pair_forms']='DENIED'
lib=ctypes.CDLL(None,use_errno=True);fds=(ctypes.c_int*2)()
for domain,typ,proto in [(0x100000001,1,0),(1,0x100000001,0),(1,1,0x100000000)]:
 out=lib.syscall(53,ctypes.c_ulonglong(domain),ctypes.c_ulonglong(typ),ctypes.c_ulonglong(proto),ctypes.byref(fds));assert out==-1 and ctypes.get_errno()==1
r['high_word_bypass']='DENIED'
try:socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);raise AssertionError('NAMED_UNIX_CREATION_ESCAPE')
except PermissionError:r['named_unix_socket_creation']='DENIED'
# No inherited Unix sockets at target entry; descriptors opened above are closed.
found=[]
for name in os.listdir('/proc/self/fd'):
 try:
  fd=int(name);copy=socket.fromfd(fd,socket.AF_UNIX,socket.SOCK_STREAM)
  try:
   if copy.getsockopt(socket.SOL_SOCKET,socket.SO_DOMAIN)==socket.AF_UNIX:found.append(fd)
  finally:copy.close()
 except OSError:pass
assert not found,found;r['inherited_unix_fds']='NONE'
for nr in [308,272,165,101,310,311,321,304,425,438]:
 assert lib.syscall(nr,0,0,0,0,0,0)==-1 and ctypes.get_errno()==1,nr
r['escape_syscall_set']='DENIED'
child=subprocess.run([os.sys.executable,'-c','import socket; socket.socket(socket.AF_UNIX)'],capture_output=True,text=True)
assert child.returncode!=0 and 'PermissionError' in child.stderr
r['descendant_named_unix']='DENIED'
# Gradle lock-listener and optional single-use daemon need private IP binds.
udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp.bind(('127.0.0.1',0));udp.close()
tcp=socket.socket();tcp.bind(('127.0.0.1',0));tcp.listen(1);tcp.close()
r['private_ip_udp_tcp_bind']='PASS'
print(json.dumps(r,sort_keys=True))

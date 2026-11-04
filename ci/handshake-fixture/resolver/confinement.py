"""Linux x86_64 namespace boundary, loopback bridge and seccomp target restrictions."""
import os,socket,fcntl,struct,ctypes,sys,subprocess,http.server,threading
PR_SET_NO_NEW_PRIVS=38;PR_SET_SECCOMP=22
# seccomp BPF: architecture guard, block AF_UNIX socket creation and escape syscalls.
class Filter(ctypes.Structure):_fields_=[('code',ctypes.c_ushort),('jt',ctypes.c_ubyte),('jf',ctypes.c_ubyte),('k',ctypes.c_uint)]
class Program(ctypes.Structure):_fields_=[('len',ctypes.c_ushort),('filter',ctypes.POINTER(Filter))]
def restrict():
 if os.uname().machine!='x86_64':raise RuntimeError('ARCH_REQUIRED')
 ins=[(0x20,0,0,4),(0x15,1,0,0xc000003e),(0x06,0,0,0x80000000),(0x20,0,0,0),(0x35,0,1,0x40000000),(0x06,0,0,0x50000|1)]
 # setns, unshare, mount, ptrace, process_vm_readv/writev, bpf, open_by_handle_at; socketpair is filtered separately
 for nr in [308,272,165,101,310,311,321,304,425,438]:ins.extend([(0x15,0,1,nr),(0x06,0,0,0x50000|1)])
 # socketpair53: permit ONLY anonymous AF_UNIX1/SOCK_STREAM1/protocol0.
 # Require zero high words too. No SOCK_CLOEXEC/NONBLOCK or alternate families/types.
 pair=[]
 for offset,value in [(16,1),(20,0),(24,1),(28,0),(32,0),(36,0)]:
  pair.extend([(0x20,0,0,offset),(0x15,1,0,value),(0x06,0,0,0x50000|1)])
 pair.append((0x06,0,0,0x7fff0000))
 ins.append((0x15,0,len(pair),53));ins.extend(pair)
 # socket syscall41: argument0 AF_UNIX1 denied; all IP sockets constrained by network namespace.
 ins.extend([(0x15,0,4,41),(0x20,0,0,16),(0x15,0,1,1),(0x06,0,0,0x50000|1),(0x06,0,0,0x7fff0000),(0x06,0,0,0x7fff0000)])
 arr=(Filter*len(ins))(*(Filter(*i) for i in ins));lib=ctypes.CDLL(None,use_errno=True)
 if lib.prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) or lib.prctl(PR_SET_SECCOMP,2,ctypes.byref(Program(len(ins),arr))):raise OSError(ctypes.get_errno(),'SECCOMP_INSTALL')
def lo_up():
 s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
 fcntl.ioctl(s,0x8914,struct.pack('16sH14x',b'lo',0x49));s.close()
class Bridge(http.server.BaseHTTPRequestHandler):
 def do_HEAD(self):self.relay()
 def do_GET(self):self.relay()
 def relay(self):
  if self.command not in ['HEAD','GET']:self.send_error(405);return
  try:
   s=socket.socket(socket.AF_UNIX);s.connect(self.server.unix_path)
   s.sendall((self.command+' '+self.path+' HTTP/1.0\r\nHost: bounded-local\r\n\r\n').encode('ascii'))
   while True:
    b=s.recv(65536)
    if not b:break
    self.wfile.write(b)
   s.close()
  except Exception:self.send_error(502)
 def log_message(self,*a):pass
def inside(unix_path,command):
 lib=ctypes.CDLL(None,use_errno=True)
 if lib.prctl(4,0,0,0,0):raise OSError('DUMPABLE_DISABLE')
 lo_up();server=http.server.ThreadingHTTPServer(('127.0.0.1',18763),Bridge);server.unix_path=unix_path
 t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
 # Fresh subprocess installs seccomp before exec; no inherited Unix socket descriptors.
 env={k:v for k,v in os.environ.items() if k not in ['LD_PRELOAD','LD_LIBRARY_PATH','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','GRADLE_OPTS','HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy']};env['HS_REVIEWED_LOCAL_MAVEN_PROXY']='http://127.0.0.1:18763/'
 helper=os.path.abspath(__file__)
 r=subprocess.run([sys.executable,helper,'--target']+command,env=env,close_fds=True)
 server.shutdown();return r.returncode
if __name__=='__main__':
 if sys.argv[1]=='--target':
  restrict();os.execvpe(sys.argv[2],sys.argv[2:],os.environ)
 elif sys.argv[1]=='--inside':raise SystemExit(inside(sys.argv[2],sys.argv[3:]))
 else:raise SystemExit(2)

import unittest,struct,copy
from host_contract import parse_elf,closure,Stop
from snapshot_adapter import tool_gate_candidate,HELPER
P='/ldoa-qualification/ndk29-snapshot/toolchains/llvm/prebuilt/linux-x86_64/bin/'
def elf(needed=(),interp=None,runpath=None,rpath=None):
 strings=b'\0';indices=[]
 for value in [*needed,*([runpath] if runpath else []),*([rpath] if rpath else [])]:indices.append(len(strings));strings+=value.encode()+b'\0'
 tags=[(1,i) for i in indices[:len(needed)]];n=len(needed)
 if runpath:tags.append((29,indices[n]));n+=1
 if rpath:tags.append((15,indices[n]))
 tags +=[(5,0x400000+512),(10,len(strings)),(0,0)]
 phnum=2+bool(interp);data=bytearray(1024);data[:16]=b'\x7fELF\x02\x01\x01'+b'\0'*9
 struct.pack_into('<HHIQQQIHHHHHH',data,16,3,62,1,0,64,0,0,64,56,phnum,0,0,0)
 struct.pack_into('<IIQQQQQQ',data,64,1,5,0,0x400000,0,1024,1024,4096)
 struct.pack_into('<IIQQQQQQ',data,120,2,4,256,0x400100,0,len(tags)*16,len(tags)*16,8)
 for i,pair in enumerate(tags):struct.pack_into('<qQ',data,256+i*16,*pair)
 data[512:512+len(strings)]=strings
 if interp:
  value=interp.encode()+b'\0';data[768:768+len(value)]=value;struct.pack_into('<IIQQQQQQ',data,176,3,4,768,0,0,len(value),len(value),1)
 return bytes(data)
def row(path,data):return {'canonical':path,'data':data,'metadata':{'size':len(data),'uid':0,'mode':0o755},'aliases':[]}
class Host(unittest.TestCase):
 def job(self):return {'run_id':'1','run_attempt':'1','head_sha':'a'*40,'workflow_sha':'b'*40,'repository':'Ashveil1/LDOA','ImageOS':'ubuntu22','ImageVersion':'20261026.341.1','runner_environment':'github-hosted'}
 def mapping(self):
  ld='/lib64/ld-linux-x86-64.so.2';lib='/lib/x86_64-linux-gnu/libc.so.6'
  return {P+'clang-21':row(P+'clang-21',elf(['libc.so.6'],ld)),P+'lld':row(P+'lld',elf(['libc.so.6'],ld)),ld:row(ld,elf()),lib:row(lib,elf())}
 def test_minimal_recursive_closure(self):
  m=self.mapping();r=closure({'clang21':P+'clang-21','lld':P+'lld'},m.get);self.assertEqual(len(r['files']),4);self.assertFalse(r['loader_execution'])
 def test_bounds_machine_and_termination(self):
  for data in [b'bad',elf()[:100],elf()[:60]]:
   with self.assertRaises(Stop):parse_elf(data)
  data=bytearray(elf());struct.pack_into('<H',data,18,183)
  with self.assertRaisesRegex(Stop,'ELF_HEADER'):parse_elf(bytes(data))
  data=bytearray(elf());struct.pack_into('<q',data,256+32,1)
  with self.assertRaisesRegex(Stop,'DYNAMIC_UNTERMINATED'):parse_elf(bytes(data))
 def test_missing_ambiguous(self):
  m=self.mapping();m.pop('/lib/x86_64-linux-gnu/libc.so.6')
  with self.assertRaisesRegex(Stop,'HOST_MISSING'):closure({'clang21':P+'clang-21','lld':P+'lld'},m.get)
  m=self.mapping();name='/usr/lib/x86_64-linux-gnu/libc.so.6';m[name]=row(name,elf())
  with self.assertRaisesRegex(Stop,'HOST_AMBIGUOUS'):closure({'clang21':P+'clang-21','lld':P+'lld'},m.get)
 def test_rpath_inheritance_never_guessed(self):
  m=self.mapping();m[P+'clang-21']=row(P+'clang-21',elf(['libc.so.6'],'/lib64/ld-linux-x86-64.so.2',rpath='$ORIGIN'))
  with self.assertRaisesRegex(Stop,'RPATH_INHERITANCE_UNSUPPORTED'):closure({'clang21':P+'clang-21','lld':P+'lld'},m.get)
 def test_relative_runpath_bad_soname_and_oversize(self):
  m=self.mapping();m[P+'clang-21']=row(P+'clang-21',elf(['libc.so.6'],'/lib64/ld-linux-x86-64.so.2',runpath='relative'))
  with self.assertRaises(Stop):closure({'clang21':P+'clang-21','lld':P+'lld'},m.get)
  with self.assertRaisesRegex(Stop,'NEEDED_NAME'):parse_elf(elf(['../bad']))
 def test_nodecap(self):
  import host_contract
  from unittest.mock import patch
  with patch.object(host_contract,'MAX_NODES',1):
   with self.assertRaisesRegex(Stop,'HOST_NODE_CAP'):closure({'clang21':P+'clang-21','lld':P+'lld'},self.mapping().get)
 def test_adapter_never_approves_historical_or_missing_authority(self):
  with self.assertRaisesRegex(Stop,'JOB_AUTHORITY_UNRESOLVED'):tool_gate_candidate({}, self.job(), {}, {}, [], [])
  job=self.job();auth={'status':'SEPARATELY_REVIEWED_THIS_JOB_SNAPSHOT_AND_HOST_OS_CONTRACT','job':job}
  env={'helper_sha256':HELPER,'job':job,'receipt':{'managed_image':job,'status':'SNAPSHOT_ONLY_NOT_APPROVED','snapshot_only':True,'cleanup':'ABSENT'}}
  with self.assertRaisesRegex(Stop,'SNAPSHOT_DELETED_OR_NOT_LIVE'):tool_gate_candidate(env,job,auth,{},[],[])
 def test_host_binding_change(self):
  job=self.job();auth={'status':'SEPARATELY_REVIEWED_THIS_JOB_SNAPSHOT_AND_HOST_OS_CONTRACT','job':job};env={'helper_sha256':HELPER,'job':job,'receipt':{'managed_image':job,'status':'SNAPSHOT_ONLY_NOT_APPROVED','snapshot_only':True},'lifecycle':'SEALED_LIVE_THIS_JOB_BEFORE_CONSUMER'}
  with self.assertRaisesRegex(Stop,'HOST_PRE_POST_CHANGED'):tool_gate_candidate(env,job,auth,{},[1],[2])
if __name__=='__main__':unittest.main()

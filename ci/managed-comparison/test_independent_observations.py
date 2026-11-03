import unittest,copy,hashlib,base64,tempfile,pathlib,json
from unittest.mock import patch
import independent_observations as v
import paired_repro as old
from test_paired_repro import Repro
class Independent(unittest.TestCase):
 def base(self,role):
  legacy=Repro().base(role)
  return {'schema':2,'contract':v.CONTRACT,'binding':legacy['binding'],'declared_inputs':{k:{'fixture':'fixed'} for k in v.INPUTS},'outputs':dict(legacy['outputs'],signing={'fixture':'fixed'}),'manifest_pins':legacy['manifest_pins'],'host_observation':{'provenance':'PROCESS_ENVIRONMENT_NOT_VERIFIED_VM_IDENTITY','ImageOS':'ubuntu24','ImageVersion':'20261026.354.1' if role=='first' else '20261101.360.1'},'host_equivalence':'NOT_ASSERTED','causal_control':'NOT_ESTABLISHED','limitations':v.LIMITS[:]}
 def test_new_claim_distinct_hosts_and_limits(self):
  x=v.join(self.base('first'),self.base('second'));self.assertEqual(x['host_differences']['ImageVersion'],['20261026.354.1','20261101.360.1']);self.assertEqual(x['host_equivalence'],'NOT_ASSERTED');self.assertEqual(x['causal_control'],'NOT_ESTABLISHED');self.assertNotIn('PASS',x['result']);self.assertIn('GUEST_IMAGE_REVISION_CONTENT_UNKNOWN_NOT_ASSERTED',x['limitations'])
 def test_every_declared_input_and_output_drift_refused(self):
  for section,keys in [('declared_inputs',v.INPUTS),('outputs',{'apk','payload','signing'})]:
   for k in keys:
    b=self.base('second');b[section][k]={'fixture':'changed'}
    with self.subTest(section=section,k=k),self.assertRaises(ValueError):v.join(self.base('first'),b)
 def test_schema_contract_claim_upgrade_missinghost_refused(self):
  for k,x in [('schema',1),('contract','unknown'),('host_equivalence','SAME_IMAGE'),('causal_control','ESTABLISHED'),('limitations',[]),('host_observation',{}),('declared_inputs',{}),('outputs',{})]:
   b=self.base('second');b[k]=x
   with self.subTest(k=k),self.assertRaises(ValueError):v.join(self.base('first'),b)
  for k in ['ImageOS','ImageVersion','provenance']:
   b=self.base('second');del b['host_observation'][k]
   with self.assertRaises(ValueError):v.join(self.base('first'),b)
  for bad in ['',None,'contains space','x'*129]:
   b=self.base('second');b['host_observation']['ImageVersion']=bad
   with self.assertRaises(ValueError):v.join(self.base('first'),b)
  b=self.base('second');b['host_observation']['provenance']='VERIFIED_VM';
  with self.assertRaises(ValueError):v.join(self.base('first'),b)
 def test_wire_schema_unknown_mix_distinct(self):
  a=self.base('first');self.assertEqual(v.decode(v.frames(a)),a)
  one=Repro().base('first');raw=old.canonical(one);h=hashlib.sha256(raw).hexdigest();frames=['REPRO1 START '+str(len(raw))+' '+h,'REPRO1 DATA 0 '+base64.b64encode(raw).decode(),'REPRO1 END '+h]
  for lines in [frames,v.frames(a)+frames,[x.replace('REPRO2','REPRO3') for x in v.frames(a)],v.frames(a)[:-1],v.frames(a)+[v.frames(a)[-1]],v.frames(one),v.frames(dict(a,contract='unknown'))]:
   with self.assertRaises(Exception):v.decode(lines)
  with self.assertRaises(ValueError):old.decode(v.frames(a))
  with self.assertRaises(ValueError):old.join(a,self.base('second'))
  with self.assertRaises(ValueError):v.join(a,Repro().base('second'))
 def test_legacy_semantics_and_historical_shape_refusal(self):
  a=Repro().base('first');b=Repro().base('second');a['inputs']['image']={'ImageOS':'ubuntu24','ImageVersion':'20261026.354.1'};b['inputs']['image']={'ImageOS':'ubuntu24','ImageVersion':'20261101.360.1'}
  with self.assertRaisesRegex(ValueError,'paired inputs disagreement'):old.join(a,b)
  b['inputs']['image']=a['inputs']['image'];self.assertIn('PASS build reproducibility only',old.join(a,b)['result'])
 def test_v1_functions_byte_unchanged(self):
  import subprocess
  root=pathlib.Path(__file__).parents[2];rel='ci/managed-comparison/paired_repro.py'
  self.assertEqual((root/rel).read_bytes(),subprocess.check_output(['git','show','f0f231a119380b103873cb8a5ac093df0c621f7b:'+rel],cwd=root))
 def test_actual_fresh_build_routing_and_recipe_pin(self):
  # Synthetic fresh-build measurement, never an API to convert stored v1 receipts.
  measured=Repro().base('first');measured['inputs']={k:{'fixture':'fixed'} for k in v.INPUTS};measured['inputs']['image']={'ImageOS':'ubuntu24','ImageVersion':'20261101.360.1'};measured['outputs']['signing']={'fixture':'fixed'}
  with patch.object(v.legacy,'build',return_value=measured) as build,patch.object(v.legacy,'filehash',return_value='a'*64):
   x=v.build(pathlib.Path('/fixture'),pathlib.Path('/private'),'first',{});build.assert_called_once();self.assertIn('independent_observations.py',x['declared_inputs']['recipes']);self.assertNotIn('image',x['declared_inputs']);self.assertEqual(x['host_observation']['ImageVersion'],'20261101.360.1')
 def test_exact_bounded_frame_and_binding_tamper(self):
  a=self.base('first');f=v.frames(a)
  for bad in [f+f,[f[0],f[1].replace('DATA 0','DATA 1'),*f[2:]],['REPRO2 '+'x'*5000]]:
   with self.assertRaises(Exception):v.decode(bad)
  for k in ['repository','run_id','run_attempt','sha','job','role']:
   b=self.base('second');b['binding'][k]='wrong'
   with self.assertRaises(ValueError):v.join(a,b)
 def test_actual_v2_aggregate_strict_cases_and_labels(self):
  from test_evidence import Evidence
  import split_join,transport,shutil
  e=Evidence();e.setUp()
  try:
   receipts=[self.base('first'),self.base('second')];dirs=[]
   for case,receipt in zip(['original','patched'],receipts):
    d=e.p/receipt['binding']['role'];d.mkdir();dirs.append(d);shutil.copytree(e.e/case,d/case)
    receipt['manifest_pins'][case]='a'*64
    (d/'coverage.json').write_text(json.dumps({'complete':True,'expected_case':case,'binding':receipt['binding'],'issues':[],'selected':['host-phase.txt','runtime-sdk.json']+[case+'/'+n for n in transport.CASE_FILES],'private_excluded':True}))
    (d/'runtime-sdk.json').write_text(json.dumps({'schema':1,'case':case,'binding':receipt['binding'],'properties':{'Pkg.Revision':'37.2.13','Pkg.BuildId':'16740729'}}))
   result=split_join.join_v2(*receipts,*dirs);self.assertEqual(result['result'],v.RESULT);self.assertEqual(result['manual_status'],'PENDING');self.assertEqual(result['causal_control'],'NOT_ESTABLISHED')
   f=dirs[1]/'patched/managed-0-capture-bracket.json';b=json.loads(f.read_text());b['schema']=2;f.write_text(json.dumps(b))
   with self.assertRaises(ValueError):split_join.join_v2(*receipts,*dirs)
   f.unlink()
   with self.assertRaises(ValueError):split_join.join_v2(*receipts,*dirs)
  finally:e.tearDown()
 def test_workflow_wire_contract_transport_no_new_files(self):
  import subprocess
  root=pathlib.Path(__file__).parents[2];s=(root/'.github/workflows/mobile-desktop.yml').read_text();self.assertEqual(s.count('independent_observations.py --role'),2);self.assertIn('FIRST_CONTRACT:',s)
  rel='ci/managed-comparison/transport.py';self.assertEqual((root/rel).read_bytes(),subprocess.check_output(['git','show','f0f231a119380b103873cb8a5ac093df0c621f7b:'+rel],cwd=root))
if __name__=='__main__':unittest.main()

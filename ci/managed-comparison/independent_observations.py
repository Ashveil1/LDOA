"""Version 2: independent-host observations, never host-equivalent causal A/B."""
import argparse,base64,hashlib,json,os,pathlib,re
import paired_repro as legacy
CONTRACT='independent-host-geometry-observations-v2'
RESULT='TWO_GEOMETRY_OBSERVATIONS_MANUAL_REVIEW_REQUIRED'
LIMITS=['HOST_KERNEL_KVM_CPU_SCHEDULING_LOAD_NOT_CONTROLLED','HOST_DYNAMIC_LIBRARIES_NOT_CLOSED','GUEST_IMAGE_REVISION_CONTENT_UNKNOWN_NOT_ASSERTED','PLATFORM_TOOLS_UNKNOWN_NOT_ASSERTED','ACTION_MUTABLE_TAGS_NOT_IMMUTABLE','DECLARED_BUILD_PACKAGES_NOT_FULL_DEPENDENCY_CLOSURE','SDK_METADATA_NOT_BINARY_HASH','NO_CAUSAL_AB_PROOF','NO_PRESENTATION_FPS_READINESS_CLAIM']
INPUTS={'source','recipes','submodules_sha256','build_packages','toolchain_sha256','archive','build_environment_sha256','environment'}
def validate(x,role):
 if not isinstance(x,dict) or set(x)!={'schema','contract','binding','declared_inputs','outputs','manifest_pins','host_observation','limitations','host_equivalence','causal_control'} or type(x['schema']) is not int or x['schema']!=2 or x['contract']!=CONTRACT:raise ValueError('v2 contract/schema')
 if x['host_equivalence']!='NOT_ASSERTED' or x['causal_control']!='NOT_ESTABLISHED' or x['limitations']!=LIMITS:raise ValueError('v2 claim/limitations')
 h=x['host_observation']
 if not isinstance(h,dict) or set(h)!={'provenance','ImageOS','ImageVersion'} or h['provenance']!='PROCESS_ENVIRONMENT_NOT_VERIFIED_VM_IDENTITY' or any(not isinstance(h[k],str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,128}',h[k]) for k in ['ImageOS','ImageVersion']):raise ValueError('host observation')
 if not isinstance(x['declared_inputs'],dict) or set(x['declared_inputs'])!=INPUTS or any(not v for v in x['declared_inputs'].values()):raise ValueError('declared input inventory')
 if not isinstance(x['outputs'],dict) or set(x['outputs'])!={'apk','payload','signing'}:raise ValueError('output inventory')
 # Reuse the unmodified v1 shape/binding/pin validator only, never v1 join.
 legacy.validate_receipt({'schema':1,'binding':x['binding'],'inputs':x['declared_inputs'],'outputs':x['outputs'],'manifest_pins':x['manifest_pins'],'label':'internal shape validation'},role)
 if len(legacy.canonical(x))>65536:raise ValueError('receipt cap')
 return x

def build(root,private,role,env):
 # Fresh measurements only. There is no historical-receipt conversion API.
 measured=legacy.build(root,private,role,env)
 declared={k:v for k,v in measured['inputs'].items() if k!='image'}
 declared['recipes']['independent_observations.py']=legacy.filehash(root/'ci/managed-comparison/independent_observations.py')
 declared['recipes']['split_join.py']=legacy.filehash(root/'ci/managed-comparison/split_join.py')
 return validate({'schema':2,'contract':CONTRACT,'binding':measured['binding'],'declared_inputs':declared,'outputs':measured['outputs'],'manifest_pins':measured['manifest_pins'],'host_observation':{'provenance':'PROCESS_ENVIRONMENT_NOT_VERIFIED_VM_IDENTITY',**measured['inputs']['image']},'limitations':LIMITS,'host_equivalence':'NOT_ASSERTED','causal_control':'NOT_ESTABLISHED'},role)

def frames(x):
 raw=legacy.canonical(x);h=hashlib.sha256(raw).hexdigest()
 return ['REPRO2 START '+str(len(raw))+' '+h]+['REPRO2 DATA '+str(i//3072)+' '+base64.b64encode(raw[i:i+3072]).decode() for i in range(0,len(raw),3072)]+['REPRO2 END '+h]

def decode(lines):
 lines=list(lines)
 if any(re.search(r'REPRO(?!2 )[0-9]+ ',l) for l in lines):raise ValueError('mixed/unknown frame version')
 # Same bounded framing algorithm, distinct required wire marker and schema.
 selected=[l[l.index('REPRO2 '):].replace('REPRO2 ','REPRO1 ',1) for l in lines if 'REPRO2 ' in l]
 x=legacy.decode(selected)
 if not isinstance(x,dict) or x.get('schema')!=2 or x.get('contract')!=CONTRACT:raise ValueError('wire/schema disagreement')
 return validate(x,x.get('binding',{}).get('role'))

def join(first,second):
 validate(first,'first');validate(second,'second')
 for k in ['repository','run_id','run_attempt','sha']:
  if first['binding'][k]!=second['binding'][k]:raise ValueError('binding disagreement')
 for k in ['declared_inputs','outputs']:
  if legacy.canonical(first[k])!=legacy.canonical(second[k]):raise ValueError('v2 exact '+k+' disagreement')
 hosts=[first['host_observation'],second['host_observation']]
 return {'schema':2,'contract':CONTRACT,'result':'EXACT_DECLARED_BUILD_OUTPUT_IDENTITY_RUNTIME_OBSERVATIONS_PENDING','host_equivalence':'NOT_ASSERTED','causal_control':'NOT_ESTABLISHED','limitations':LIMITS,'host_observations':hosts,'host_differences':{k:[hosts[0][k],hosts[1][k]] for k in ['ImageOS','ImageVersion'] if hosts[0][k]!=hosts[1][k]},'binding':{k:first['binding'][k] for k in ['repository','run_id','run_attempt','sha']},'declared_inputs':legacy.digest(first['declared_inputs']),'outputs':legacy.digest(first['outputs']),'device_manifest_equality':'NOT asserted; each exact case pin retained'}

def main():
 a=argparse.ArgumentParser();a.add_argument('--role',required=True,choices=['first','second']);a.add_argument('--private',required=True,type=pathlib.Path);args=a.parse_args();x=build(pathlib.Path.cwd(),args.private,args.role,os.environ)
 for line in frames(x):print(line,flush=True)
 scalars={'contract':CONTRACT,'inputs':legacy.digest(x['declared_inputs']),'outputs':legacy.digest(x['outputs']),**x['binding']}
 if args.role=='second':
  for k in ['contract','inputs','outputs','repository','run_id','run_attempt','sha']:
   if os.environ.get('FIRST_'+k.upper())!=str(scalars[k]):raise ValueError('v2 first-job scalar mismatch '+k)
  if os.environ.get('FIRST_JOB')!='repro_first' or os.environ.get('FIRST_ROLE')!='first':raise ValueError('first-job identity')
  print('OBSERVATION_JOIN2 EXACT_DECLARED_BUILD_OUTPUT_IDENTITY; host equivalence NOT_ASSERTED; causal control NOT_ESTABLISHED; full receipts/cases/manual review REQUIRED',flush=True)
 with open(os.environ['GITHUB_OUTPUT'],'a') as f:
  for k,v in scalars.items():f.write(k+'='+str(v)+'\n')
if __name__=='__main__':main()

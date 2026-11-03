"""External aggregate. Two distinct hosted jobs, both build+case evidence."""
import json
import paired_repro as repro
import check_case
import transport

def join(first_build,second_build,first_dir,second_dir):
 result=repro.join(first_build,second_build)
 for receipt,d,case in [(first_build,first_dir,'original'),(second_build,second_dir,'patched')]:
  coverage=json.loads((d/'coverage.json').read_text())
  if coverage.get('complete') is not True or coverage.get('expected_case')!=case or coverage.get('binding')!=receipt['binding']:raise ValueError('case coverage')
  expected={'host-phase.txt','runtime-sdk.json'}|{case+'/'+n for n in transport.CASE_FILES}
  if coverage.get('issues')!=[] or set(coverage.get('selected',[]))!=expected or coverage.get('private_excluded') is not True:raise ValueError('case coverage inventory')
  if (d/('patched' if case=='original' else 'original')).exists():raise ValueError('unexpected other case')
  sdk=json.loads((d/'runtime-sdk.json').read_text())
  if sdk!={'schema':1,'case':case,'binding':receipt['binding'],'properties':{'Pkg.Revision':'37.2.13','Pkg.BuildId':'16740729'}}:raise ValueError('SDK evidence binding/exactness')
  pin=receipt['manifest_pins'][case]
  check_case.check(d/case,case,pin)
 result['result']='PASS paired build and executed original/patched case gates; actual PNG/input manual review REQUIRED'
 return result

if __name__=='__main__':
 import argparse,pathlib
 ap=argparse.ArgumentParser();ap.add_argument('--first-log',type=pathlib.Path,required=True);ap.add_argument('--second-log',type=pathlib.Path,required=True);ap.add_argument('--first-case',type=pathlib.Path,required=True);ap.add_argument('--second-case',type=pathlib.Path,required=True);a=ap.parse_args()
 print(json.dumps(join(repro.decode(a.first_log.read_text().splitlines()),repro.decode(a.second_log.read_text().splitlines()),a.first_case,a.second_case),sort_keys=True))

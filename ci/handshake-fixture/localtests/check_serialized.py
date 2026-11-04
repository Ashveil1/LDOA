import sys,pathlib
sys.path.insert(0,str(pathlib.Path(__file__).parents[1]))
from check_result import validate,assess
r=assess(validate(pathlib.Path(sys.argv[1]).read_bytes()))
assert r['assessment']=='MATCH',r
assert 'monitor_disabled' in r['capability_gaps']
print('ACTUAL_SERIALIZER_SCHEMA_ASSESS_PARITY_PASS_SYNTHETIC_INPUTS_ONLY')

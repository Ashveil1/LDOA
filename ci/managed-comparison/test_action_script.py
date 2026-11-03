"""Exact v2 parseScript contract executed from archived upstream TS source."""
import pathlib,subprocess,tempfile,unittest,json,re,os
import intercase_absence as a
P=pathlib.Path(__file__).parent;ROOT=P.parents[1]
class ActionScript(unittest.TestCase):
 def test_action_parser_actual_source_and_singlecommand(self):
  # This fixed public upstream parser is tiny and source-grounded.
  source=(P/'action-script-parser.ts.fixture').read_text()
  js=source.replace('export function parseScript(rawScript: string): Array<string>','function parseScript(rawScript)').replace('const scripts: Array<string>','const scripts').replace('(value: string)','(value)')
  workflow=(ROOT/'.github/workflows/mobile-desktop.yml').read_text()
  line=re.search(r'^          script: (.+)$',workflow,re.M).group(1)
  old="python3 - <<'PY'\nimport pathlib,re\nPY"
  with tempfile.TemporaryDirectory() as t:
   f=pathlib.Path(t)/'parser.js';f.write_text(js+'\nconsole.log(JSON.stringify([parseScript('+json.dumps(line)+'),parseScript('+json.dumps(old)+')]));')
   new,bad=json.loads(subprocess.check_output(['node',str(f)],text=True))
   self.assertEqual(new,[line]);self.assertEqual(bad,["python3 - <<'PY'",'import pathlib,re','PY'])
   # Execute action-style sh -c once, substitute only diagnostic Python.
   b=pathlib.Path(t)/'bin';b.mkdir();fake=b/'python3';fake.write_text('#!/bin/sh\nprintf "called\\n" >> "$CALLS"\nprintf "%s\\n" "$@" > "$ARGS"\n');fake.chmod(0o700)
   env={**os.environ,'PATH':str(b)+':'+os.environ['PATH'],'RUNNER_TEMP':t,'CALLS':t+'/calls','ARGS':t+'/args'}
   for cmd in new:subprocess.run(['sh','-c',cmd],env=env,check=True)
   self.assertEqual(pathlib.Path(t+'/calls').read_text(),'called\n');self.assertEqual(pathlib.Path(t+'/args').read_text().splitlines(),['ci/managed-comparison/intercase_absence.py','anchor','--anchor',t+'/original-emulator-identity.json'])
 def test_sdk_strict_properties(self):
  with tempfile.TemporaryDirectory() as t:
   sdk=pathlib.Path(t);(sdk/'emulator').mkdir();f=sdk/'emulator/source.properties';good='Pkg.UserSrc=false\nPkg.Revision=37.2.13\nPkg.BuildId=16740729\n'
   f.write_text(good);self.assertEqual(a.sdk_assert(sdk)['Pkg.Revision'],'37.2.13')
   for bad in [good.replace('37.2.13','37.2.14'),good.replace('16740729','1'),good+'Pkg.Revision=37.2.13\n',good.replace('Pkg.BuildId=16740729\n',''),good+'Pkg.BuildId=1\n','x'*4097]:
    f.write_text(bad)
    with self.subTest(bad=bad[:100]),self.assertRaises(Exception):a.sdk_assert(sdk)
 def test_sdk_refusal_precedes_anchor(self):
  from unittest.mock import patch
  with tempfile.TemporaryDirectory() as t:
   sdk=pathlib.Path(t);(sdk/'emulator').mkdir();(sdk/'emulator/source.properties').write_text('Pkg.Revision=37.2.14\nPkg.BuildId=16740729\n')
   with patch('sys.argv',['intercase_absence.py','anchor','--anchor',t+'/anchor.json']),patch.dict(os.environ,{'ANDROID_HOME':t}),patch.object(a,'anchor') as target:
    self.assertEqual(a.main(),1);target.assert_not_called();self.assertFalse((sdk/'anchor.json').exists())
if __name__=='__main__':unittest.main()

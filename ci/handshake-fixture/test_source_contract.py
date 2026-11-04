import pathlib,re,json,unittest,hashlib,ast,tempfile
B=pathlib.Path(__file__).parent
class SourceContract(unittest.TestCase):
 def test_android_inventory_parity(self):
  source=(B/'fixture/src/androidTest/java/dev/phonelinux/handshakefixture/HandshakeInstrumentation.java').read_text();raw=re.search(r'CASES=\{(.*?)\};',source,re.S)[1];names=re.findall(r'"([a-z_]+)"',raw);self.assertEqual(names,[r['case'] for r in json.loads((B/'cases.json').read_text())])
 def test_native_extra_dependencies(self):
  d=json.loads((B/'android-native-inputs.json').read_text());self.assertEqual({r['name'] for r in d['files']},{'libproot.so','libproot_loader.so','libtalloc.so','libandroid-shmem.so'})
 def test_generated_link_inventory(self):
  d=json.loads((B/'sysroot-file-selection.json').read_text());self.assertEqual(d['generated_links'][0]['path'],'lib');self.assertEqual(d['generated_links'][1]['path'],'lib64')
 def test_package_selection_parity(self):
  s=json.loads((B/'sysroot-file-selection.json').read_text());l=json.loads((B/'sysroot-packages.lock.json').read_text());self.assertEqual(s['packages'],l['packages']);self.assertEqual(len(s['packages']),13)
 def test_generation_not_inode_heuristic(self):
  s=(B/'native/producer.c').read_text();self.assertIn('request_generation(buf',s);self.assertNotIn('request_ino',s);self.assertNotIn('st_ctim',s)
  runner=(B/'fixture/src/androidTest/java/dev/phonelinux/handshakefixture/CaseRunner.java').read_text();self.assertIn('session.pin+" 2\\n"',runner)
 def test_compile_evidence_current(self):
  d=json.loads((B/'compile-freshness.json').read_text())
  for path,pin in {**d['source_files'],**d['bound_evidence']}.items():self.assertEqual(hashlib.sha256((B/path).read_bytes()).hexdigest(),pin,path)
  source=hashlib.sha256((B/'native/producer.c').read_bytes()).hexdigest();self.assertEqual(json.loads((B/'compile-evidence.json').read_text())['producer_source_sha256'],source)
 def test_runtime_zip_hash_after_close(self):
  s=(B/'stage_runtime.py').read_text();self.assertIn("\noutmanifest=",s);self.assertNotIn("\n outmanifest=",s)
 def test_dormant_inventory_and_gate(self):
  s=(B/'fixture/src/androidTest/java/dev/phonelinux/handshakefixture/DormantSuite.java').read_text();raw=re.search(r'NAMES=\{(.*?)\};',s,re.S)[1];self.assertEqual(re.findall(r'"([a-z_]+)"',raw),[r['case'] for r in json.loads((B/'cases.json').read_text())]);self.assertIn('executor.state.finish()',s)
  expected=re.search(r'EXPECTED=\{(.*?)\};',s,re.S)[1];self.assertEqual(re.findall(r'"([A-Z_]+)"',expected),[r['expected'] for r in json.loads((B/'cases.json').read_text())])
  active=(B/'fixture/src/androidTest/java/dev/phonelinux/handshakefixture/HandshakeInstrumentation.java').read_text();self.assertNotIn('DormantSuite.',active);self.assertIn('PREIMPLEMENTATION_BLOCKED',active);self.assertIn('if (FixedScopeGate.accepts(launchArguments))',active)
 def test_authored_inventory_excludes_only_generated_directories(self):
  tree=ast.parse((B/'prepare_payload.py').read_text());expr=next(n.value for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='source' for t in n.targets))
  with tempfile.TemporaryDirectory() as tmp:
   b=pathlib.Path(tmp)
   included=['native/producer.c','fixture/src/main/cpp/CMakeLists.txt','fixture/src/main/java/A.java','fixture/build.gradle.kts','build_selected.py','prepare_payload.py']
   excluded=['private-run/hs-fixture-private/debug.keystore','private-run/maven/plugin.jar','fixture/.cxx/Debug/configure_fingerprint.bin','fixture/build/intermediates/output','generated/java/FixedInputs.java','.gradle/cache','__pycache__/cached.pyc']
   for name in included+excluded:
    p=b/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(name)
   actual=eval(compile(ast.Expression(expr),'<actual_inventory>','eval'),{'b':b,'h':lambda p:hashlib.sha256(p.read_bytes()).hexdigest()})
   self.assertEqual(set(actual),set(included))
 def test_no_successful_cleanup_claim(self):
  s=(B/'fixture/src/androidTest/java/dev/phonelinux/handshakefixture/OwnedTree.java').read_text();self.assertIn('return false; // Fail closed',s)
if __name__=='__main__':unittest.main()

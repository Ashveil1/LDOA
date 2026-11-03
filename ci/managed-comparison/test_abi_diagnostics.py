"""Local text-guard fixtures; no target execution or ABI closure claim."""
import pathlib,re,unittest
import abi_result as a
import test_abi_result as baseline
ROOT=pathlib.Path(__file__).resolve().parents[2]
class Diagnostics(baseline.ABI):
 def test_kotlin_python_guard_exact(self):
  text=(ROOT/'app/src/androidTest/java/dev/phonelinux/proto/MobileDesktopEntryTest.kt').read_text()
  self.assertIn('Regex("'+a.LOADER_FAILURE+'",RegexOption.IGNORE_CASE)',text)
 def test_legit_identifiers(self):
  self.legit='''libgpg-error.so.0 => /lib/x86_64-linux-gnu/libgpg-error.so.0 (0x123)
 /lib/x86_64-linux-gnu/libgcrypt.so.20:
  libgpg-error.so.0 (GPG_ERROR_1.0) => /lib/x86_64-linux-gnu/libgpg-error.so.0
 /lib/x86_64-linux-gnu/libgpg-error.so.0:
'''
  self.assertNotRegex(self.legit,a.LOADER_FAILURE)
  self.assertIn('libgpg-error.so.0',self.call(l=self.l+'\n'+self.legit))
 def test_mixed_legit_failure(self):
  legit='libgpg-error.so.0 (GPG_ERROR_1.0) => /lib/x86_64-linux-gnu/libgpg-error.so.0\n'
  failures=['libX11.so.6 => not found',"bin/pcmanfm: /lib/libc.so.6: version `GLIBC_999' not found (required by bin/pcmanfm)","bin/pcmanfm: /lib/libc.so.6: weak version `X' not found (required by bin/pcmanfm)",'undefined symbol: error_helper (bin/pcmanfm)','bin/pcmanfm: symbol lookup error: /lib/libfm.so.4: undefined symbol: fm_x','bin/pcmanfm: error while loading shared libraries: libX.so: cannot open shared object file: No such file or directory','bin/pcmanfm: wrong ELF class: ELFCLASS32','bin/pcmanfm: /lib/libX.so: no version information available (required by bin/pcmanfm)','readelf: Error: Not an ELF file','readelf: Warning: invalid section header','ldd: bin/pcmanfm: not a dynamic executable','ld.so: ERROR: object cannot be preloaded: ignored.','bin/pcmanfm: error: invalid ELF header','unknown error','cannot open file']
  for f in failures:
   for mixed in [legit+f,f+'\n'+legit,legit+f+'\n'+legit]:
    with self.subTest(f=f),self.assertRaises(ValueError):self.call(l=self.l+'\n'+mixed)
 def test_fixed_identifiers_old_new(self):
  # Source format rtld.c: library (version) => resolved pathname.
  # Actual full run41638811412 old6/new0 remains in private review notes.
  text="libgpg-error.so.0 => /lib/x86_64-linux-gnu/libgpg-error.so.0 (0x123)\nlibgpg-error.so.0 (GPG_ERROR_1.0) => /lib/x86_64-linux-gnu/libgpg-error.so.0"
  self.assertEqual(len(re.findall('error',text,re.I)),5)
  self.assertNotRegex(text,a.LOADER_FAILURE)
if __name__=='__main__':unittest.main()

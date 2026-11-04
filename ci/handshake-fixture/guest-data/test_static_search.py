import unittest,pathlib,hashlib
from qualify_static import dynamic_paths,needed_target,script_refs
from guest_data_only import Stop,selected,OPTIONAL_GI_UTILITIES
class Search(unittest.TestCase):
 def test_origin_required_no_sibling_guess(self):
  mapped={'usr/lib/x86_64-linux-gnu/gconv/libGB.so':{'type':'file'}}
  with self.assertRaises(Stop):needed_target('usr/lib/x86_64-linux-gnu/gconv/EUC-CN.so','libGB.so',{'RUNPATH':[],'RPATH':[]},mapped)
  target,dirs=needed_target('usr/lib/x86_64-linux-gnu/gconv/EUC-CN.so','libGB.so',{'RUNPATH':['$ORIGIN'],'RPATH':[]},mapped)
  self.assertEqual(target,'usr/lib/x86_64-linux-gnu/gconv/libGB.so')
 def test_unsupported_path_and_missing_still_fail(self):
  for paths in [{'RPATH':['/tmp/host'],'RUNPATH':[]},{'RPATH':[],'RUNPATH':['$ORIGIN/../unsafe']}]:
   with self.assertRaises(Stop):needed_target('a/x','libGB.so',paths,{})
  with self.assertRaises(Stop):needed_target('a/x','missing.so',{'RUNPATH':['$ORIGIN'],'RPATH':[]},{})
 def test_tags_capture_and_script_absolute_relative(self):
  self.assertEqual(dynamic_paths(' (RUNPATH) Library runpath: [$ORIGIN]')['RUNPATH'],['$ORIGIN'])
  rows={'usr/lib/x86_64-linux-gnu/libc.so.6':{'type':'file'},'usr/lib/gcc/x86_64-linux-gnu/13/libgcc.a':{'type':'file'}}
  refs=script_refs('/*GNU*/ OUTPUT_FORMAT(elf64-x86-64) GROUP ( /lib/x86_64-linux-gnu/libc.so.6 AS_NEEDED ( -lgcc ) )','usr/lib/gcc/x86_64-linux-gnu/13/libgcc_s.so',rows)
  self.assertEqual(len(refs),2)
 def test_script_unknown_or_missing_never_green(self):
  for text in ['SEARCH_DIR(/host)', 'GROUP ( missing.so )','GROUP ( ../../escape )','OUTPUT_FORMAT(elf64-x86-64)']:
   with self.assertRaises(Stop):script_refs(text,'usr/lib/x.so',{})
 def test_exact_three_gi_exclusions_not_libraries(self):
  self.assertEqual(len(OPTIONAL_GI_UTILITIES),3)
  for n in OPTIONAL_GI_UTILITIES:self.assertFalse(selected(n))
  for n in ['usr/include/glib-2.0/girepository.h','usr/lib/x86_64-linux-gnu/libgio-2.0.so.0','usr/lib/x86_64-linux-gnu/glib-2.0/include/glibconfig.h']:self.assertTrue(selected(n))
if __name__=='__main__':unittest.main()

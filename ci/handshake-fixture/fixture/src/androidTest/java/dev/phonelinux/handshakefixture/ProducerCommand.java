package dev.phonelinux.handshakefixture;
import android.content.Context;
import java.io.File;
import java.util.Arrays;
import java.util.Map;
/** Fixed ELF64 direct producer command, not shell or arbitrary guest execution. */
final class ProducerCommand {
 static ProcessBuilder make(Context context,StagingDriver.Staged staged,HandshakeInstrumentation.Session s,String mode){
  if(!mode.equals("normal")&&!mode.equals("monitor_disabled"))throw new IllegalArgumentException("MODE");
  s.verifyPaths();staged.verifyPaths();
  if(!s.privateParent.equals(staged.sessions))throw new IllegalStateException("SESSION_ROOT_SCOPE");
  File nd=new File(context.getApplicationInfo().nativeLibraryDir);
  ProcessBuilder p=new ProcessBuilder(Arrays.asList(new File(nd,"libproot.so").getAbsolutePath(),"--link2symlink","--kill-on-exit","-0","-r",staged.root.getAbsolutePath(),"-b",s.privateParent.getAbsolutePath()+":/run/ldoa-handshake","-b","/dev","-b","/proc","-b","/sys","-w","/","/producer",s.child,s.token,s.pin,mode));
  Map<String,String>e=p.environment();e.clear();e.put("PROOT_LOADER",new File(nd,"libproot_loader.so").getAbsolutePath());
  e.put("LD_LIBRARY_PATH",nd.getAbsolutePath());e.put("PROOT_TMP_DIR",staged.tmp.getAbsolutePath());e.put("TMPDIR","/tmp");
  e.put("PATH","/usr/bin:/bin");e.put("GIO_USE_FILE_MONITOR","inotify");e.put("GIO_EXTRA_MODULES","/empty-gio-modules");
  // No PROOT_LOADER_32 because every executable/runtime ELF is64bit. No X socket or DISPLAY.
  p.redirectErrorStream(false);return p;
 }
}

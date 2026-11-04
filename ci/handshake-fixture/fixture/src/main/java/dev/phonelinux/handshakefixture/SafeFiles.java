package dev.phonelinux.handshakefixture;
public final class SafeFiles {
 static { System.loadLibrary("handshake_safe"); }
 private SafeFiles() {}
 // Every name is a single validated fixed basename. No arbitrary absolute path API.
 public static native int openDirectory(String privatePath, int expectedUid);
 public static native int makeDirectory(int parent, String name, int expectedUid);
 public static native int createFile(int parent, String name, byte[] data, int expectedUid);
 public static native int createAsset(int parent, String name, byte[] data, int expectedUid);
 public static native byte[] readFile(int parent, String name, int expectedUid, int cap);
 public static native void publish(int parent, String temporary, String target, int expectedUid);
 public static native long[] identity(int fd, int expectedUid, boolean directory);
 public static native void remove(int parent, String name, int expectedUid);
 public static native void negative(int parent, int operation, int expectedUid);
 public static native void removeDirectory(int parent, String name, int expectedUid);
 public static native int openChildDirectory(int parent, String name, int expectedUid);
 public static native void executableProducer(int parent, int expectedUid);
 public static native int removeFixed(int parent, String name, int expectedUid);
 public static native int openChildOrMissing(int parent, String name, int expectedUid);
 public static native void close(int fd);
}

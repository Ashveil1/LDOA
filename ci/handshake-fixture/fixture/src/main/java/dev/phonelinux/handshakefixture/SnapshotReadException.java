package dev.phonelinux.handshakefixture;
/** JNI fixed reason only, never raw errno/path text. */
public final class SnapshotReadException extends RuntimeException {
 private static final long serialVersionUID=1L;
 public SnapshotReadException(String reason){super(reason);}
 public String reason(){String s=getMessage();return s.equals("NOT_PUBLISHED")||s.equals("CAP_EXCEEDED")||s.equals("REJECTED")?s:"UNAVAILABLE";}
}

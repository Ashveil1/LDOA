package dev.phonelinux.proto

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Strict lifecycle for DEBUG-gated diagnostic opt-in flag files
 *  (.ldoa-*). Shared by the product readers (XServer, GuestUbuntu) and the
 *  instrumentation test so every side applies IDENTICAL discipline:
 *  - read: ABSENT (NoSuchFileException) is "not set"; a non-regular or
 *    symlinked path, and any access error, is NEVER silently "absent"
 *    (Files.exists conflates absence with unknown). The content read is
 *    opened with NOFOLLOW too, so a stat-then-swap raced symlink cannot
 *    turn a "regular" stat into a link-following read.
 *  - write/delete: every option is explicit (WRITE, CREATE_NEW, NOFOLLOW);
 *    the CREATE/TRUNCATE defaults that apply only to an EMPTY option list
 *    are never relied on.
 *  - INVOCATION-LEVEL OWNERSHIP: only a mutation THIS invocation provably
 *    performed (Mutation.CREATED / Mutation.DELETED, recorded by the
 *    caller at the successful mutation) is undone at restore;
 *    Mutation.NONE touches nothing - a file that merely APPEARED with
 *    bytes equal to ours (a racer, another writer) is never deleted,
 *    because same bytes is not identity.
 *  - Byte guard limit, acknowledged: a replace-with-identical-bytes swap
 *    is indistinguishable from the original by content; fileKey/inode
 *    tracking is NOT implemented, so the guard cannot promise every
 *    replaced file is preserved. This is a documented race limit, not a
 *    correctness claim.
 *  Pure java.nio.file (no Android deps) so the JVM unit fixture exercises
 *  this exact code. */
internal object LdoaFlagLifecycle {

    /** What this invocation provably did to the flag, recorded by the
     *  caller immediately after the successful mutation. */
    enum class Mutation { NONE, CREATED, DELETED }

    enum class RestoreOutcome { RESTORED, DELETED, NOTHING_TO_DO, PRESERVED_FOREIGN }

    /** Fail-closed mode check BEFORE launch: the EFFECTIVE flag state must
     *  equal the requested mode. A preserved-foreign flag (OFF could not
     *  remove it) or a racer-created flag (OFF, was absent at save time)
     *  would otherwise launch WITH logging while OFF was requested - any
     *  disagreement throws instead of launching in the wrong mode. */
    fun requireMode(path: Path, requestedOn: Boolean) {
        val effective = isSet(path)
        if (effective != requestedOn)
            throw IllegalStateException("diagnostic flag state (present=" + effective +
                ") disagrees with requested mode (on=" + requestedOn +
                ") - refusing to proceed in the wrong mode (fail closed)")
    }

    /** Content of the flag if it is a regular NON-SYMLINK file; null if
     *  ABSENT (NoSuchFileException). A non-regular/symlinked path throws
     *  IllegalStateException; any other access failure (e.g. denied
     *  parent) propagates - unknown is never "absent". The stat AND the
     *  content read are both NOFOLLOW (open NOFOLLOW channel, not a
     *  stat-then-follow race). */
    fun readIfRegular(path: Path): ByteArray? {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            return null
        }
        if (!attrs.isRegularFile)
            throw IllegalStateException(path.toString() + " exists but is not a regular " +
                "non-symlink file - refusing to manage an unexpected type (fail closed)")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { return it.readBytes() }
    }

    fun isSet(path: Path): Boolean = readIfRegular(path) != null

    /** Create the flag with exact bytes; explicit options. CREATE_NEW:
     *  any existing name (file OR symlink) fails loudly, never truncates. */
    fun createNew(path: Path, bytes: ByteArray) {
        Files.write(path, bytes,
            StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
    }

    /** Delete only while the current bytes still equal `expected`
     *  (NOFOLLOW re-read). Returns true only when THIS call actually
     *  deleted; a content mismatch preserves the file and returns false. */
    fun deleteIfUnchanged(path: Path, expected: ByteArray): Boolean {
        val current = readIfRegular(path) ?: return false
        if (!current.contentEquals(expected)) return false
        Files.delete(path)
        return true
    }

    /** Undo ONLY what `mutation` proves this invocation did:
     *  - NONE: touch nothing (a same-bytes lookalike is never deleted).
     *  - CREATED: delete only while the current bytes still equal
     *    `createdBytes`; a content change preserves the file (byte guard;
     *    identical-bytes replacement is an acknowledged race limit).
     *  - DELETED: recreate the exact saved bytes with CREATE_NEW - into a
     *    still-absent path ONLY; anything that appeared since fails loudly
     *    (no CREATE+TRUNCATE overwrite of a newcomer). */
    fun guardedRestore(path: Path, saved: ByteArray?, mutation: Mutation,
                       createdBytes: ByteArray = byteArrayOf(0x31)): RestoreOutcome {
        return when (mutation) {
            Mutation.NONE -> RestoreOutcome.NOTHING_TO_DO
            Mutation.CREATED -> {
                val current = readIfRegular(path) ?: return RestoreOutcome.NOTHING_TO_DO
                if (!current.contentEquals(createdBytes)) return RestoreOutcome.PRESERVED_FOREIGN
                Files.delete(path)
                RestoreOutcome.DELETED
            }
            Mutation.DELETED -> {
                createNew(path, requireNotNull(saved) { "DELETED mutation implies saved bytes" })
                RestoreOutcome.RESTORED
            }
        }
    }
}

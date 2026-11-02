package dev.phonelinux.proto

/** Delivery-evidence helper for the virgl GNOME session qualification:
 *  reports one driver-selection env var from an ALREADY-READ,
 *  identity-revalidated /proc/<pid>/environ split as enum-token-or-OTHER.
 *  Pure stdlib (no Android imports) so the enum cases are directly
 *  fixture-verifiable on a JVM.
 *
 *  Accepted tokens mirror the consumers' own matching (mutter 46.2):
 *  COGL_DRIVER accepts gl3/gles2/nop via g_ascii_strcasecmp
 *  (cogl-renderer.c:91-112,:336); CLUTTER_DRIVER accepts gl3/gles2/any
 *  plus "*" via g_str_equal (clutter-backend.c:204-211,:247). */
object EnvTokenEvidence {
    val COGL_DRIVER_TOKENS = setOf("gl3", "gles2", "nop")
    val CLUTTER_DRIVER_TOKENS = setOf("gl3", "gles2", "any", "*")

    /** Never returns the raw value: a known enum token is reported as-is,
     *  anything else becomes OTHER(non-enum); ABSENT when unset;
     *  UNKNOWN(read-failed) when the environ read failed (same wording as
     *  the scan's GALLIUM_DRIVER failure state). [caseInsensitive]
     *  mirrors the consumer's matching rule. */
    fun envEnumToken(env: List<String>?, key: String, tokens: Set<String>, caseInsensitive: Boolean): String {
        if (env == null) return "UNKNOWN(read-failed)"
        val prefix = key + "="
        val raw = env.firstOrNull { it.startsWith(prefix) }?.substring(prefix.length) ?: return "ABSENT"
        return if (tokens.any { it.equals(raw, ignoreCase = caseInsensitive) }) raw else "OTHER(non-enum)"
    }
}

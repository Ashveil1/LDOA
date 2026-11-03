package dev.phonelinux.proto

internal object XdiagInventoryEvidence {
    /** Original failure wins. Each write is attempted, in order, independently. */
    fun finish(primary: Throwable?, writeRaw: () -> Unit, writeMetadata: () -> Unit) {
        var failure = primary
        for (write in listOf(writeRaw, writeMetadata)) {
            try { write() } catch (t: Throwable) {
                if (failure == null) failure = t
                else if (failure !== t) failure.addSuppressed(t)
            }
        }
        failure?.let { throw it }
    }
}

package dev.phonelinux.proto

/** Disposable-emulator predicate for test-only seams - pure function so
 *  unit tests exercise the real logic; the same shape as the
 *  instrumentation setUp CI gate (runner opt-in AND build indicators). */
internal object XdiagEmulatorGate {
    fun looksDisposableEmulator(fingerprint: String?, model: String?, product: String?): Boolean =
        listOfNotNull(fingerprint, model, product).any {
            it.contains("sdk", true) || it.contains("emulator", true) || it.contains("generic", true)
        }
}

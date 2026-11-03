package dev.phonelinux.proto

/** Diagnostic inventory, not provisioning or a library-availability gate.
 * Query with a literal * (no shell) so not-installed database entries
 * are included too, and an absent optional name never changes dpkg's rc.
 * xdiagGuest still rejects every command/transport failure before parsing. */
internal object XdiagPackageInventory {
    const val FORMAT = "\u0024{Package}\t\u0024{Architecture}\t\u0024{Status}\t\u0024{Version}\n"
    fun queryArgs(): Array<String> = arrayOf("dpkg-query", "-W", "-f=" + FORMAT, "*")
    val required = listOf("jwm", "pcmanfm")
    // Preserve the old literals as inventory, not aliases. Noble candidates
    // are source-grounded in pcmanfm/libfm metadata; no version is inferred.
    val optional = listOf("libgtk-3-0", "libfm-gtk4", "libfm4", "librandr2", "libx11-6",
        "libgtk-3-0t64", "libfm-gtk3-4t64", "libfm4t64", "libxrandr2")
    private data class Row(val name: String, val arch: String, val status: String, val version: String) {
        val installed: Boolean get() = status.split(' ').let { it[1] == "ok" && it[2] == "installed" }
    }

    fun report(raw: String): String {
        require(raw.endsWith('\n')) { "dpkg inventory missing final newline - UNKNOWN" }
        val rows = raw.dropLast(1).split('\n').map { line ->
            val f = line.split('\t')
            require(f.size == 4) { "dpkg inventory malformed row - UNKNOWN" }
            val (name, arch, status, version) = f
            require(name.matches(Regex("[a-z0-9][a-z0-9+.-]+"))) { "invalid package name - UNKNOWN" }
            require(arch.isEmpty() || arch.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "invalid architecture - UNKNOWN" }
            require(status.matches(Regex("(unknown|install|hold|deinstall|purge) (ok|reinstreq) " +
                "(not-installed|config-files|half-installed|unpacked|half-configured|triggers-awaited|triggers-pending|installed)"))) {
                "invalid dpkg status - UNKNOWN"
            }
            require(version.none { it.isWhitespace() || it.isISOControl() }) { "invalid version - UNKNOWN" }
            Row(name, arch, status, version).also {
                require(!it.installed || (arch.isNotEmpty() && version.isNotEmpty())) {
                    "installed package without architecture/version - UNKNOWN"
                }
            }
        }
        require(rows.map { it.name to it.arch }.distinct().size == rows.size) { "duplicate package/architecture - UNKNOWN" }
        val byName = rows.groupBy { it.name }
        val missing = required.filter { n -> byName[n].orEmpty().none { it.installed } }
        val text = buildString {
            append("scope\tpackage\tarchitecture\tstatus\tversion\n")
            for (name in required + optional) {
                val scope = if (name in required) "required" else "optional"
                val matches = byName[name].orEmpty().sortedBy { it.arch }
                if (matches.isEmpty()) append("$scope\t$name\t-\tABSENT_FROM_DATABASE\t-\n")
                else matches.forEach { append("$scope\t${it.name}\t${it.arch.ifEmpty { "-" }}\t${it.status}\t${it.version.ifEmpty { "-" }}\n") }
            }
            append("required_installed: ${missing.isEmpty()}\n")
            if (missing.isNotEmpty()) append("required_missing_or_not_installed: ${missing.joinToString(",")}\n")
        }
        return text
    }

    fun requireInstalled(report: String) {
        require(report.lineSequence().any { it == "required_installed: true" }) {
            "required session package(s) missing/not installed - UNKNOWN; inventory preserved"
        }
    }
}

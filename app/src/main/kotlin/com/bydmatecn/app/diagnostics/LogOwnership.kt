package com.bydmatecn.app.diagnostics

/** logcat -v time contains a PID even when READ_LOGS exposes other applications. */
internal class LogOwnership(private val appPid: Int, private val packageName: String) {
    private val helperPids = mutableSetOf<Int>()
    private val record = Regex("""^[0-9-]+ [0-9:.]+ [VDIWEF]/([^ (]+)\(\s*(\d+)\):""")
    fun keep(line: String): Boolean {
        val match = record.find(line) ?: return line.startsWith("--------- beginning")
        val tag = match.groupValues[1]
        val pid = match.groupValues[2].toIntOrNull() ?: return false
        if (tag == "bydmatecn_helper") helperPids.add(pid)
        if (pid == appPid || pid in helperPids) return true
        // Keep only explicitly scoped OS grant/process failures, never another app's tags.
        return tag in setOf("ActivityManager", "AccessibilityManagerService") &&
            Regex("""\b${Regex.escape(packageName)}(?:\b|:)""").containsMatchIn(line)
    }
}

package com.konnisan.dewuauto.automation

class FinalConfirmationGuard {
    private var runId: String = ""
    private val attemptedTaskSignatures = LinkedHashSet<String>()

    fun reset(newRunId: String) {
        runId = newRunId
        attemptedTaskSignatures.clear()
    }

    fun tryAcquire(expectedRunId: String, taskSignature: String): Boolean {
        if (expectedRunId != runId || taskSignature.isBlank()) return false
        return attemptedTaskSignatures.add(taskSignature)
    }

    fun hasAttempted(taskSignature: String): Boolean = taskSignature in attemptedTaskSignatures

    fun attemptCount(): Int = attemptedTaskSignatures.size
}

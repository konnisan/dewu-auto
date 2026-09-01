package com.konnisan.dewuauto.automation

class SingleEnrollmentGate {
    private var runId: String = ""
    private var tokenTaskSignature: String? = null
    private var tokenExpiresAt: Long = 0L

    var finalConfirmationUsed: Boolean = false
        private set

    fun reset(newRunId: String) {
        runId = newRunId
        tokenTaskSignature = null
        tokenExpiresAt = 0L
        finalConfirmationUsed = false
    }

    fun grant(
        expectedRunId: String,
        taskSignature: String,
        nowMs: Long,
        ttlMs: Long = 60_000L,
    ): Boolean {
        if (finalConfirmationUsed || expectedRunId != runId || taskSignature.isBlank()) return false
        tokenTaskSignature = taskSignature
        tokenExpiresAt = nowMs + ttlMs.coerceAtLeast(1L)
        return true
    }

    fun consume(expectedRunId: String, taskSignature: String, nowMs: Long): Boolean {
        val valid = !finalConfirmationUsed &&
            expectedRunId == runId &&
            tokenTaskSignature == taskSignature &&
            nowMs <= tokenExpiresAt
        tokenTaskSignature = null
        tokenExpiresAt = 0L
        if (valid) finalConfirmationUsed = true
        return valid
    }

    fun expiresAt(): Long = tokenExpiresAt

    fun invalidate() {
        tokenTaskSignature = null
        tokenExpiresAt = 0L
    }
}

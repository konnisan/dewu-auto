package com.konnisan.dewuauto.automation

object TaskResultLedger {
    fun upsert(
        current: List<PreviewTaskResult>,
        update: PreviewTaskResult,
    ): List<PreviewTaskResult> {
        val index = current.indexOfFirst { it.signature == update.signature }
        if (index < 0) return current + update
        return current.toMutableList().apply { this[index] = update }
    }
}

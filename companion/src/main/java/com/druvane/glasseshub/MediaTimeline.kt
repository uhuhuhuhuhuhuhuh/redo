package com.druvane.glasseshub

object MediaTimeline {
    private val lock = Any()
    private var sourceBaseUs = Long.MIN_VALUE
    private var wallBaseMs = 0L

    fun map(presentationTimeUs: Long): Long {
        val now = System.currentTimeMillis()
        if (presentationTimeUs <= 0L) return now
        return synchronized(lock) {
            val resetNeeded =
                sourceBaseUs == Long.MIN_VALUE ||
                    presentationTimeUs < sourceBaseUs ||
                    presentationTimeUs - sourceBaseUs > MAX_REASONABLE_SESSION_US
            if (resetNeeded) {
                sourceBaseUs = presentationTimeUs
                wallBaseMs = now
            }
            wallBaseMs + (presentationTimeUs - sourceBaseUs) / 1_000L
        }
    }

    fun reset() = synchronized(lock) {
        sourceBaseUs = Long.MIN_VALUE
        wallBaseMs = 0L
    }

    private const val MAX_REASONABLE_SESSION_US = 12L * 60L * 60L * 1_000_000L
}

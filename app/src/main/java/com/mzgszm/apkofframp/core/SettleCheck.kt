package com.mzgszm.apkofframp.core

data class InstallState(val versionCode: Long, val lastUpdateTime: Long)

/**
 * Play often installs a base APK and its splits in several steps. Wait until two reads
 * [delayMs] apart agree on version and update time before touching the files.
 */
class SettleCheck(
    private val read: () -> InstallState?,
    private val sleep: suspend (Long) -> Unit,
) {
    /** Returns the settled state, or null if the app vanished or never settled within [maxRounds]. */
    suspend fun await(delayMs: Long, maxRounds: Int = 10): InstallState? {
        var previous = read() ?: return null
        repeat(maxRounds) {
            sleep(delayMs)
            val current = read() ?: return null
            if (current == previous) return current
            previous = current
        }
        return null
    }
}

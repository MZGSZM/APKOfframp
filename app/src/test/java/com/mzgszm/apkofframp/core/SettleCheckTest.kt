package com.mzgszm.apkofframp.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The settle check runs against a fake clock: sleep only advances a counter. */
class SettleCheckTest {
    private class FakeClock {
        var now = 0L
        suspend fun sleep(ms: Long) { now += ms }
    }

    /** States returned by successive reads; the last one repeats. */
    private fun reads(vararg states: InstallState?): () -> InstallState? {
        var i = 0
        return { states[minOf(i++, states.size - 1)] }
    }

    @Test fun settledInstallPassesAfterOneDelay() = runBlocking {
        val clock = FakeClock()
        val s = InstallState(10, 1000)
        val r = SettleCheck(reads(s, s), clock::sleep).await(30_000)
        assertEquals(s, r)
        assertEquals(30_000L, clock.now)
    }

    @Test fun installInProgressWaitsUntilTwoReadsAgree() = runBlocking {
        val clock = FakeClock()
        // Play writes the base, then a split, then settles.
        val r = SettleCheck(
            reads(InstallState(10, 1000), InstallState(11, 2000), InstallState(11, 2500), InstallState(11, 2500)),
            clock::sleep,
        ).await(30_000)
        assertEquals(InstallState(11, 2500), r)
        assertEquals(90_000L, clock.now)
    }

    @Test fun updateTimeAloneCountsAsChange() = runBlocking {
        val clock = FakeClock()
        val r = SettleCheck(reads(InstallState(11, 1), InstallState(11, 2), InstallState(11, 2)), clock::sleep).await(30_000)
        assertEquals(InstallState(11, 2), r)
        assertEquals(60_000L, clock.now)
    }

    @Test fun uninstalledDuringCheckGivesNull() = runBlocking {
        val clock = FakeClock()
        assertNull(SettleCheck(reads(InstallState(1, 1), null), clock::sleep).await(30_000))
        assertNull(SettleCheck(reads(null), clock::sleep).await(30_000))
    }

    @Test fun neverSettlingGivesUp() = runBlocking {
        val clock = FakeClock()
        var n = 0L
        val r = SettleCheck({ InstallState(n, n++) }, clock::sleep).await(30_000, maxRounds = 4)
        assertNull(r)
        assertEquals(120_000L, clock.now)
    }
}

package io.github.audiz.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransitionLeadEstimatorTest {

    private var now = 10_000L
    private val estimator = TransitionLeadEstimator { now }

    private fun simulateTransition(preloaded: Boolean, startupMs: Long) {
        estimator.onTransitionRequested(isNextLocal = false, isNextPreloaded = preloaded)
        now += startupMs / 2
        estimator.onPlaybackStarted()
        now += startupMs - startupMs / 2
        estimator.onAudioAdvancing()
        now += 200_000L
    }

    @Test
    fun testLocalAndNoCrossfadeLeads() {
        assertEquals(TransitionLeadEstimator.NO_CROSSFADE_LEAD_MS, estimator.leadMs(false, false, false))
        assertEquals(TransitionLeadEstimator.LOCAL_LEAD_MS, estimator.leadMs(true, true, false))
    }

    @Test
    fun testFastStartupShrinksLead() {
        val initial = estimator.leadMs(true, false, true)
        repeat(6) { simulateTransition(preloaded = true, startupMs = 700L) }
        val learned = estimator.leadMs(true, false, true)
        assertTrue(learned < initial, "Запас должен уменьшиться: $initial -> $learned")
        assertTrue(learned >= 700L + 400L, "Запас не меньше фактического запуска с маржой: $learned")
    }

    @Test
    fun testSlowStartupGrowsLeadQuickly() {
        simulateTransition(preloaded = true, startupMs = 4000L)
        val lead = estimator.leadMs(true, false, true)
        assertTrue(lead >= 4000L, "После медленного запуска запас должен сразу вырасти: $lead")
    }

    @Test
    fun testOldTrackPositionDoesNotCompleteMeasurement() {
        estimator.onTransitionRequested(isNextLocal = false, isNextPreloaded = false)
        // Позиция старого трека идёт, но новый ещё не запущен — замер не завершается
        now += 100L
        estimator.onAudioAdvancing()
        now += 3000L
        estimator.onPlaybackStarted()
        now += 500L
        estimator.onAudioAdvancing()
        val lead = estimator.leadMs(true, false, false)
        assertTrue(lead > 3600L * 1.3, "Учтено полное время запуска 3.6 с, а не 0.1 с: $lead")
    }
}

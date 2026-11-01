package io.github.audiz.player

/**
 * ⏱ Запас времени на запуск следующего трека перед кроссфейдом.
 *
 * Автопереход запускается за `кроссфейд + запас` до конца трека. Кроссфейд начинается, когда
 * следующий трек реально заиграл, а после его окончания хвост текущего трека отбрасывается.
 * Поэтому слишком большой запас обрезает конец трека, а слишком маленький оставляет паузу.
 *
 * Оценщик замеряет фактическое время от запроса автоперехода до момента, когда позиция
 * нового трека пошла вперёд (отдельно для предзагруженных и непредзагруженных треков),
 * и подстраивает запас под него. Сигнал «плеер запущен» для этого не годится: на ПК он
 * приходит до того, как декодер выдал первый звук.
 */
internal class TransitionLeadEstimator(
    private val nowMs: () -> Long
) {
    companion object {
        const val LOCAL_LEAD_MS = 500L
        const val NO_CROSSFADE_LEAD_MS = 800L
        const val MIN_STREAM_LEAD_MS = 800L
        const val MAX_STREAM_LEAD_MS = 6500L

        private const val INITIAL_PRELOADED_STARTUP_MS = 2000f
        private const val INITIAL_COLD_STARTUP_MS = 4500f
        private const val SAFETY_FACTOR = 1.4f
        private const val SAFETY_MARGIN_MS = 400f
        // Рост оценки быстрый (пауза хуже обрезки), снижение — плавное
        private const val ALPHA_UP = 0.6f
        private const val ALPHA_DOWN = 0.25f
        private const val MAX_VALID_STARTUP_MS = 15_000L
    }

    private var preloadedStartupMs = INITIAL_PRELOADED_STARTUP_MS
    private var coldStartupMs = INITIAL_COLD_STARTUP_MS

    private var pendingSinceMs = 0L
    private var pendingPreloaded = false
    private var nextTrackStarted = false

    /**
     * Запас (мс) перед кроссфейдом для следующего трека.
     */
    fun leadMs(hasCrossfade: Boolean, isNextLocal: Boolean, isNextPreloaded: Boolean): Long {
        if (!hasCrossfade) return NO_CROSSFADE_LEAD_MS
        if (isNextLocal) return LOCAL_LEAD_MS
        val startup = if (isNextPreloaded) preloadedStartupMs else coldStartupMs
        return (startup * SAFETY_FACTOR + SAFETY_MARGIN_MS).toLong()
            .coerceIn(MIN_STREAM_LEAD_MS, MAX_STREAM_LEAD_MS)
    }

    /** Автопереход с кроссфейдом запрошен: начинаем замер времени запуска. */
    fun onTransitionRequested(isNextLocal: Boolean, isNextPreloaded: Boolean) {
        if (isNextLocal) {
            cancel()
            return
        }
        pendingSinceMs = nowMs()
        pendingPreloaded = isNextPreloaded
        nextTrackStarted = false
    }

    /** Плеер переключился на следующий трек; его позиция теперь отражает новый трек. */
    fun onPlaybackStarted() {
        if (pendingSinceMs > 0L) nextTrackStarted = true
    }

    /** Позиция нового трека пошла вперёд — звук реально зазвучал: обновляем оценку. */
    fun onAudioAdvancing() {
        val since = pendingSinceMs
        if (since <= 0L || !nextTrackStarted) return
        pendingSinceMs = 0L
        nextTrackStarted = false
        val startup = nowMs() - since
        if (startup <= 0L || startup > MAX_VALID_STARTUP_MS) return

        val measured = startup.toFloat()
        if (pendingPreloaded) {
            preloadedStartupMs = blend(preloadedStartupMs, measured)
        } else {
            coldStartupMs = blend(coldStartupMs, measured)
        }
    }

    /** Переход отменён или запущен вручную — замер не засчитывается. */
    fun cancel() {
        pendingSinceMs = 0L
        nextTrackStarted = false
    }

    private fun blend(current: Float, measured: Float): Float {
        val alpha = if (measured > current) ALPHA_UP else ALPHA_DOWN
        return current + alpha * (measured - current)
    }
}

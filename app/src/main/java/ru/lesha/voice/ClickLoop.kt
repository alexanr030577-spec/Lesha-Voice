package ru.lesha.voice

/** Serial tap scheduling. All calls and gesture callbacks must run on the main thread. */
internal class ClickLoop(
    private val scheduler: Scheduler,
    private val tap: (Float, Float, (Boolean) -> Unit) -> Unit,
) {
    interface Scheduler {
        fun nowMs(): Long
        fun postDelayed(delayMs: Long, action: () -> Unit)
        fun clear()
    }

    var active: Boolean = false
        private set
    val isRunning: Boolean get() = active && !paused

    private var paused = false
    private var gesturePending = false
    private var session = 0L
    private var scheduledTask = 0L
    private var x = 0f
    private var y = 0f
    private var intervalMs = 0L
    private var nextDueMs = 0L

    fun start(x: Float, y: Float, intervalMs: Long, initialDelayMs: Long = 3_000L) {
        require(intervalMs > 0)
        require(initialDelayMs >= 0)
        stop()
        this.x = x
        this.y = y
        this.intervalMs = intervalMs
        active = true
        nextDueMs = scheduler.nowMs() + initialDelayMs
        scheduleNext()
    }

    fun pause() {
        if (!active || paused) return
        paused = true
        clearScheduled()
    }

    fun resume() {
        if (!active || !paused) return
        paused = false
        scheduleNext()
    }

    fun stop() {
        session++
        active = false
        paused = false
        gesturePending = false
        clearScheduled()
    }

    private fun clearScheduled() {
        scheduledTask++
        scheduler.clear()
    }

    private fun scheduleNext() {
        if (!isRunning || gesturePending) return
        val expectedSession = session
        val expectedTask = ++scheduledTask
        scheduler.postDelayed((nextDueMs - scheduler.nowMs()).coerceAtLeast(0L)) {
            if (session != expectedSession || scheduledTask != expectedTask ||
                !isRunning || gesturePending
            ) return@postDelayed
            scheduledTask++
            gesturePending = true
            var delivered = false
            tap(x, y) { completed ->
                if (session == expectedSession && !delivered) {
                    delivered = true
                    gesturePending = false
                    if (completed) {
                        nextDueMs = scheduler.nowMs() + intervalMs
                        scheduleNext()
                    } else {
                        stop()
                    }
                }
            }
        }
    }
}

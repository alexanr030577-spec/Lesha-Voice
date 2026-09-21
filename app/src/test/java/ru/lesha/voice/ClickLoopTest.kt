package ru.lesha.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickLoopTest {
    @Test fun waitsForStartupAndGestureCompletionBeforeNextInterval() {
        val f = Fixture()
        f.loop.start(12f, 34f, 100L)
        f.scheduler.advanceBy(2_999L)
        assertEquals(0, f.taps.size)
        f.scheduler.advanceBy(1L)
        assertEquals(1, f.taps.size)
        assertEquals(12f, f.taps[0].x, 0f)
        assertEquals(34f, f.taps[0].y, 0f)
        f.scheduler.advanceBy(10_000L)
        assertEquals(1, f.taps.size)
        f.taps[0].complete(true)
        f.scheduler.advanceBy(99L)
        assertEquals(1, f.taps.size)
        f.scheduler.advanceBy(1L)
        assertEquals(2, f.taps.size)
    }

    @Test fun stopCancelsStartupEvenIfCancelledRunnableArrivesLate() {
        val f = Fixture()
        f.loop.start(1f, 2f, 100L)
        val oldStartup = f.scheduler.tasks.single().action
        f.loop.stop()
        f.scheduler.advanceBy(10_000L)
        oldStartup()
        assertEquals(0, f.taps.size)
        assertFalse(f.loop.active)
        assertFalse(f.loop.isRunning)
    }

    @Test fun stoppedGestureCannotScheduleAnotherTap() {
        val f = Fixture()
        f.startImmediately()
        f.loop.stop()
        f.taps[0].complete(true)
        f.scheduler.advanceBy(10_000L)
        assertEquals(1, f.taps.size)
        assertFalse(f.loop.active)
    }

    @Test fun oldGestureCallbackCannotAffectRestartedSession() {
        val f = Fixture()
        f.startImmediately()
        f.loop.stop()
        f.startImmediately()
        f.taps[0].complete(true)
        f.taps[0].complete(false)
        f.scheduler.advanceBy(1_000L)
        assertEquals(2, f.taps.size)
        assertTrue(f.loop.active)
        f.taps[1].complete(true)
        f.scheduler.advanceBy(100L)
        assertEquals(3, f.taps.size)
    }

    @Test fun pausedStartupRetainsItsOriginalDeadlineAndInvalidatesOldRunnable() {
        val f = Fixture()
        f.loop.start(1f, 2f, 100L)
        val oldStartup = f.scheduler.tasks.single().action
        f.scheduler.advanceBy(1_000L)
        f.loop.pause()
        assertTrue(f.loop.active)
        assertFalse(f.loop.isRunning)
        f.scheduler.advanceBy(1_000L)
        f.loop.resume()
        oldStartup()
        assertEquals(0, f.taps.size)
        f.scheduler.advanceBy(999L)
        assertEquals(0, f.taps.size)
        f.scheduler.advanceBy(1L)
        assertEquals(1, f.taps.size)
    }

    @Test fun pauseAndResumeDuringGestureWaitsForItsCompletion() {
        val f = Fixture()
        f.startImmediately()
        f.loop.pause()
        f.loop.resume()
        f.loop.resume()
        f.scheduler.advanceBy(1_000L)
        assertEquals(1, f.taps.size)
        f.taps[0].complete(true)
        f.scheduler.advanceBy(100L)
        assertEquals(2, f.taps.size)
    }

    @Test fun gestureCompletedWhilePausedWaitsUntilResume() {
        val f = Fixture()
        f.startImmediately()
        f.loop.pause()
        f.taps[0].complete(true)
        f.scheduler.advanceBy(1_000L)
        assertEquals(1, f.taps.size)
        f.loop.resume()
        f.scheduler.advanceBy(0L)
        assertEquals(2, f.taps.size)
    }

    @Test fun falseGestureResultStopsLoop() {
        val f = Fixture()
        f.startImmediately()
        f.taps[0].complete(false)
        f.scheduler.advanceBy(1_000L)
        assertFalse(f.loop.active)
        assertEquals(1, f.taps.size)
    }

    @Test fun duplicateGestureCallbackCannotScheduleParallelTap() {
        val f = Fixture()
        f.startImmediately()
        f.taps[0].complete(true)
        f.taps[0].complete(true)
        f.scheduler.advanceBy(100L)
        assertEquals(2, f.taps.size)
        f.taps[0].complete(false)
        assertTrue(f.loop.active)
    }

    @Test fun resumeAfterStopCannotRestartLoop() {
        val f = Fixture()
        f.loop.start(1f, 2f, 100L)
        f.loop.pause()
        f.loop.stop()
        f.loop.resume()
        f.scheduler.advanceBy(10_000L)
        assertEquals(0, f.taps.size)
        assertFalse(f.loop.active)
    }

    private class Fixture {
        val scheduler = FakeScheduler()
        val taps = mutableListOf<Tap>()
        val loop = ClickLoop(scheduler) { x, y, complete -> taps.add(Tap(x, y, complete)) }

        fun startImmediately() {
            loop.start(1f, 2f, 100L, initialDelayMs = 0L)
            scheduler.advanceBy(0L)
        }
    }

    private data class Tap(val x: Float, val y: Float, val complete: (Boolean) -> Unit)

    private class FakeScheduler : ClickLoop.Scheduler {
        data class Task(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        private var now = 0L

        override fun nowMs() = now

        override fun postDelayed(delayMs: Long, action: () -> Unit) {
            tasks.add(Task(now + delayMs, action))
        }

        override fun clear() {
            tasks.forEach { it.cancelled = true }
        }

        fun advanceBy(deltaMs: Long) {
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= target }.minByOrNull { it.due }
                    ?: break
                next.cancelled = true
                now = next.due
                next.action()
            }
            now = target
        }
    }
}

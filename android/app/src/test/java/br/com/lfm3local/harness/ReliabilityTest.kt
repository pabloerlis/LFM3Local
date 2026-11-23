package br.com.lfm3local.harness

import com.arm.aichat.ContextWindowFullException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReliabilityTest {
    @Test fun contextFullTriggersExactlyOneRebuild() = runBlocking {
        var attempts = 0
        var rebuilds = 0
        val value = retryOnceWhenContextFull(
            rebuild = { rebuilds++ },
            operation = {
                attempts++
                if (attempts == 1) throw ContextWindowFullException()
                "ok"
            }
        )
        assertEquals("ok", value)
        assertEquals(2, attempts)
        assertEquals(1, rebuilds)
    }

    @Test fun secondContextFullIsPropagatedWithoutLoop() = runBlocking {
        var tries = 0
        var rebuilds = 0
        try {
            retryOnceWhenContextFull(
                rebuild = { rebuilds++ },
                operation = { tries++; throw ContextWindowFullException() }
            )
            fail("retry must fail after the second attempt")
        } catch (_: ContextWindowFullException) { }
        assertEquals(2, tries)
        assertEquals(1, rebuilds)
    }

    @Test fun unrelatedErrorsDoNotTriggerRebuild() = runBlocking {
        var rebuilds = 0
        try {
            retryOnceWhenContextFull(
                rebuild = { rebuilds++ },
                operation = { throw IllegalArgumentException("invalid input") }
            )
            fail("must propagate")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, rebuilds)
    }

    @Test fun profilerUsesMonotonicTimeAndHonestUnits() {
        var tick = 1000L
        val profiler = TurnProfiler { tick }
        tick = 1700
        var measurement = profiler.textVisible("")
        assertNull(measurement.firstVisibleMs)
        tick = 2200
        measurement = profiler.textVisible("Olá")
        assertEquals(1200L, measurement.firstVisibleMs)
        tick = 3200
        measurement = profiler.textVisible("Olá mundo")
        assertEquals(0, measurement.toolCalls)
        assertEquals(9, measurement.visibleCharacters)
        assertEquals(9.0, measurement.charactersPerSecond, 0.01)
        profiler.toolAttempt()
        assertEquals(1, profiler.snapshot().toolCalls)
    }
}

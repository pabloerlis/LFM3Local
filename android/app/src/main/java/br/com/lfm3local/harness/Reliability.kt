package br.com.lfm3local.harness

import com.arm.aichat.ContextWindowFullException

/** Rebuild the KV cache once, only when native prefill rejected the prompt. */
suspend fun <T> retryOnceWhenContextFull(
    rebuild: suspend () -> Unit,
    operation: suspend () -> T
): T = try {
    operation()
} catch (e: ContextWindowFullException) {
    rebuild()
    operation()
}

data class TurnStats(
    val elapsedMs: Long,
    val firstVisibleMs: Long?,
    val visibleCharacters: Int,
    val charactersPerSecond: Double,
    val toolCalls: Int
) {
    fun shortText(): String {
        val first = firstVisibleMs?.let { it.toString() + "ms" } ?: "—"
        val rate = "%.0f".format(java.util.Locale.US, charactersPerSecond)
        return "1º texto: " + first + " · " + rate + " caracteres/s · " +
            toolCalls + " ferramenta(s)"
    }
}

/** Monotonic timing. Visible character speed is NOT model tokens/s. */
class TurnProfiler(private val nowMillis: () -> Long) {
    private val started = nowMillis()
    private var first: Long? = null
    private var lastCount = 0
    private var toolCount = 0

    fun textVisible(text: String): TurnStats {
        val elapsed = (nowMillis() - started).coerceAtLeast(0)
        if (text.isNotBlank() && first == null) first = elapsed
        lastCount = text.length
        return snapshot()
    }

    fun toolAttempt(): TurnStats {
        toolCount++
        return snapshot()
    }

    fun snapshot(): TurnStats {
        val elapsed = (nowMillis() - started).coerceAtLeast(0)
        val activeMs = (elapsed - (first ?: elapsed)).coerceAtLeast(0)
        val rate = if (first == null || activeMs == 0L) 0.0
            else lastCount.toDouble() * 1000.0 / activeMs
        return TurnStats(elapsed, first, lastCount, rate, toolCount)
    }
}

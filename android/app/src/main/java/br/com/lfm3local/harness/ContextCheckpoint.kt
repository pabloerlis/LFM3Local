package br.com.lfm3local.harness

import java.security.MessageDigest

/** Neutral, testable representation of a persisted chat message. */
data class ContextTurn(val id: Long, val role: String, val body: String)

/**
 * Snapshot identifiers are keyed by both content and tool/system configuration,
 * so a cached KV state cannot accidentally be reused after an edit or skill change.
 */
object ContextCheckpoint {
    private fun digest(chunks: List<String>): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        chunks.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update((bytes.size ushr 24).toByte())
            hash.update((bytes.size ushr 16).toByte())
            hash.update((bytes.size ushr 8).toByte())
            hash.update(bytes.size.toByte())
            hash.update(bytes)
        }
        return hash.digest()
    }

    fun revision(messages: List<ContextTurn>): Long {
        val chunks = buildList {
            add(messages.size.toString())
            for (message in messages) {
                add(message.id.toString())
                add(message.role)
                add(message.body)
            }
        }
        val bytes = digest(chunks)
        var value = 0L
        for (i in 0..7) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        return value
    }

    fun configurationKey(systemPrompt: String, nativeMode: Boolean): String {
        val bytes = digest(listOf("LFM3Local/KV/v1", systemPrompt, nativeMode.toString()))
        val digits = "0123456789abcdef"
        return buildString(64) {
            bytes.forEach {
                val b = it.toInt() and 0xFF
                append(digits[b ushr 4])
                append(digits[b and 0x0F])
            }
        }
    }
}

/**
 * Pure decision unit for the fast path, kept outside the Android activity.
 * A hit is used only when the revision and configuration match exactly.
 */
data class SnapshotDescriptor(
    val chatId: Long,
    val revision: Long,
    val configurationKey: String
) {
    fun matches(chatId: Long, revision: Long, configurationKey: String) =
        this.chatId == chatId && this.revision == revision && this.configurationKey == configurationKey
}

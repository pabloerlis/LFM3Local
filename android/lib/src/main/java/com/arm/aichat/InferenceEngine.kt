package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String)

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /** Registers allowlisted JSON schemas using the GGUF Jinja template.
     * Returns false for older/incompatible chat templates (compatibility mode). */
    suspend fun configureToolSchemas(tools: List<NativeToolSchema>): Boolean

    /** Save up to two recent chats as bounded RAM KV checkpoints.
     * Can return false under memory pressure; normal SQLite replay remains available. */
    suspend fun cacheConversation(chatId: Long, revision: Long, configurationKey: String): Boolean

    /** Fast path for reopening a previous chat, if key and revision match.
     * Returns false on a miss; caller then restores from the durable SQLite journal. */
    suspend fun restoreCachedConversation(chatId: Long, revision: Long, configurationKey: String): Boolean

    /** Restore previous role-separated turns without reloading the model. */
    suspend fun restoreConversation(systemPrompt: String, roles: List<String>, contents: List<String>)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /** Continue a completed assistant tool call with a real tool-role message. */
    fun sendToolResult(toolName: String, result: String, predictLength: Int = 512): Flow<String>

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()

/** The model is still loaded; callers may restore recent turns and retry once. */
class ContextWindowFullException : Exception("A janela de contexto do modelo ficou cheia")

/** Code-defined tool description; model/user text never creates new native functions. */
data class NativeToolSchema(val name: String, val description: String, val parameters: String)

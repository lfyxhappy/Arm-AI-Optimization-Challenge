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

    /** Configure the next model context. Changes take effect on the next load. */
    suspend fun setRuntimeConfig(config: RuntimeConfig)

    /** Build and backend details used in an exported benchmark artifact. */
    fun runtimeInfo(): RuntimeInfo

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /** Request that the active token stream stops after its current native call. */
    fun stopGeneration()

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

/**
 * Runtime choice inside the backend profile compiled into this APK.
 *
 * [ACCELERATOR] never enables a backend that is absent from the APK: it leaves
 * a visible CPU fallback reason instead. This makes CPU-versus-accelerator
 * comparison safe on a single profile build.
 */
enum class BackendPreference(val nativeValue: Int, val uiLabel: String) {
    AUTO(0, "Auto (compiled profile)"),
    CPU(1, "CPU baseline"),
    ACCELERATOR(2, "Accelerator (compiled profile)"),
}

/** Requested Flash Attention policy for the next native context. */
enum class FlashAttentionMode(val nativeValue: Int, val uiLabel: String) {
    AUTO(-1, "Flash attention: Auto"),
    ENABLED(1, "Flash attention: On"),
    DISABLED(0, "Flash attention: Off"),
}

/** A shared K/V cache precision that llama.cpp supports for this experiment. */
enum class KvCacheType(val nativeValue: Int, val uiLabel: String) {
    F16(0, "KV cache: F16"),
    Q8_0(1, "KV cache: Q8_0"),
    Q4_0(2, "KV cache: Q4_0"),
}

/**
 * Context construction inputs. Keeping this immutable lets every benchmark
 * record identify the exact configuration used to build its fresh context.
 */
data class RuntimeConfig(
    val threads: Int = 4,
    val temperature: Float = 0.3f,
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val flashAttention: FlashAttentionMode = FlashAttentionMode.AUTO,
    val kvCacheType: KvCacheType = KvCacheType.F16,
    val batchSize: Int = 512,
    val ubatchSize: Int = 512,
) {
    init {
        require(threads in 1..8) { "Thread count must be 1..8" }
        require(temperature in 0f..2f) { "Temperature must be 0.0..2.0" }
        require(batchSize in 16..1024) { "Batch size must be 16..1024" }
        require(ubatchSize in 16..batchSize) { "uBatch size must be 16..batch size" }
        require(kvCacheType == KvCacheType.F16 || flashAttention != FlashAttentionMode.DISABLED) {
            "Quantized KV cache requires Flash Attention to be Auto or On"
        }
    }
}

/** Native timings for the most recently completed load/prompt/generation flow. */
data class RuntimeTiming(
    val modelLoadMs: Double,
    val contextInitMs: Double,
    val systemPrefillMs: Double,
    val promptPrefillMs: Double,
    val nativeDecodeMs: Double,
)

data class RuntimeInfo(
    val configuredThreads: Int,
    val temperature: Float,
    val backend: String,
    val systemInfo: String,
    val peakMemoryKb: Long,
    val backendProfile: String,
    val backendPreference: BackendPreference,
    val requestedDevice: String,
    val activeDevice: String,
    val registeredBackends: String,
    val registeredDevices: String,
    val layerOffload: String,
    val fallbackReason: String?,
    val batchSize: Int,
    val ubatchSize: Int,
    val flashAttention: FlashAttentionMode,
    val kvCacheType: KvCacheType,
    val timing: RuntimeTiming,
)

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

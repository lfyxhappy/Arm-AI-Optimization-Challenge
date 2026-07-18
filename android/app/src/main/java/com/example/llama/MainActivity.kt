package com.lfyxhappy.armai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.BackendPreference
import com.arm.aichat.FlashAttentionMode
import com.arm.aichat.InferenceEngine
import com.arm.aichat.KvCacheType
import com.arm.aichat.RuntimeConfig
import com.arm.aichat.RuntimeInfo
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {
    private lateinit var engine: InferenceEngine
    private lateinit var status: TextView
    private lateinit var runtimeDetails: TextView
    private lateinit var modelInfo: TextView
    private lateinit var input: EditText
    private lateinit var systemPrompt: EditText
    private lateinit var temperature: EditText
    private lateinit var maxTokens: EditText
    private lateinit var threads: Spinner
    private lateinit var backendMode: Spinner
    private lateinit var flashAttentionMode: Spinner
    private lateinit var kvCacheType: Spinner
    private lateinit var batchSize: EditText
    private lateinit var ubatchSize: EditText
    private lateinit var stageName: EditText
    private lateinit var archiveStatus: TextView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var importButton: Button
    private lateinit var stagedButton: Button
    private lateinit var tuneButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private var generationJob: Job? = null
    @Volatile private var stopRequested = false
    private var currentModel: ImportedModel? = null
    private val messages = mutableListOf<Message>()
    private val interactiveMeasurements = mutableListOf<BenchmarkMeasurement>()
    private val benchmarkMeasurements = mutableListOf<BenchmarkMeasurement>()
    private var currentBenchmarkSession: BenchmarkSession? = null
    private var activeChatSession: ActiveChatSession? = null
    private var pendingExport: ExportDocument? = null

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importModel(it) }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val export = pendingExport
        pendingExport = null
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null && export != null) lifecycleScope.launch(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(export.content) }
            withContext(Dispatchers.Main) { status.text = "Exported ${export.filename}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        runtimeDetails = findViewById(R.id.runtime_details)
        modelInfo = findViewById(R.id.model_info)
        input = findViewById(R.id.user_input)
        systemPrompt = findViewById(R.id.system_prompt)
        temperature = findViewById(R.id.temperature)
        maxTokens = findViewById(R.id.max_tokens)
        threads = findViewById(R.id.threads)
        backendMode = findViewById(R.id.backend_mode)
        flashAttentionMode = findViewById(R.id.flash_attention_mode)
        kvCacheType = findViewById(R.id.kv_cache_type)
        batchSize = findViewById(R.id.batch_size)
        ubatchSize = findViewById(R.id.ubatch_size)
        stageName = findViewById(R.id.stage_name)
        archiveStatus = findViewById(R.id.archive_status)
        importButton = findViewById(R.id.import_model)
        stagedButton = findViewById(R.id.load_staged_model)
        tuneButton = findViewById(R.id.auto_tune)
        sendButton = findViewById(R.id.send)
        stopButton = findViewById(R.id.stop)
        threads.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("2", "4", "6", "8"))
        threads.setSelection(1)
        backendMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            BACKEND_PREFERENCES.map { it.uiLabel },
        )
        flashAttentionMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            FlashAttentionMode.values().map { it.uiLabel },
        )
        kvCacheType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            KvCacheType.values().map { it.uiLabel },
        )
        val recycler = findViewById<RecyclerView>(R.id.messages)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageAdapter = MessageAdapter(messages)
        recycler.adapter = messageAdapter

        importButton.setOnClickListener { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        stagedButton.setOnClickListener { loadStagedModel() }
        refreshStagedModelButton()
        sendButton.setOnClickListener { sendChat() }
        stopButton.setOnClickListener { stopActiveGeneration() }
        tuneButton.setOnClickListener { autoTune() }
        findViewById<Button>(R.id.export_json).setOnClickListener { exportCurrentSession("json") }
        findViewById<Button>(R.id.export_csv).setOnClickListener { exportCurrentSession("csv") }
        findViewById<Button>(R.id.export_report).setOnClickListener { exportCurrentSession("html") }
        findViewById<Button>(R.id.export_all_stages).setOnClickListener { exportAllStages() }
        refreshArchiveStatus()

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            while (engine.state.value !is InferenceEngine.State.Initialized) delay(100)
            val runtime = engine.runtimeInfo()
            withContext(Dispatchers.Main) {
                renderRuntimeDetails(runtime)
                status.text = "Runtime ready. Import a GGUF model."
            }
        }
    }

    private fun importModel(uri: Uri) {
        setBusy(true, "Validating GGUF metadata...")
        val config = selectedRuntimeConfig()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metadata = contentResolver.openInputStream(uri)?.use { GgufMetadataReader.create().readStructuredMetadata(it) }
                    ?: error("Unable to read the selected file")
                val imported = copyAndHash(uri, metadata)
                configureAndLoad(imported, config)
                currentModel = imported
                withContext(Dispatchers.Main) {
                    modelInfo.text = imported.describe(metadata)
                    renderRuntimeDetails(engine.runtimeInfo())
                    status.text = "Model ready: ${imported.filename}"
                    input.hint = "Ask the local model"
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { showError("Import failed", error) }
            } finally {
                withContext(Dispatchers.Main) { setBusy(false) }
            }
        }
    }

    /**
     * Debug/device-test path for OEM file pickers that hide unknown GGUF MIME
     * types. The file is copied into this debuggable app's private directory
     * with `adb shell run-as`; production users still use the SAF picker.
     */
    private fun refreshStagedModelButton() {
        stagedButton.visibility = if (stagedModelFile().isFile) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun loadStagedModel() {
        val file = stagedModelFile()
        if (!file.isFile) return
        setBusy(true, "Reading staged GGUF metadata...")
        val config = selectedRuntimeConfig()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metadata = FileInputStream(file).use { GgufMetadataReader.create().readStructuredMetadata(it) }
                val imported = ImportedModel(file, sha256(file), file.length(), metadata.basic.name ?: "staged GGUF")
                configureAndLoad(imported, config)
                currentModel = imported
                withContext(Dispatchers.Main) {
                    modelInfo.text = imported.describe(metadata)
                    renderRuntimeDetails(engine.runtimeInfo())
                    status.text = "Model ready: ${imported.filename}"
                    input.hint = "Ask the local model"
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { showError("Staged model failed", error) }
            } finally {
                withContext(Dispatchers.Main) { setBusy(false) }
            }
        }
    }

    private fun stagedModelFile() = File(filesDir, "models/staged-qwen3-4b.gguf")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun copyAndHash(uri: Uri, metadata: GgufMetadata): ImportedModel {
        val directory = File(filesDir, "models").apply { mkdirs() }
        val temporary = File(directory, "import-${UUID.randomUUID()}.gguf")
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { source ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Selected document cannot be opened")
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val baseName = metadata.filename().replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val destination = File(directory, "$baseName-${sha256.take(12)}.gguf")
        if (destination.exists()) temporary.delete() else check(temporary.renameTo(destination)) { "Cannot move imported model" }
        return ImportedModel(destination, sha256, destination.length(), metadata.basic.name ?: baseName)
    }

    private suspend fun configureAndLoad(
        model: ImportedModel,
        config: RuntimeConfig,
    ) {
        if (engine.state.value !is InferenceEngine.State.Initialized) engine.cleanUp()
        engine.setRuntimeConfig(config)
        engine.loadModel(model.file.absolutePath)
        activeChatSession = null
    }

    private suspend fun ensureInteractiveSession(
        model: ImportedModel,
        config: RuntimeConfig,
        configuredSystemPrompt: String,
    ) {
        val existing = activeChatSession
        if (existing != null &&
            existing.modelSha256 == model.sha256 &&
            existing.config == config &&
            existing.systemPrompt == configuredSystemPrompt &&
            engine.state.value is InferenceEngine.State.ModelReady
        ) return

        configureAndLoad(model, config)
        engine.setSystemPrompt(configuredSystemPrompt)
        activeChatSession = ActiveChatSession(model.sha256, config, configuredSystemPrompt)
    }

    private fun sendChat() {
        val prompt = input.text.toString().trim()
        val model = currentModel
        if (prompt.isEmpty() || model == null) return
        val config = selectedRuntimeConfig()
        val configuredSystemPrompt = systemPrompt.text.toString().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val configuredMaxTokens = selectedMaxTokens()
        stopRequested = false
        input.setText("")
        input.isEnabled = false
        messages += Message(UUID.randomUUID().toString(), prompt, true)
        messages += Message(UUID.randomUUID().toString(), "", false)
        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                ensureInteractiveSession(model, config, configuredSystemPrompt)
                val started = System.nanoTime()
                var firstTokenAt = 0L
                var tokens = 0
                val response = StringBuilder()
                engine.sendUserPrompt(prompt, configuredMaxTokens).collect { token ->
                    if (firstTokenAt == 0L) firstTokenAt = System.nanoTime()
                    tokens++
                    response.append(token)
                    withContext(Dispatchers.Main) {
                        messages[messages.lastIndex] = messages.last().copy(content = visibleAssistantText(response.toString()))
                        messageAdapter.notifyItemChanged(messages.lastIndex)
                    }
                }
                if (!stopRequested) recordInteractiveMeasurement(model, tokens, started, firstTokenAt)
            } catch (error: Exception) {
                activeChatSession = null
                withContext(Dispatchers.Main) { showError("Generation failed", error) }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    input.isEnabled = true
                    generationJob = null
                    stopRequested = false
                }
            }
        }
    }

    private fun autoTune() {
        val model = currentModel ?: run { toast("Import a GGUF model first"); return }
        val config = selectedRuntimeConfig()
        val stage = selectedStageName()
        val sessionId = UUID.randomUUID().toString()
        val sessionStartedAt = Instant.now().toString()
        val benchmarkSystemPrompt = systemPrompt.text.toString().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val benchmarkMaxTokens = selectedMaxTokens()
        val benchmarkPrompt = BENCHMARK_PROMPT
        val benchmarkInputs = BenchmarkInputs.from(benchmarkSystemPrompt, benchmarkPrompt, benchmarkMaxTokens)
        benchmarkMeasurements.clear()
        setBusy(true, "Auto tuning $stage: 1 warm-up + 5 measured runs per thread count...")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                for (threadCount in THREAD_CANDIDATES) {
                    statusOnMain("Warm-up at $threadCount threads")
                    benchmarkMeasurements += runBenchmark(
                        model, threadCount, config, benchmarkPrompt, benchmarkSystemPrompt, benchmarkMaxTokens,
                        sessionId, stage, warmup = true,
                    )
                    repeat(MEASURED_RUNS) { index ->
                        statusOnMain("Measuring $threadCount threads: ${index + 1}/$MEASURED_RUNS")
                        benchmarkMeasurements += runBenchmark(
                            model, threadCount, config, benchmarkPrompt, benchmarkSystemPrompt, benchmarkMaxTokens,
                            sessionId, stage, warmup = false,
                        )
                    }
                }
                val archive = archiveBenchmarkSession(stage, config, benchmarkInputs, sessionStartedAt, sessionId, complete = true)
                val best = BenchmarkExporter.recommended(benchmarkMeasurements)
                statusOnMain(best?.let {
                    "Archived $stage (${archive.sessionCount} stages). Recommended: ${it.threads} threads (${it.meanTokensPerSecond.format(2)} mean tokens/s)"
                } ?: "Archived $stage (${archive.sessionCount} stages). No valid measurements: device thermal status was severe")
                withContext(Dispatchers.Main) { refreshArchiveStatus() }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { showError("Auto tuning failed", error) }
            } finally {
                if (benchmarkMeasurements.isNotEmpty() && currentBenchmarkSession?.id != sessionId) {
                    runCatching {
                        archiveBenchmarkSession(stage, config, benchmarkInputs, sessionStartedAt, sessionId, complete = false)
                    }.onSuccess {
                        withContext(Dispatchers.Main) { refreshArchiveStatus() }
                    }
                }
                withContext(Dispatchers.Main) { setBusy(false) }
            }
        }
    }

    private fun archiveBenchmarkSession(
        stage: String,
        config: RuntimeConfig,
        inputs: BenchmarkInputs,
        startedAt: String,
        sessionId: String,
        complete: Boolean,
    ): BenchmarkArchiveResult {
        val session = BenchmarkArchiveStore.createSession(
            this,
            stage,
            config,
            inputs,
            benchmarkMeasurements.toList(),
            complete,
            startedAt,
            sessionId,
        )
        val archive = BenchmarkArchiveStore(this).archive(session)
        currentBenchmarkSession = session
        return archive
    }

    private suspend fun runBenchmark(
        model: ImportedModel,
        threadCount: Int,
        config: RuntimeConfig,
        prompt: String,
        benchmarkSystemPrompt: String,
        benchmarkMaxTokens: Int,
        sessionId: String,
        stage: String,
        warmup: Boolean,
    ): BenchmarkMeasurement {
        val before = DeviceTelemetry.capture(this)
        if (before.isSevere) return BenchmarkMeasurement.skipped(
            model, threadCount, config, sessionId, stage, before, warmup, "Thermal status is SEVERE or higher",
        )
        configureAndLoad(model, config.copy(threads = threadCount))
        engine.setSystemPrompt(benchmarkSystemPrompt)
        val started = System.nanoTime()
        var firstTokenAt = 0L
        var tokens = 0
        engine.sendUserPrompt(prompt, benchmarkMaxTokens).collect {
            if (firstTokenAt == 0L) firstTokenAt = System.nanoTime()
            tokens++
        }
        val ended = System.nanoTime()
        val after = DeviceTelemetry.capture(this)
        val runtime = engine.runtimeInfo()
        val validityIssue = listOfNotNull(
            if (after.isSevere) "Thermal status became SEVERE or higher" else null,
            runtimeValidityIssue(runtime),
        ).joinToString("; ").ifBlank { null }
        return BenchmarkMeasurement(
            sessionId = sessionId, stage = stage, timestamp = Instant.now().toString(), modelName = model.displayName,
            modelSha256 = model.sha256, modelBytes = model.bytes, threads = threadCount,
            temperature = config.temperature, outputTokens = tokens,
            ttftMs = if (firstTokenAt == 0L) -1.0 else (firstTokenAt - started) / 1_000_000.0,
            elapsedMs = (ended - started) / 1_000_000.0,
            tokensPerSecond = if (tokens == 0) 0.0 else tokens * 1_000_000_000.0 / (ended - started),
            peakMemoryKb = runtime.peakMemoryKb, backend = runtime.backend,
            backendProfile = runtime.backendProfile, backendPreference = runtime.backendPreference.name.lowercase(),
            requestedDevice = runtime.requestedDevice, activeDevice = runtime.activeDevice,
            registeredBackends = runtime.registeredBackends, registeredDevices = runtime.registeredDevices,
            layerOffload = runtime.layerOffload, fallbackReason = runtime.fallbackReason,
            batchSize = runtime.batchSize, ubatchSize = runtime.ubatchSize, systemInfo = runtime.systemInfo,
            flashAttention = runtime.flashAttention.name.lowercase(), kvCacheType = runtime.kvCacheType.name.lowercase(),
            modelLoadMs = runtime.timing.modelLoadMs, contextInitMs = runtime.timing.contextInitMs,
            systemPrefillMs = runtime.timing.systemPrefillMs, promptPrefillMs = runtime.timing.promptPrefillMs,
            nativeDecodeMs = runtime.timing.nativeDecodeMs,
            thermalStatus = after.thermalStatus, batteryTemperatureC = after.batteryTemperatureC,
            batteryCurrentUa = after.batteryCurrentUa, valid = validityIssue == null, warmup = warmup,
            invalidReason = validityIssue,
        )
    }

    private fun recordInteractiveMeasurement(model: ImportedModel, tokens: Int, started: Long, first: Long) {
        val ended = System.nanoTime()
        val telemetry = DeviceTelemetry.capture(this)
        val runtime = engine.runtimeInfo()
        val validityIssue = listOfNotNull(
            if (telemetry.isSevere) "Thermal status is SEVERE or higher" else null,
            runtimeValidityIssue(runtime),
        ).joinToString("; ").ifBlank { null }
        interactiveMeasurements += BenchmarkMeasurement(
            sessionId = "interactive",
            stage = "interactive",
            timestamp = Instant.now().toString(),
            modelName = model.displayName,
            modelSha256 = model.sha256,
            modelBytes = model.bytes,
            threads = runtime.configuredThreads,
            temperature = runtime.temperature,
            outputTokens = tokens,
            ttftMs = if (first == 0L) -1.0 else (first - started) / 1_000_000.0,
            elapsedMs = (ended - started) / 1_000_000.0,
            tokensPerSecond = if (tokens == 0) 0.0 else tokens * 1e9 / (ended - started),
            peakMemoryKb = runtime.peakMemoryKb,
            backend = runtime.backend,
            backendProfile = runtime.backendProfile,
            backendPreference = runtime.backendPreference.name.lowercase(),
            requestedDevice = runtime.requestedDevice,
            activeDevice = runtime.activeDevice,
            registeredBackends = runtime.registeredBackends,
            registeredDevices = runtime.registeredDevices,
            layerOffload = runtime.layerOffload,
            fallbackReason = runtime.fallbackReason,
            batchSize = runtime.batchSize,
            ubatchSize = runtime.ubatchSize,
            systemInfo = runtime.systemInfo,
            flashAttention = runtime.flashAttention.name.lowercase(),
            kvCacheType = runtime.kvCacheType.name.lowercase(),
            modelLoadMs = runtime.timing.modelLoadMs,
            contextInitMs = runtime.timing.contextInitMs,
            systemPrefillMs = runtime.timing.systemPrefillMs,
            promptPrefillMs = runtime.timing.promptPrefillMs,
            nativeDecodeMs = runtime.timing.nativeDecodeMs,
            thermalStatus = telemetry.thermalStatus,
            batteryTemperatureC = telemetry.batteryTemperatureC,
            batteryCurrentUa = telemetry.batteryCurrentUa,
            valid = validityIssue == null,
            warmup = false,
            invalidReason = validityIssue,
        )
    }

    private fun exportCurrentSession(format: String) {
        val session = currentBenchmarkSession ?: run { toast("Run auto tune before exporting"); return }
        val slug = session.stage.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "stage" }
        when (format) {
            "json" -> export("$slug-${session.id.takeLast(8)}.json", "application/json", BenchmarkExporter.json(session, session.measurements))
            "csv" -> export("$slug-${session.id.takeLast(8)}.csv", "text/csv", BenchmarkExporter.csv(session, session.measurements))
            "html" -> export("$slug-${session.id.takeLast(8)}.html", "text/html", BenchmarkExporter.report(session, session.measurements))
        }
    }

    private fun exportAllStages() {
        val history = BenchmarkArchiveStore(this).historyCsvOrNull()
            ?: run { toast("No archived benchmark stages yet"); return }
        export("all-benchmark-stages.csv", "text/csv", history)
    }

    private fun export(filename: String, mime: String, content: String) {
        pendingExport = ExportDocument(filename, content)
        exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, filename))
    }

    private fun selectedThreads() = threads.selectedItem.toString().toInt()
    private fun selectedBackendPreference() = BACKEND_PREFERENCES.getOrElse(backendMode.selectedItemPosition) { BackendPreference.AUTO }
    private fun selectedTemperature() = temperature.text.toString().toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.3f
    private fun selectedMaxTokens() = maxTokens.text.toString().toIntOrNull()?.coerceIn(1, 1024) ?: 96
    private fun selectedRuntimeConfig(): RuntimeConfig {
        val kvCache = KvCacheType.values().getOrElse(kvCacheType.selectedItemPosition) { KvCacheType.F16 }
        var flashAttention = FlashAttentionMode.values().getOrElse(flashAttentionMode.selectedItemPosition) { FlashAttentionMode.AUTO }
        if (kvCache != KvCacheType.F16 && flashAttention == FlashAttentionMode.DISABLED) {
            flashAttention = FlashAttentionMode.ENABLED
            flashAttentionMode.setSelection(FlashAttentionMode.ENABLED.ordinal)
            toast("Quantized KV cache requires Flash Attention; switched to On")
        }
        val batch = batchSize.text.toString().toIntOrNull()?.coerceIn(16, 1024) ?: DEFAULT_BATCH_SIZE
        val ubatch = ubatchSize.text.toString().toIntOrNull()?.coerceIn(16, batch) ?: batch
        return RuntimeConfig(
            threads = selectedThreads(),
            temperature = selectedTemperature(),
            backendPreference = selectedBackendPreference(),
            flashAttention = flashAttention,
            kvCacheType = kvCache,
            batchSize = batch,
            ubatchSize = ubatch,
        )
    }
    private fun selectedStageName() = stageName.text.toString().trim().take(80).ifBlank { "untitled-stage" }
    private fun refreshArchiveStatus() {
        val count = BenchmarkArchiveStore(this).sessionCount()
        archiveStatus.text = if (count == 0) "Archive: no completed benchmark stages yet" else "Archive: $count immutable benchmark stage(s) retained"
    }
    private fun visibleAssistantText(raw: String): String {
        val start = raw.indexOf("<think>")
        if (start < 0) return raw.replace("</think>", "").trimStart()
        val end = raw.indexOf("</think>", start + "<think>".length)
        if (end < 0) return ""
        return raw.removeRange(start, end + "</think>".length).trimStart()
    }
    private fun renderRuntimeDetails(runtime: RuntimeInfo) {
        runtimeDetails.text = buildString {
            append("Profile: ").append(runtime.backendProfile)
            append(" | preference: ").append(runtime.backendPreference.name.lowercase())
            append("\nRequested: ").append(runtime.requestedDevice)
            append(" | active: ").append(runtime.activeDevice)
            append(" | offload: ").append(runtime.layerOffload)
            append("\nBackends: ").append(runtime.registeredBackends)
            append("\nContext: flash=").append(runtime.flashAttention.name.lowercase())
            append(" | KV=").append(runtime.kvCacheType.name)
            append(" | batch=").append(runtime.batchSize).append('/').append(runtime.ubatchSize)
            append("\nPeak memory: ").append(runtime.peakMemoryKb).append(" KB")
            runtime.fallbackReason?.let { append("\nFallback: ").append(it) }
        }
    }
    private fun runtimeValidityIssue(runtime: RuntimeInfo): String? {
        runtime.fallbackReason?.let { return it }
        return if (runtime.requestedDevice != "CPU" && runtime.activeDevice != runtime.requestedDevice) {
            "Requested ${runtime.requestedDevice}, but active device is ${runtime.activeDevice}"
        } else null
    }
    private fun setBusy(busy: Boolean, message: String? = null) {
        importButton.isEnabled = !busy
        stagedButton.isEnabled = !busy
        tuneButton.isEnabled = !busy
        sendButton.isEnabled = !busy
        stopButton.isEnabled = !busy
        threads.isEnabled = !busy
        temperature.isEnabled = !busy
        maxTokens.isEnabled = !busy
        backendMode.isEnabled = !busy
        flashAttentionMode.isEnabled = !busy
        kvCacheType.isEnabled = !busy
        batchSize.isEnabled = !busy
        ubatchSize.isEnabled = !busy
        stageName.isEnabled = !busy
        if (message != null) status.text = message
    }
    private suspend fun statusOnMain(text: String) = withContext(Dispatchers.Main) { status.text = text }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun showError(prefix: String, error: Exception) { status.text = "$prefix: ${error.message ?: error.javaClass.simpleName}"; toast(status.text.toString()) }
    private fun stopActiveGeneration() {
        stopRequested = true
        activeChatSession = null
        if (::engine.isInitialized) engine.stopGeneration()
    }
    override fun onStop() { stopActiveGeneration(); generationJob?.cancel(); super.onStop() }
    override fun onDestroy() { if (::engine.isInitialized) engine.destroy(); super.onDestroy() }

    private data class ExportDocument(val filename: String, val content: String)
    private data class ActiveChatSession(
        val modelSha256: String,
        val config: RuntimeConfig,
        val systemPrompt: String,
    )
    data class ImportedModel(val file: File, val sha256: String, val bytes: Long, val displayName: String) {
        val filename get() = file.name
        fun describe(metadata: GgufMetadata) = "$displayName\n${(bytes / (1024.0 * 1024.0 * 1024.0)).format(2)} GiB | SHA-256 ${sha256.take(16)}...\n${metadata.basic}"
    }
    private companion object {
        val THREAD_CANDIDATES = listOf(2, 4, 6, 8)
        val BACKEND_PREFERENCES = BackendPreference.values()
        const val MEASURED_RUNS = 5
        const val DEFAULT_BATCH_SIZE = 512
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant. Reply in concise Chinese."
        const val BENCHMARK_PROMPT = "请用中文简要解释：为什么移动端大语言模型需要同时优化量化和线程数？"
    }
}

internal data class DeviceTelemetry(val thermalStatus: Int, val batteryTemperatureC: Double?, val batteryCurrentUa: Int?) {
    val isSevere get() = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    companion object {
        fun capture(context: Context): DeviceTelemetry {
            val power = context.getSystemService(PowerManager::class.java)
            val battery = context.getSystemService(BatteryManager::class.java)
            val temp = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
            val current = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeUnless { it == Int.MIN_VALUE }
            return DeviceTelemetry(power?.currentThermalStatus ?: 0, temp, current)
        }
    }
}

internal data class BenchmarkMeasurement(
    val sessionId: String, val stage: String, val timestamp: String, val modelName: String, val modelSha256: String, val modelBytes: Long, val threads: Int,
    val temperature: Float, val outputTokens: Int, val ttftMs: Double, val elapsedMs: Double, val tokensPerSecond: Double,
    val peakMemoryKb: Long, val backend: String, val backendProfile: String, val backendPreference: String,
    val requestedDevice: String, val activeDevice: String, val registeredBackends: String, val registeredDevices: String,
    val layerOffload: String, val fallbackReason: String?, val batchSize: Int, val ubatchSize: Int, val systemInfo: String,
    val flashAttention: String, val kvCacheType: String,
    val modelLoadMs: Double, val contextInitMs: Double, val systemPrefillMs: Double, val promptPrefillMs: Double, val nativeDecodeMs: Double,
    val thermalStatus: Int,
    val batteryTemperatureC: Double?, val batteryCurrentUa: Int?, val valid: Boolean, val warmup: Boolean, val invalidReason: String?,
) {
    companion object {
        fun skipped(
            model: MainActivity.ImportedModel,
            threads: Int,
            config: RuntimeConfig,
            sessionId: String,
            stage: String,
            telemetry: DeviceTelemetry,
            warmup: Boolean,
            reason: String,
        ) = BenchmarkMeasurement(
            sessionId = sessionId,
            stage = stage,
            timestamp = Instant.now().toString(),
            modelName = model.displayName,
            modelSha256 = model.sha256,
            modelBytes = model.bytes,
            threads = threads,
            temperature = config.temperature,
            outputTokens = 0,
            ttftMs = -1.0,
            elapsedMs = 0.0,
            tokensPerSecond = 0.0,
            peakMemoryKb = 0,
            backend = "not-run",
            backendProfile = "not-loaded",
            backendPreference = config.backendPreference.name.lowercase(),
            requestedDevice = "not-loaded",
            activeDevice = "not-loaded",
            registeredBackends = "not-loaded",
            registeredDevices = "not-loaded",
            layerOffload = "not-loaded",
            fallbackReason = null,
            batchSize = config.batchSize,
            ubatchSize = config.ubatchSize,
            systemInfo = "",
            flashAttention = config.flashAttention.name.lowercase(),
            kvCacheType = config.kvCacheType.name.lowercase(),
            modelLoadMs = -1.0,
            contextInitMs = -1.0,
            systemPrefillMs = -1.0,
            promptPrefillMs = -1.0,
            nativeDecodeMs = -1.0,
            thermalStatus = telemetry.thermalStatus,
            batteryTemperatureC = telemetry.batteryTemperatureC,
            batteryCurrentUa = telemetry.batteryCurrentUa,
            valid = false,
            warmup = warmup,
            invalidReason = reason,
        )
    }
}

internal data class BenchmarkSummary(
    val threads: Int,
    val measuredRuns: Int,
    val meanTtftMs: Double,
    val meanTokensPerSecond: Double,
    val maxPeakMemoryKb: Long,
    val runtime: BenchmarkMeasurement,
)

internal object BenchmarkExporter {
    fun summaries(items: List<BenchmarkMeasurement>): List<BenchmarkSummary> =
        items.filter { it.valid && !it.warmup }
            .groupBy { it.threads }
            .map { (threadCount, samples) ->
                val ttftSamples = samples.map { it.ttftMs }.filter { it >= 0.0 }
                BenchmarkSummary(
                    threads = threadCount,
                    measuredRuns = samples.size,
                    meanTtftMs = if (ttftSamples.isEmpty()) -1.0 else ttftSamples.average(),
                    meanTokensPerSecond = samples.map { it.tokensPerSecond }.average(),
                    maxPeakMemoryKb = samples.maxOf { it.peakMemoryKb },
                    runtime = samples.first(),
                )
            }
            .sortedBy { it.threads }

    fun recommended(items: List<BenchmarkMeasurement>): BenchmarkSummary? =
        summaries(items).maxWithOrNull(
            compareBy<BenchmarkSummary> { it.meanTokensPerSecond }
                .thenBy { -it.meanTtftMs }
        )

    private fun baseline(items: List<BenchmarkMeasurement>) = summaries(items).firstOrNull()

    fun json(session: BenchmarkSession?, items: List<BenchmarkMeasurement>) = buildString {
        append("{\n  \"schema\": \"arm-mobile-ai-benchmark/v3\",")
        if (session != null) append("\n  \"session\": {").append(session.json()).append("},")
        append("\n  \"measurements\": [\n")
        items.forEachIndexed { index, value ->
            append("    {").append(value.json()).append("}")
            append(if (index == items.lastIndex) "\n" else ",\n")
        }
        append("  ]\n}\n")
    }

    fun csvHeader() = "session_id,stage,session_complete,input_protocol,input_system_prompt_sha256,input_system_prompt_utf8_bytes,input_user_prompt_sha256,input_user_prompt_utf8_bytes,input_max_output_tokens,timestamp,model,sha256,model_bytes,threads,temperature,output_tokens,ttft_ms,end_to_end_ms,tokens_per_second,peak_memory_kb,backend,backend_profile,backend_preference,requested_device,active_device,registered_backends,registered_devices,layer_offload,fallback_reason,batch_size,ubatch_size,flash_attention,kv_cache_type,model_load_ms,context_init_ms,system_prefill_ms,prompt_prefill_ms,native_decode_ms,thermal_status,battery_temperature_c,battery_current_ua,valid,warmup,invalid_reason\n"

    fun csv(session: BenchmarkSession, items: List<BenchmarkMeasurement>) = csvHeader() + csvRows(items, session.complete, session.inputs)

    fun csv(items: List<BenchmarkMeasurement>) = csvHeader() + csvRows(items)

    fun csvRows(
        items: List<BenchmarkMeasurement>,
        sessionComplete: Boolean? = null,
        inputs: BenchmarkInputs? = null,
    ) = buildString {
        items.forEach { append(it.csv(sessionComplete, inputs)).append('\n') }
    }

    fun report(session: BenchmarkSession?, items: List<BenchmarkMeasurement>): String {
        val baseline = baseline(items)
        val optimized = recommended(items)
        val stage = session?.stage ?: items.firstOrNull()?.stage ?: "unlabeled stage"
        val config = session?.config
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Arm Mobile AI Report</title>")
            append("<style>body{font-family:sans-serif;margin:32px;color:#17212b}table{border-collapse:collapse;width:100%;margin:12px 0}th,td{padding:8px;border:1px solid #b9c2ca;text-align:left}th{background:#e8f1f3}</style></head><body>")
            append("<h1>Stage: ").append(html(stage)).append("</h1>")
            append("<p>Values are means across valid, non-warm-up runs. SEVERE thermal samples are retained but excluded from recommendations.</p>")
            if (session != null) {
                append("<h2>Reproducibility</h2><table><tr><th>Session</th><td>").append(html(session.id)).append("</td></tr>")
                append("<tr><th>Started</th><td>").append(html(session.startedAt)).append("</td></tr>")
                append("<tr><th>Stage status</th><td>").append(if (session.complete) "completed" else "partial").append("</td></tr>")
                append("<tr><th>App / ABI / APK SHA-256</th><td>").append(html(session.appVersion)).append(" / ").append(html(session.abi)).append(" / ").append(html(session.appApkSha256)).append("</td></tr>")
                append("<tr><th>Flash / KV / batch</th><td>").append(html(config!!.flashAttention.name)).append(" / ")
                append(html(config.kvCacheType.name)).append(" / ").append(config.batchSize).append(" / ").append(config.ubatchSize).append("</td></tr></table>")
                append("<h2>Input fingerprint</h2><table><tr><th>Protocol</th><td>").append(html(session.inputs.protocol)).append("</td></tr>")
                append("<tr><th>System prompt SHA-256 / bytes</th><td>").append(html(session.inputs.systemPromptSha256)).append(" / ").append(session.inputs.systemPromptUtf8Bytes).append("</td></tr>")
                append("<tr><th>User prompt SHA-256 / bytes</th><td>").append(html(session.inputs.userPromptSha256)).append(" / ").append(session.inputs.userPromptUtf8Bytes).append("</td></tr>")
                append("<tr><th>Maximum output tokens</th><td>").append(session.inputs.maxOutputTokens).append("</td></tr></table>")
            }
            append("<h2>Baseline vs Optimized</h2><table><tr><th>Configuration</th><th>Threads</th><th>Valid runs</th><th>Mean TTFT (ms)</th><th>Mean tokens/s</th><th>Peak memory max (KB)</th></tr>")
            append(summaryRow("Baseline", baseline)).append(summaryRow("Optimized", optimized)).append("</table>")
            append("<h2>Split Timing</h2><table><tr><th>Configuration</th><th>Model load</th><th>Context init</th><th>System prefill</th><th>Prompt prefill</th><th>Native decode</th><th>End-to-end</th></tr>")
            append(timingRow("Baseline", baseline?.runtime)).append(timingRow("Optimized", optimized?.runtime)).append("</table>")
            append("<h2>Runtime Evidence</h2><table><tr><th>Configuration</th><th>Profile</th><th>Requested / active</th><th>Flash / KV</th><th>Offload</th><th>Fallback</th></tr>")
            append(runtimeRow("Baseline", baseline?.runtime)).append(runtimeRow("Optimized", optimized?.runtime)).append("</table>")
            append("<p>Protocol: one warm-up plus five recorded runs per thread count using a fresh context, fixed prompt, sampling parameters, and this stage configuration.</p></body></html>")
        }
    }

    private fun summaryRow(label: String, value: BenchmarkSummary?) = "<tr><td>${html(label)}</td><td>${value?.threads ?: "n/a"}</td><td>${value?.measuredRuns ?: "n/a"}</td><td>${value?.meanTtftMs?.format(2) ?: "n/a"}</td><td>${value?.meanTokensPerSecond?.format(2) ?: "n/a"}</td><td>${value?.maxPeakMemoryKb ?: "n/a"}</td></tr>"

    private fun timingRow(label: String, value: BenchmarkMeasurement?): String {
        fun ms(value: Double?) = value?.takeIf { it >= 0.0 }?.format(2) ?: "n/a"
        return "<tr><td>${html(label)}</td><td>${ms(value?.modelLoadMs)}</td><td>${ms(value?.contextInitMs)}</td><td>${ms(value?.systemPrefillMs)}</td><td>${ms(value?.promptPrefillMs)}</td><td>${ms(value?.nativeDecodeMs)}</td><td>${ms(value?.elapsedMs)}</td></tr>"
    }

    private fun runtimeRow(label: String, value: BenchmarkMeasurement?): String = "<tr><td>${html(label)}</td><td>${html(value?.backendProfile ?: "n/a")}</td><td>${html(value?.requestedDevice ?: "n/a")} / ${html(value?.activeDevice ?: "n/a")}</td><td>${html(value?.flashAttention ?: "n/a")} / ${html(value?.kvCacheType ?: "n/a")}</td><td>${html(value?.layerOffload ?: "n/a")}</td><td>${html(value?.fallbackReason ?: "none")}</td></tr>"

    private fun BenchmarkSession.json() = "\"id\":\"${escape(id)}\",\"stage\":\"${escape(stage)}\",\"startedAt\":\"${escape(startedAt)}\",\"complete\":$complete,\"appVersion\":\"${escape(appVersion)}\",\"appApkSha256\":\"${escape(appApkSha256)}\",\"deviceFingerprint\":\"${escape(deviceFingerprint)}\",\"abi\":\"${escape(abi)}\",\"runtimeConfig\":{\"threads\":${config.threads},\"temperature\":${config.temperature},\"backendPreference\":\"${escape(config.backendPreference.name.lowercase())}\",\"flashAttention\":\"${escape(config.flashAttention.name.lowercase())}\",\"kvCacheType\":\"${escape(config.kvCacheType.name.lowercase())}\",\"batchSize\":${config.batchSize},\"ubatchSize\":${config.ubatchSize}},\"inputs\":{${inputs.json()}}"

    private fun BenchmarkInputs.json() = "\"protocol\":\"${escape(protocol)}\",\"systemPromptSha256\":\"${escape(systemPromptSha256)}\",\"systemPromptUtf8Bytes\":$systemPromptUtf8Bytes,\"userPromptSha256\":\"${escape(userPromptSha256)}\",\"userPromptUtf8Bytes\":$userPromptUtf8Bytes,\"maxOutputTokens\":$maxOutputTokens"

    private fun BenchmarkMeasurement.json() = "\"sessionId\":\"${escape(sessionId)}\",\"stage\":\"${escape(stage)}\",\"timestamp\":\"${escape(timestamp)}\",\"model\":\"${escape(modelName)}\",\"sha256\":\"$modelSha256\",\"modelBytes\":$modelBytes,\"threads\":$threads,\"temperature\":$temperature,\"outputTokens\":$outputTokens,\"ttftMs\":$ttftMs,\"endToEndMs\":$elapsedMs,\"tokensPerSecond\":$tokensPerSecond,\"peakMemoryKb\":$peakMemoryKb,\"backend\":\"${escape(backend)}\",\"backendProfile\":\"${escape(backendProfile)}\",\"backendPreference\":\"${escape(backendPreference)}\",\"requestedDevice\":\"${escape(requestedDevice)}\",\"activeDevice\":\"${escape(activeDevice)}\",\"registeredBackends\":\"${escape(registeredBackends)}\",\"registeredDevices\":\"${escape(registeredDevices)}\",\"layerOffload\":\"${escape(layerOffload)}\",\"fallbackReason\":${fallbackReason?.let { "\"${escape(it)}\"" } ?: "null"},\"batchSize\":$batchSize,\"ubatchSize\":$ubatchSize,\"flashAttention\":\"${escape(flashAttention)}\",\"kvCacheType\":\"${escape(kvCacheType)}\",\"modelLoadMs\":$modelLoadMs,\"contextInitMs\":$contextInitMs,\"systemPrefillMs\":$systemPrefillMs,\"promptPrefillMs\":$promptPrefillMs,\"nativeDecodeMs\":$nativeDecodeMs,\"thermalStatus\":$thermalStatus,\"batteryTemperatureC\":${batteryTemperatureC ?: "null"},\"batteryCurrentUa\":${batteryCurrentUa ?: "null"},\"valid\":$valid,\"warmup\":$warmup,\"invalidReason\":${invalidReason?.let { "\"${escape(it)}\"" } ?: "null"}"

    private fun BenchmarkMeasurement.csv(sessionComplete: Boolean?, inputs: BenchmarkInputs?) = listOf(
        sessionId, stage, sessionComplete,
        inputs?.protocol, inputs?.systemPromptSha256, inputs?.systemPromptUtf8Bytes,
        inputs?.userPromptSha256, inputs?.userPromptUtf8Bytes, inputs?.maxOutputTokens,
        timestamp, modelName, modelSha256, modelBytes, threads, temperature, outputTokens,
        ttftMs, elapsedMs, tokensPerSecond, peakMemoryKb, backend, backendProfile, backendPreference,
        requestedDevice, activeDevice, registeredBackends, registeredDevices, layerOffload, fallbackReason,
        batchSize, ubatchSize, flashAttention, kvCacheType, modelLoadMs, contextInitMs, systemPrefillMs,
        promptPrefillMs, nativeDecodeMs, thermalStatus, batteryTemperatureC, batteryCurrentUa, valid, warmup, invalidReason,
    ).joinToString(",") { csvValue(it) }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun csvValue(value: Any?) = "\"${(value?.toString() ?: "").replace("\"", "\"\"")}\""
}

private fun Double.format(scale: Int) = String.format(Locale.US, "%.${scale}f", this)

private fun GgufMetadata.filename() = when {
    basic.name != null -> basic.sizeLabel?.let { "${basic.name}-$it" } ?: requireNotNull(basic.name)
    architecture?.architecture != null -> "${requireNotNull(architecture?.architecture)}-${basic.uuid ?: System.currentTimeMillis().toString(16)}"
    else -> "model-${System.currentTimeMillis().toString(16)}"
}

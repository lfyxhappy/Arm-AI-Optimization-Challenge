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
import com.arm.aichat.InferenceEngine
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
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var importButton: Button
    private lateinit var stagedButton: Button
    private lateinit var tuneButton: Button
    private var generationJob: Job? = null
    @Volatile private var stopRequested = false
    private var currentModel: ImportedModel? = null
    private val messages = mutableListOf<Message>()
    private val interactiveMeasurements = mutableListOf<BenchmarkMeasurement>()
    private val benchmarkMeasurements = mutableListOf<BenchmarkMeasurement>()
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
        importButton = findViewById(R.id.import_model)
        stagedButton = findViewById(R.id.load_staged_model)
        tuneButton = findViewById(R.id.auto_tune)
        threads.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("2", "4", "6", "8"))
        threads.setSelection(1)
        backendMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            BACKEND_PREFERENCES.map { it.uiLabel },
        )
        val recycler = findViewById<RecyclerView>(R.id.messages)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageAdapter = MessageAdapter(messages)
        recycler.adapter = messageAdapter

        importButton.setOnClickListener { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        stagedButton.setOnClickListener { loadStagedModel() }
        refreshStagedModelButton()
        findViewById<Button>(R.id.send).setOnClickListener { sendChat() }
        findViewById<Button>(R.id.stop).setOnClickListener { stopActiveGeneration() }
        tuneButton.setOnClickListener { autoTune() }
        findViewById<Button>(R.id.export_json).setOnClickListener { export("benchmark.json", "application/json", BenchmarkExporter.json(currentModel, benchmarkMeasurements)) }
        findViewById<Button>(R.id.export_csv).setOnClickListener { export("benchmark.csv", "text/csv", BenchmarkExporter.csv(benchmarkMeasurements)) }
        findViewById<Button>(R.id.export_report).setOnClickListener { export("baseline-vs-optimized.html", "text/html", BenchmarkExporter.report(currentModel, benchmarkMeasurements)) }

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
        val configuredThreads = selectedThreads()
        val configuredTemperature = selectedTemperature()
        val configuredBackend = selectedBackendPreference()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metadata = contentResolver.openInputStream(uri)?.use { GgufMetadataReader.create().readStructuredMetadata(it) }
                    ?: error("Unable to read the selected file")
                val imported = copyAndHash(uri, metadata)
                configureAndLoad(imported, configuredThreads, configuredTemperature, configuredBackend)
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
        val configuredThreads = selectedThreads()
        val configuredTemperature = selectedTemperature()
        val configuredBackend = selectedBackendPreference()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metadata = FileInputStream(file).use { GgufMetadataReader.create().readStructuredMetadata(it) }
                val imported = ImportedModel(file, sha256(file), file.length(), metadata.basic.name ?: "staged GGUF")
                configureAndLoad(imported, configuredThreads, configuredTemperature, configuredBackend)
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
        threads: Int,
        temp: Float,
        backendPreference: BackendPreference,
    ) {
        if (engine.state.value !is InferenceEngine.State.Initialized) engine.cleanUp()
        engine.setRuntimeConfig(threads, temp, backendPreference)
        engine.loadModel(model.file.absolutePath)
    }

    private fun sendChat() {
        val prompt = input.text.toString().trim()
        val model = currentModel
        if (prompt.isEmpty() || model == null) return
        val configuredThreads = selectedThreads()
        val configuredTemperature = selectedTemperature()
        val configuredBackend = selectedBackendPreference()
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
                configureAndLoad(model, configuredThreads, configuredTemperature, configuredBackend)
                engine.setSystemPrompt(configuredSystemPrompt)
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
        val temp = selectedTemperature()
        val backendPreference = selectedBackendPreference()
        val benchmarkSystemPrompt = systemPrompt.text.toString().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val benchmarkMaxTokens = selectedMaxTokens()
        benchmarkMeasurements.clear()
        setBusy(true, "Auto tuning new session: 1 warm-up + 5 measured runs per thread count...")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val prompt = BENCHMARK_PROMPT
                for (threadCount in THREAD_CANDIDATES) {
                    statusOnMain("Warm-up at $threadCount threads")
                    benchmarkMeasurements += runBenchmark(model, threadCount, temp, backendPreference, prompt, benchmarkSystemPrompt, benchmarkMaxTokens, warmup = true)
                    repeat(MEASURED_RUNS) { index ->
                        statusOnMain("Measuring $threadCount threads: ${index + 1}/$MEASURED_RUNS")
                        benchmarkMeasurements += runBenchmark(model, threadCount, temp, backendPreference, prompt, benchmarkSystemPrompt, benchmarkMaxTokens, warmup = false)
                    }
                }
                val best = BenchmarkExporter.recommended(benchmarkMeasurements)
                statusOnMain(best?.let {
                    "Recommended: ${it.threads} threads (${it.meanTokensPerSecond.format(2)} mean tokens/s)"
                } ?: "No valid measurements: device thermal status was severe")
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { showError("Auto tuning failed", error) }
            } finally {
                withContext(Dispatchers.Main) { setBusy(false) }
            }
        }
    }

    private suspend fun runBenchmark(model: ImportedModel, threadCount: Int, temp: Float, backendPreference: BackendPreference, prompt: String, benchmarkSystemPrompt: String, benchmarkMaxTokens: Int, warmup: Boolean): BenchmarkMeasurement {
        val before = DeviceTelemetry.capture(this)
        if (before.isSevere) return BenchmarkMeasurement.skipped(model, threadCount, temp, backendPreference, before, warmup, "Thermal status is SEVERE or higher")
        configureAndLoad(model, threadCount, temp, backendPreference)
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
            timestamp = Instant.now().toString(), modelName = model.displayName, modelSha256 = model.sha256,
            modelBytes = model.bytes, threads = threadCount, temperature = temp, outputTokens = tokens,
            ttftMs = if (firstTokenAt == 0L) -1.0 else (firstTokenAt - started) / 1_000_000.0,
            elapsedMs = (ended - started) / 1_000_000.0,
            tokensPerSecond = if (tokens == 0) 0.0 else tokens * 1_000_000_000.0 / (ended - started),
            peakMemoryKb = runtime.peakMemoryKb, backend = runtime.backend,
            backendProfile = runtime.backendProfile, backendPreference = runtime.backendPreference.name.lowercase(),
            requestedDevice = runtime.requestedDevice, activeDevice = runtime.activeDevice,
            registeredBackends = runtime.registeredBackends, registeredDevices = runtime.registeredDevices,
            layerOffload = runtime.layerOffload, fallbackReason = runtime.fallbackReason,
            batchSize = runtime.batchSize, ubatchSize = runtime.ubatchSize, systemInfo = runtime.systemInfo,
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
            Instant.now().toString(), model.displayName, model.sha256, model.bytes, runtime.configuredThreads,
            runtime.temperature, tokens, if (first == 0L) -1.0 else (first - started) / 1_000_000.0,
            (ended - started) / 1_000_000.0, if (tokens == 0) 0.0 else tokens * 1e9 / (ended - started),
            runtime.peakMemoryKb, runtime.backend, runtime.backendProfile, runtime.backendPreference.name.lowercase(),
            runtime.requestedDevice, runtime.activeDevice, runtime.registeredBackends, runtime.registeredDevices,
            runtime.layerOffload, runtime.fallbackReason, runtime.batchSize, runtime.ubatchSize, runtime.systemInfo, telemetry.thermalStatus,
            telemetry.batteryTemperatureC, telemetry.batteryCurrentUa, validityIssue == null, false, validityIssue,
        )
    }

    private fun export(filename: String, mime: String, content: String) {
        if (benchmarkMeasurements.isEmpty()) { toast("Run auto tune before exporting"); return }
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
    private fun setBusy(busy: Boolean, message: String? = null) { importButton.isEnabled = !busy; tuneButton.isEnabled = !busy; backendMode.isEnabled = !busy; if (message != null) status.text = message }
    private suspend fun statusOnMain(text: String) = withContext(Dispatchers.Main) { status.text = text }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun showError(prefix: String, error: Exception) { status.text = "$prefix: ${error.message ?: error.javaClass.simpleName}"; toast(status.text.toString()) }
    private fun stopActiveGeneration() {
        stopRequested = true
        if (::engine.isInitialized) engine.stopGeneration()
    }
    override fun onStop() { stopActiveGeneration(); generationJob?.cancel(); super.onStop() }
    override fun onDestroy() { if (::engine.isInitialized) engine.destroy(); super.onDestroy() }

    private data class ExportDocument(val filename: String, val content: String)
    data class ImportedModel(val file: File, val sha256: String, val bytes: Long, val displayName: String) {
        val filename get() = file.name
        fun describe(metadata: GgufMetadata) = "$displayName\n${(bytes / (1024.0 * 1024.0 * 1024.0)).format(2)} GiB | SHA-256 ${sha256.take(16)}...\n${metadata.basic}"
    }
    private companion object {
        val THREAD_CANDIDATES = listOf(2, 4, 6, 8)
        val BACKEND_PREFERENCES = BackendPreference.values()
        const val MEASURED_RUNS = 5
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
    val timestamp: String, val modelName: String, val modelSha256: String, val modelBytes: Long, val threads: Int,
    val temperature: Float, val outputTokens: Int, val ttftMs: Double, val elapsedMs: Double, val tokensPerSecond: Double,
    val peakMemoryKb: Long, val backend: String, val backendProfile: String, val backendPreference: String,
    val requestedDevice: String, val activeDevice: String, val registeredBackends: String, val registeredDevices: String,
    val layerOffload: String, val fallbackReason: String?, val batchSize: Int, val ubatchSize: Int, val systemInfo: String,
    val thermalStatus: Int,
    val batteryTemperatureC: Double?, val batteryCurrentUa: Int?, val valid: Boolean, val warmup: Boolean, val invalidReason: String?,
) {
    companion object {
        fun skipped(model: MainActivity.ImportedModel, threads: Int, temp: Float, backendPreference: BackendPreference, telemetry: DeviceTelemetry, warmup: Boolean, reason: String) =
            BenchmarkMeasurement(Instant.now().toString(), model.displayName, model.sha256, model.bytes, threads, temp, 0, -1.0, 0.0, 0.0, 0,
                "not-run", "not-loaded", backendPreference.name.lowercase(), "not-loaded", "not-loaded", "not-loaded", "not-loaded",
                "not-loaded", null, 0, 0, "", telemetry.thermalStatus, telemetry.batteryTemperatureC, telemetry.batteryCurrentUa, false, warmup, reason)
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
    fun json(model: Any?, items: List<BenchmarkMeasurement>) = buildString {
        append("{\n  \"schema\": \"arm-mobile-ai-benchmark/v1\",\n  \"measurements\": [\n")
        items.forEachIndexed { index, value -> append("    {").append(value.json()).append("}").append(if (index == items.lastIndex) "\n" else ",\n") }
        append("  ]\n}\n")
    }
    fun csv(items: List<BenchmarkMeasurement>) = buildString {
        append("timestamp,model,sha256,threads,temperature,output_tokens,ttft_ms,tokens_per_second,peak_memory_kb,backend,backend_profile,backend_preference,requested_device,active_device,registered_backends,registered_devices,layer_offload,fallback_reason,batch_size,ubatch_size,thermal_status,battery_temperature_c,battery_current_ua,valid,warmup,invalid_reason\n")
        items.forEach { append(listOf(it.timestamp,it.modelName,it.modelSha256,it.threads,it.temperature,it.outputTokens,it.ttftMs,it.tokensPerSecond,it.peakMemoryKb,it.backend,it.backendProfile,it.backendPreference,it.requestedDevice,it.activeDevice,it.registeredBackends,it.registeredDevices,it.layerOffload,it.fallbackReason,it.batchSize,it.ubatchSize,it.thermalStatus,it.batteryTemperatureC,it.batteryCurrentUa,it.valid,it.warmup,it.invalidReason).joinToString(",") { csvValue(it) }).append('\n') }
    }
    fun report(model: Any?, items: List<BenchmarkMeasurement>): String {
        val baseline = baseline(items)
        val optimized = recommended(items)
        val baselineRuntime = baseline?.runtime
        val optimizedRuntime = optimized?.runtime
        return """<!doctype html><html><head><meta charset="utf-8"><title>Arm Mobile AI Report</title><style>body{font-family:sans-serif;margin:32px;color:#17212b}table{border-collapse:collapse;width:100%}th,td{padding:8px;border:1px solid #b9c2ca;text-align:left}th{background:#e8f1f3}</style></head><body><h1>Baseline vs Optimized</h1><p>Generated by Arm Mobile AI Optimization Workbench. Values are means across valid, non-warm-up runs; peak memory is the maximum observed value. SEVERE thermal samples are excluded from the recommendation.</p><table><tr><th>Configuration</th><th>Threads</th><th>Valid runs</th><th>Mean TTFT (ms)</th><th>Mean tokens/s</th><th>Peak memory max (KB)</th></tr><tr><td>Baseline</td><td>${baseline?.threads ?: "n/a"}</td><td>${baseline?.measuredRuns ?: "n/a"}</td><td>${baseline?.meanTtftMs?.format(2) ?: "n/a"}</td><td>${baseline?.meanTokensPerSecond?.format(2) ?: "n/a"}</td><td>${baseline?.maxPeakMemoryKb ?: "n/a"}</td></tr><tr><td>Optimized</td><td>${optimized?.threads ?: "n/a"}</td><td>${optimized?.measuredRuns ?: "n/a"}</td><td>${optimized?.meanTtftMs?.format(2) ?: "n/a"}</td><td>${optimized?.meanTokensPerSecond?.format(2) ?: "n/a"}</td><td>${optimized?.maxPeakMemoryKb ?: "n/a"}</td></tr></table><h2>Runtime evidence</h2><table><tr><th>Field</th><th>Baseline</th><th>Optimized</th></tr><tr><td>Profile</td><td>${baselineRuntime?.backendProfile ?: "n/a"}</td><td>${optimizedRuntime?.backendProfile ?: "n/a"}</td></tr><tr><td>Requested / active</td><td>${baselineRuntime?.requestedDevice ?: "n/a"} / ${baselineRuntime?.activeDevice ?: "n/a"}</td><td>${optimizedRuntime?.requestedDevice ?: "n/a"} / ${optimizedRuntime?.activeDevice ?: "n/a"}</td></tr><tr><td>Layer offload</td><td>${baselineRuntime?.layerOffload ?: "n/a"}</td><td>${optimizedRuntime?.layerOffload ?: "n/a"}</td></tr><tr><td>Batch / ubatch</td><td>${baselineRuntime?.batchSize ?: "n/a"} / ${baselineRuntime?.ubatchSize ?: "n/a"}</td><td>${optimizedRuntime?.batchSize ?: "n/a"} / ${optimizedRuntime?.ubatchSize ?: "n/a"}</td></tr><tr><td>Fallback</td><td>${baselineRuntime?.fallbackReason ?: "none"}</td><td>${optimizedRuntime?.fallbackReason ?: "none"}</td></tr></table><h2>Protocol</h2><p>Per thread: 1 warm-up plus 5 recorded runs with the same Chinese prompt, system prompt, temperature, backend mode, and maximum output tokens.</p></body></html>"""
    }
    private fun BenchmarkMeasurement.json() = "\"timestamp\":\"${escape(timestamp)}\",\"model\":\"${escape(modelName)}\",\"sha256\":\"$modelSha256\",\"threads\":$threads,\"temperature\":$temperature,\"outputTokens\":$outputTokens,\"ttftMs\":$ttftMs,\"tokensPerSecond\":$tokensPerSecond,\"peakMemoryKb\":$peakMemoryKb,\"backend\":\"${escape(backend)}\",\"backendProfile\":\"${escape(backendProfile)}\",\"backendPreference\":\"${escape(backendPreference)}\",\"requestedDevice\":\"${escape(requestedDevice)}\",\"activeDevice\":\"${escape(activeDevice)}\",\"registeredBackends\":\"${escape(registeredBackends)}\",\"registeredDevices\":\"${escape(registeredDevices)}\",\"layerOffload\":\"${escape(layerOffload)}\",\"fallbackReason\":${fallbackReason?.let { "\"${escape(it)}\"" } ?: "null"},\"batchSize\":$batchSize,\"ubatchSize\":$ubatchSize,\"thermalStatus\":$thermalStatus,\"batteryTemperatureC\":${batteryTemperatureC ?: "null"},\"batteryCurrentUa\":${batteryCurrentUa ?: "null"},\"valid\":$valid,\"warmup\":$warmup,\"invalidReason\":${invalidReason?.let { "\"${escape(it)}\"" } ?: "null"}"
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun csvValue(value: Any?) = "\"${(value?.toString() ?: "").replace("\"", "\"\"")}\""
}

private fun Double.format(scale: Int) = String.format(Locale.US, "%.${scale}f", this)

private fun GgufMetadata.filename() = when {
    basic.name != null -> basic.sizeLabel?.let { "${basic.name}-$it" } ?: requireNotNull(basic.name)
    architecture?.architecture != null -> "${requireNotNull(architecture?.architecture)}-${basic.uuid ?: System.currentTimeMillis().toString(16)}"
    else -> "model-${System.currentTimeMillis().toString(16)}"
}

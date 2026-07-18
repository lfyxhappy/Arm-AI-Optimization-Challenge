package com.lfyxhappy.armai

import android.content.Context
import android.os.Build
import com.arm.aichat.RuntimeConfig
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Privacy-preserving identity for the controlled inputs of a formal stage.
 * The prompt contents stay on-device; their UTF-8 SHA-256 values make a later
 * cross-stage comparison auditable without exporting the prompt text itself.
 */
internal data class BenchmarkInputs(
    val protocol: String,
    val systemPromptSha256: String,
    val systemPromptUtf8Bytes: Int,
    val userPromptSha256: String,
    val userPromptUtf8Bytes: Int,
    val maxOutputTokens: Int,
) {
    companion object {
        const val PROTOCOL = "fixed-prompt-v1"

        fun from(systemPrompt: String, userPrompt: String, maxOutputTokens: Int): BenchmarkInputs {
            require(maxOutputTokens > 0) { "Maximum output tokens must be positive" }
            val systemBytes = systemPrompt.toByteArray(Charsets.UTF_8)
            val userBytes = userPrompt.toByteArray(Charsets.UTF_8)
            return BenchmarkInputs(
                protocol = PROTOCOL,
                systemPromptSha256 = sha256(systemBytes),
                systemPromptUtf8Bytes = systemBytes.size,
                userPromptSha256 = sha256(userBytes),
                userPromptUtf8Bytes = userBytes.size,
                maxOutputTokens = maxOutputTokens,
            )
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** The benchmark sequence represented by a formal immutable session. */
internal enum class BenchmarkRunMode(val persistedValue: String, val uiLabel: String) {
    AUTO_TUNE("auto_tune", "Auto tuning"),
    TARGETED_REPEAT("targeted_repeat", "Targeted repeat"),
}

/** Immutable metadata attached to one formal benchmark run. */
internal data class BenchmarkSession(
    val id: String,
    val stage: String,
    val startedAt: String,
    val appVersion: String,
    val appApkSha256: String,
    val deviceFingerprint: String,
    val abi: String,
    val config: RuntimeConfig,
    val inputs: BenchmarkInputs,
    val runMode: BenchmarkRunMode,
    val threadCandidates: List<Int>,
    val complete: Boolean,
    val measurements: List<BenchmarkMeasurement>,
) {
    init {
        require(threadCandidates.isNotEmpty()) { "At least one thread candidate is required" }
        require(threadCandidates.all { it in 1..8 }) { "Thread candidates must be 1..8" }
        require(threadCandidates.distinct().size == threadCandidates.size) { "Thread candidates must be unique" }
    }
}

internal data class BenchmarkArchiveResult(
    val json: File,
    val csv: File,
    val html: File,
    val sessionCount: Int,
)

/**
 * Formal sessions are immutable files under app-private storage. Each archive
 * receives a time- and UUID-qualified name so a later optimization stage can
 * never overwrite its baseline. The aggregate CSV is intentionally append-only
 * from the user's perspective and is exportable through the UI.
 */
internal class BenchmarkArchiveStore(private val context: Context) {
    private val root = File(context.filesDir, "benchmark-stages")
    private val historyCsv = File(root, "all-stages.csv")

    fun archive(session: BenchmarkSession): BenchmarkArchiveResult {
        root.mkdirs()
        check(root.isDirectory) { "Cannot create benchmark archive directory" }
        val prefix = "${timestampSlug(session.startedAt)}-${slug(session.stage)}-${session.id.takeLast(8)}"
        val json = File(root, "$prefix.json")
        val csv = File(root, "$prefix.csv")
        val html = File(root, "$prefix.html")
        atomicWrite(json, BenchmarkExporter.json(session, session.measurements))
        atomicWrite(csv, BenchmarkExporter.csv(session, session.measurements))
        atomicWrite(html, BenchmarkExporter.report(session, session.measurements))

        val existing = if (historyCsv.isFile) upgradeHistoryCsvForRunScope(historyCsv.readText()) else BenchmarkExporter.csvHeader()
        atomicWrite(
            historyCsv,
            existing + BenchmarkExporter.csvRows(
                session.measurements,
                session.complete,
                session.inputs,
                session.runMode,
                session.threadCandidates,
            ),
        )
        return BenchmarkArchiveResult(json, csv, html, sessionCount())
    }

    fun sessionCount(): Int = root.listFiles { file -> file.extension == "json" }?.size ?: 0

    fun historyCsvOrNull(): String? = historyCsv.takeIf(File::isFile)?.readText()

    companion object {
        private const val LEGACY_HISTORY_HEADER = "session_id,stage,session_complete,input_protocol,input_system_prompt_sha256,input_system_prompt_utf8_bytes,input_user_prompt_sha256,input_user_prompt_utf8_bytes,input_max_output_tokens,timestamp,model,sha256,model_bytes,threads,temperature,output_tokens,ttft_ms,end_to_end_ms,tokens_per_second,peak_memory_kb,backend,backend_profile,backend_preference,requested_device,active_device,registered_backends,registered_devices,layer_offload,fallback_reason,batch_size,ubatch_size,flash_attention,kv_cache_type,model_load_ms,context_init_ms,system_prefill_ms,prompt_prefill_ms,native_decode_ms,thermal_status,battery_temperature_c,battery_current_ua,valid,warmup,invalid_reason"

        /**
         * Adds the run-scope columns to a previous app version's aggregate
         * history without discarding its immutable measurement rows.
         */
        internal fun upgradeHistoryCsvForRunScope(existing: String): String {
            val lines = existing.lineSequence().toList()
            val header = lines.firstOrNull()?.trimEnd('\r') ?: return BenchmarkExporter.csvHeader()
            if (header == BenchmarkExporter.csvHeader().trimEnd('\n', '\r')) return existing
            check(header == LEGACY_HISTORY_HEADER) { "Unsupported benchmark history CSV header" }

            val legacyColumnCount = LEGACY_HISTORY_HEADER.split(',').size
            val migratedRows = lines.drop(1)
                .filter { it.isNotBlank() }
                .map { row ->
                    val fields = parseCsvRow(row.trimEnd('\r')).toMutableList()
                    check(fields.size == legacyColumnCount) { "Malformed legacy benchmark history CSV row" }
                    fields.add(3, "")
                    fields.add(4, "")
                    fields.joinToString(",") { csvValue(it) }
                }
            return buildString {
                append(BenchmarkExporter.csvHeader())
                migratedRows.forEach { append(it).append('\n') }
            }
        }

        fun createSession(
            context: Context,
            stage: String,
            config: RuntimeConfig,
            inputs: BenchmarkInputs,
            measurements: List<BenchmarkMeasurement>,
            runMode: BenchmarkRunMode,
            threadCandidates: List<Int>,
            complete: Boolean,
            startedAt: String = Instant.now().toString(),
            id: String = UUID.randomUUID().toString(),
        ) = BenchmarkSession(
            id = id,
            stage = stage,
            startedAt = startedAt,
            appVersion = appVersion(context),
            appApkSha256 = apkSha256(context),
            deviceFingerprint = Build.FINGERPRINT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            config = config,
            inputs = inputs,
            runMode = runMode,
            threadCandidates = threadCandidates.toList(),
            complete = complete,
            measurements = measurements,
        )

        private fun appVersion(context: Context): String = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")

        private fun apkSha256(context: Context): String = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.getOrDefault("unavailable")

        private fun timestampSlug(value: String) = value
            .replace(Regex("[^0-9A-Za-z]+"), "-")
            .trim('-')
            .take(40)

        private fun slug(value: String) = value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "unnamed-stage" }
            .take(48)

        private fun parseCsvRow(row: String): List<String> {
            val fields = mutableListOf<String>()
            val value = StringBuilder()
            var quoted = false
            var index = 0
            while (index < row.length) {
                when (val character = row[index]) {
                    '"' -> if (quoted && index + 1 < row.length && row[index + 1] == '"') {
                        value.append('"')
                        index++
                    } else {
                        quoted = !quoted
                    }
                    ',' -> if (quoted) value.append(character) else {
                        fields += value.toString()
                        value.clear()
                    }
                    else -> value.append(character)
                }
                index++
            }
            check(!quoted) { "Unterminated quoted field in benchmark history CSV" }
            fields += value.toString()
            return fields
        }

        private fun csvValue(value: String) = "\"${value.replace("\"", "\"\"")}\""

        private fun atomicWrite(destination: File, content: String) {
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.bufferedWriter().use { it.write(content) }
            check(temporary.renameTo(destination)) { "Cannot finalize ${destination.name}" }
        }
    }
}

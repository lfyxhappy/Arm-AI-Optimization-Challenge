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

/** Immutable metadata attached to one formal auto-tune run. */
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
    val complete: Boolean,
    val measurements: List<BenchmarkMeasurement>,
)

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

        val existing = if (historyCsv.isFile) historyCsv.readText() else BenchmarkExporter.csvHeader()
        atomicWrite(historyCsv, existing + BenchmarkExporter.csvRows(session.measurements, session.complete, session.inputs))
        return BenchmarkArchiveResult(json, csv, html, sessionCount())
    }

    fun sessionCount(): Int = root.listFiles { file -> file.extension == "json" }?.size ?: 0

    fun historyCsvOrNull(): String? = historyCsv.takeIf(File::isFile)?.readText()

    companion object {
        fun createSession(
            context: Context,
            stage: String,
            config: RuntimeConfig,
            inputs: BenchmarkInputs,
            measurements: List<BenchmarkMeasurement>,
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

        private fun atomicWrite(destination: File, content: String) {
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.bufferedWriter().use { it.write(content) }
            check(temporary.renameTo(destination)) { "Cannot finalize ${destination.name}" }
        }
    }
}

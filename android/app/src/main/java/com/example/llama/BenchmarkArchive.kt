package com.lfyxhappy.armai

import android.content.Context
import android.os.Build
import com.arm.aichat.RuntimeConfig
import java.io.File
import java.time.Instant
import java.util.UUID

/** Immutable metadata attached to one formal auto-tune run. */
internal data class BenchmarkSession(
    val id: String,
    val stage: String,
    val startedAt: String,
    val appVersion: String,
    val deviceFingerprint: String,
    val abi: String,
    val config: RuntimeConfig,
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
        atomicWrite(historyCsv, existing + BenchmarkExporter.csvRows(session.measurements, session.complete))
        return BenchmarkArchiveResult(json, csv, html, sessionCount())
    }

    fun sessionCount(): Int = root.listFiles { file -> file.extension == "json" }?.size ?: 0

    fun historyCsvOrNull(): String? = historyCsv.takeIf(File::isFile)?.readText()

    companion object {
        fun createSession(
            context: Context,
            stage: String,
            config: RuntimeConfig,
            measurements: List<BenchmarkMeasurement>,
            complete: Boolean,
            startedAt: String = Instant.now().toString(),
            id: String = UUID.randomUUID().toString(),
        ) = BenchmarkSession(
            id = id,
            stage = stage,
            startedAt = startedAt,
            appVersion = appVersion(context),
            deviceFingerprint = Build.FINGERPRINT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            config = config,
            complete = complete,
            measurements = measurements,
        )

        private fun appVersion(context: Context): String = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")

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

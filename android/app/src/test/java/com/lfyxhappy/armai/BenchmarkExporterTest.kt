package com.lfyxhappy.armai

import com.arm.aichat.FlashAttentionMode
import com.arm.aichat.KvCacheType
import com.arm.aichat.RuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkExporterTest {
    @Test
    fun recommendationAndReportUseValidGroupMeans() {
        val measurements = listOf(
            measurement(2, 6.0, 2_000.0, 8_000_000),
            measurement(2, 6.0, 2_200.0, 8_100_000),
            measurement(4, 9.0, 1_000.0, 9_000_000),
            measurement(4, 9.0, 1_100.0, 9_100_000),
            measurement(6, 9.0, 1_200.0, 9_200_000),
            measurement(6, 9.0, 1_300.0, 9_300_000),
            measurement(8, 100.0, 100.0, 10_000_000, warmup = true),
            measurement(8, 100.0, 100.0, 10_000_000, valid = false),
        )

        val summaries = BenchmarkExporter.summaries(measurements)
        val recommended = requireNotNull(BenchmarkExporter.recommended(measurements))
        val report = BenchmarkExporter.report(null, measurements)

        assertEquals(listOf(2, 4, 6), summaries.map { it.threads })
        assertEquals(4, recommended.threads)
        assertEquals(9.0, recommended.meanTokensPerSecond, 0.001)
        assertEquals(1_050.0, recommended.meanTtftMs, 0.001)
        assertEquals(9_100_000, recommended.maxPeakMemoryKb)
        assertTrue(report.contains("<th>Mean tokens/s</th>"))
        assertTrue(report.contains("<td>1050.00</td>"))
        assertTrue(report.contains("<td>9.00</td>"))
        assertTrue(report.contains("<td>9100000</td>"))
        assertTrue(report.contains("Split Timing"))
        assertTrue(BenchmarkExporter.csv(measurements).contains("flash_attention"))
    }

    @Test
    fun stageExportCarriesImmutableConfigurationAndSplitTimings() {
        val config = RuntimeConfig(
            threads = 6,
            flashAttention = FlashAttentionMode.ENABLED,
            kvCacheType = KvCacheType.Q8_0,
            batchSize = 256,
            ubatchSize = 128,
        )
        val session = BenchmarkSession(
            id = "session-1",
            stage = "cpu-q4-fa-on-q8-kv",
            startedAt = "2026-07-18T00:00:00Z",
            appVersion = "0.1.0",
            deviceFingerprint = "test-device",
            abi = "arm64-v8a",
            config = config,
            complete = true,
            measurements = listOf(measurement(6, 12.0, 900.0, 9_100_000)),
        )

        val json = BenchmarkExporter.json(session, session.measurements)
        val csv = BenchmarkExporter.csv(session, session.measurements)
        val report = BenchmarkExporter.report(session, session.measurements)

        assertTrue(json.contains("arm-mobile-ai-benchmark/v2"))
        assertTrue(json.contains("cpu-q4-fa-on-q8-kv"))
        assertTrue(json.contains("\"flashAttention\":\"enabled\""))
        assertTrue(json.contains("\"kvCacheType\":\"q8_0\""))
        assertTrue(json.contains("\"complete\":true"))
        assertTrue(csv.contains("native_decode_ms"))
        assertTrue(csv.contains("\"true\""))
        assertTrue(report.contains("Flash / KV / batch"))
        assertTrue(report.contains("Split Timing"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun quantizedKvCacheRejectsFlashAttentionOff() {
        RuntimeConfig(
            flashAttention = FlashAttentionMode.DISABLED,
            kvCacheType = KvCacheType.Q4_0,
        )
    }

    private fun measurement(
        threads: Int,
        tokensPerSecond: Double,
        ttftMs: Double,
        peakMemoryKb: Long,
        warmup: Boolean = false,
        valid: Boolean = true,
    ) = BenchmarkMeasurement(
        sessionId = "session-1",
        stage = "cpu-q4-baseline",
        timestamp = "2026-07-18T00:00:00Z",
        modelName = "test-model",
        modelSha256 = "test-sha",
        modelBytes = 1,
        threads = threads,
        temperature = 0.3f,
        outputTokens = 32,
        ttftMs = ttftMs,
        elapsedMs = 1_000.0,
        tokensPerSecond = tokensPerSecond,
        peakMemoryKb = peakMemoryKb,
        backend = "CPU",
        backendProfile = "cpu",
        backendPreference = "auto",
        requestedDevice = "CPU",
        activeDevice = "CPU",
        registeredBackends = "CPU",
        registeredDevices = "CPU",
        layerOffload = "0 layers (CPU)",
        fallbackReason = null,
        batchSize = 512,
        ubatchSize = 512,
        systemInfo = "",
        flashAttention = "auto",
        kvCacheType = "f16",
        modelLoadMs = 100.0,
        contextInitMs = 20.0,
        systemPrefillMs = 10.0,
        promptPrefillMs = 30.0,
        nativeDecodeMs = 500.0,
        thermalStatus = 2,
        batteryTemperatureC = null,
        batteryCurrentUa = null,
        valid = valid,
        warmup = warmup,
        invalidReason = if (valid) null else "Thermal status became SEVERE or higher",
    )
}

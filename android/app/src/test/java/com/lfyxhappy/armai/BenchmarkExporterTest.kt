package com.lfyxhappy.armai

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
    }

    private fun measurement(
        threads: Int,
        tokensPerSecond: Double,
        ttftMs: Double,
        peakMemoryKb: Long,
        warmup: Boolean = false,
        valid: Boolean = true,
    ) = BenchmarkMeasurement(
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
        thermalStatus = 2,
        batteryTemperatureC = null,
        batteryCurrentUa = null,
        valid = valid,
        warmup = warmup,
        invalidReason = if (valid) null else "Thermal status became SEVERE or higher",
    )
}

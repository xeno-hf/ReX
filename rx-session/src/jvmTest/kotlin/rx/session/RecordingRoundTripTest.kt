package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.defs.Loga
import rx.protocol.ChannelTable
import java.nio.file.Files

/** Synthetic public fixtures: no owner rides or vendor definition files required. */
class RecordingRoundTripTest {
    @Test fun markAndClockAnchorUseTheInjectedSessionClock() {
        val def = ChannelDef("RPM", 26, 2, 1.0, 0.0, 20000.0, 0.0, "Para", "rpm", "Stage_1", 0, emptyMap())
        val layout = DecodedLayout(ChannelRegistry(null, listOf(def)), ChannelTable(listOf(ChannelTable.Entry(26, 2))))
        val dir = Files.createTempDirectory("rex-clock").toFile()
        var now = 845_510_000_000_000L // Android uptime domain, not process-relative monotonicNanos
        try {
            val rec = Recorder(kotlinx.io.files.Path(dir.path), layout, emptyMap(),
                wallClock = { 1_792_565_930_541L }, nanoTime = { now }, clockDomain = "test-uptime")
            rec.onRound(now, longArrayOf(1200))
            now += 2_000_000_000L
            rec.mark("gap check")
            rec.onRound(now, longArrayOf(1300))
            rec.close()
            val events = dir.resolve("events.jsonl").readText()
            assertTrue(events.contains("\"t_ns\":$now"), events)
            val meta = dir.resolve("meta.json").readText()
            assertTrue(meta.contains("\"domain\":\"test-uptime\""), meta)
            assertTrue(meta.contains("\"started_monotonic_ns\":845510000000000"), meta)
            assertEquals(listOf(0.0, 2000.0), Loga.parse(dir.resolve("decoded.loga").readBytes()).column("Time").toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun recordsAndReplaysWithAppendedPowerAndGps() {
        val def = ChannelDef("Vehicle_Speed", 51, 2, 10.0, 0.0, 300.0, 0.0, "Para", "km/h", "Stage_1", 1, emptyMap())
        val registry = ChannelRegistry(null, listOf(def))
        val layout = DecodedLayout(registry, ChannelTable(listOf(ChannelTable.Entry(51, 2))))
        val dir = Files.createTempDirectory("rex-roundtrip").toFile()
        try {
            Recorder(kotlinx.io.files.Path(dir.path), layout, emptyMap(), wallClock = { 0L }, extras = listOf(
                Recorder.ExtraColumn(-9002, "Phone_GPS", "Phone_GPS_Speed", 1) { 42.5 },
                Recorder.ExtraColumn(-9100, "ReX", "MGU_Power", 0) { 700.0 },
                Recorder.ExtraColumn(-9101, "ReX", "BMS_Power", 0) { -600.0 },
            )).apply {
                onRound(1_000_000L, longArrayOf(425))
                onRound(101_000_000L, longArrayOf(500))
                onRound(201_000_000L, longArrayOf(600))
                close()
            }
            val loga = Loga.parse(dir.resolve("decoded.loga").readBytes())
            assertEquals(listOf(-1, 51, -9002, -9100, -9101), loga.varIds)
            assertEquals(0, loga.rejectedRows)
            assertEquals(3, loga.rows.size)
            assertEquals(700.0, loga.column("MGU_Power")[0])
            assertEquals(-600.0, loga.column("BMS_Power")[0])
            val replay = LogaValueSource(loga, registry)
            assertEquals(425L, replay.raw(51, 2, 0))
            assertEquals(500L, replay.raw(51, 2, 100))
            assertTrue(loga.names.last() == "BMS_Power")
        } finally { dir.deleteRecursively() }
    }
}

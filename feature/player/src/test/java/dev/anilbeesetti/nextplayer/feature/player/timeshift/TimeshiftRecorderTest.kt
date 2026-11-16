// Ported from Overtime for Windows (fi.severi.overtime.player); keep the two in step.
package dev.anilbeesetti.nextplayer.feature.player.timeshift

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Exercises the recorder against a local HTTP server with a controllable clock. */
class TimeshiftRecorderTest {
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val now = AtomicLong(1_000_000L)
    private val recorders = mutableListOf<TimeshiftRecorder>()

    @Before
    fun setUp() {
        server = HttpServer.create(
            InetSocketAddress(
                "127.0.0.1",
                0,
            ),
            0,
        )
        server.start()
        dir = Files.createTempDirectory("timeshift-test").toFile()
    }

    @After
    fun tearDown() {
        recorders.forEach { it.stop() }
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun recorder(path: String, bufferMs: Long = 300_000, readyAfterMs: Long = 0) =
        TimeshiftRecorder(
            url(path),
            mapOf("User-Agent" to "test"),
            bufferMs,
            readyAfterMs,
            File(
                dir,
                path.trim('/'),
            ),
            clock = now::get,
        )
            .also {
                recorders += it
                it.start()
            }

    /** [count] TS packets whose payload bytes encode the packet index, so data can be checked. */
    private fun packets(from: Int, count: Int) = ByteArray(count * 188) { i ->
        if (i % 188 == 0) 0x47 else ((from + i / 188) and 0x7F).toByte()
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun recordsTsAndMapsTimeToOffsets() {
        // A stream that sends 10 packets, then (after the test advances the clock) 10 more, then hangs.
        val gate = java.util.concurrent.CountDownLatch(1)
        server.createContext("/live.ts") { ex ->
            ex.sendResponseHeaders(
                200,
                0,
            )
            ex.responseBody.write(
                packets(
                    0,
                    10,
                ),
            )
            ex.responseBody.flush()
            gate.await()
            ex.responseBody.write(
                packets(
                    10,
                    10,
                ),
            )
            ex.responseBody.flush()
            Thread.sleep(1_000)
            ex.close()
        }
        val rec = recorder("/live.ts")
        waitFor { rec.latestOffset == 1880L }
        assertIs<TimeshiftRecorder.Status.Ready>(rec.status.value)

        now.addAndGet(10_000)
        gate.countDown()
        waitFor { rec.latestOffset == 3760L }

        assertEquals(
            1_000_000L,
            rec.earliestWall,
        )
        assertEquals(
            1_010_000L,
            rec.liveWall,
        )
        // Arrival is assumed even between writes, so halfway in time is halfway in bytes (packet-aligned).
        assertEquals(
            2820L,
            rec.offsetAt(1_005_000L),
        )
        assertEquals(
            1_005_000L,
            rec.wallAt(2820L),
        )
        assertEquals(
            3760L,
            rec.offsetAt(1_010_000L),
        )
        assertEquals(
            0L,
            rec.offsetAt(0L),
        )

        val buffer = ByteArray(188 * 3)
        assertEquals(
            buffer.size,
            rec.read(
                1880L,
                buffer,
                buffer.size,
                0,
            ),
        )
        assertContentEquals(
            packets(
                10,
                3,
            ),
            buffer,
        )
        // Nothing past the live edge yet: a read times out empty.
        assertEquals(
            0,
            rec.read(
                3760L,
                buffer,
                buffer.size,
                50,
            ),
        )
    }

    @Test
    fun waitsForAKeyframeAndStartsOnIt() {
        val gates = List(3) { java.util.concurrent.CountDownLatch(1) }
        val slice = { TsPackets.pes(0x41) } // ordinary H.264 slice
        server.createContext("/video.ts") { ex ->
            ex.sendResponseHeaders(
                200,
                0,
            )
            val out = ex.responseBody
            // 0-4: tables and slices. 5-6 after 2 s. 7-8 tables again, 9 the keyframe, 10 a slice; 11 after another 1.5 s.
            listOf(
                TsPackets.pat(),
                TsPackets.pmt(0x1B),
                slice(),
                slice(),
                slice(),
            ).forEach(out::write)
            out.flush()
            gates[0].await()
            out.write(slice())
            out.write(slice())
            out.flush()
            gates[1].await()
            listOf(
                TsPackets.pat(),
                TsPackets.pmt(0x1B),
                TsPackets.pes(0x67),
                slice(),
            ).forEach(out::write)
            out.flush()
            gates[2].await()
            out.write(slice())
            out.flush()
            Thread.sleep(1_000)
            ex.close()
        }
        val rec = recorder(
            "/video.ts",
            readyAfterMs = 1_000,
        )
        waitFor { rec.latestOffset == 5 * 188L }
        now.addAndGet(2_000)
        gates[0].countDown()
        waitFor { rec.latestOffset == 7 * 188L }
        // Two seconds buffered, but no keyframe yet: starting now would be sound over black.
        assertIs<TimeshiftRecorder.Status.Starting>(rec.status.value)

        gates[1].countDown()
        waitFor { rec.latestOffset == 11 * 188L }
        assertIs<TimeshiftRecorder.Status.Starting>(
            rec.status.value,
            "keyframe just arrived, nothing after it yet",
        )

        now.addAndGet(1_500)
        gates[2].countDown()
        waitFor { rec.latestOffset == 12 * 188L }
        assertIs<TimeshiftRecorder.Status.Ready>(rec.status.value)

        // Playback starts at the PAT (packet 7) just before the keyframe, so VLC gets the tables it needs first.
        val keyframe = 7 * 188L
        // Any target after it starts there; seeking forward from before it lands there too.
        assertEquals(
            keyframe,
            rec.keyframeNear(
                11 * 188L,
                preferLater = false,
                limit = rec.latestOffset,
            ),
        )
        assertEquals(
            keyframe,
            rec.keyframeNear(
                3 * 188L,
                preferLater = true,
                limit = rec.latestOffset,
            ),
        )
        // Before the first keyframe, the only one there is.
        assertEquals(
            keyframe,
            rec.keyframeNear(
                3 * 188L,
                preferLater = false,
                limit = rec.latestOffset,
            ),
        )
    }

    @Test
    fun dropsDataOlderThanTheBuffer() {
        server.createContext("/long.ts") { ex ->
            ex.sendResponseHeaders(
                200,
                0,
            )
            // 12 bursts, each 5 s apart on the fake clock and big enough to fill a chunk.
            repeat(12) { burst ->
                ex.responseBody.write(
                    packets(
                        burst * 23_000,
                        23_000,
                    ),
                )
                ex.responseBody.flush()
                Thread.sleep(150)
            }
            Thread.sleep(1_000)
            ex.close()
        }
        val rec = recorder(
            "/long.ts",
            bufferMs = 20_000,
        )
        var seen = 0L
        repeat(12) { burst ->
            waitFor { rec.latestOffset >= (burst + 1) * 23_000L * 188 }
            seen = rec.latestOffset
            now.addAndGet(5_000)
        }
        // About 55 s recorded, 20 s kept (plus at most one chunk of slack).
        val kept = rec.liveWall - rec.earliestWall
        assertTrue(
            kept in 20_000..30_000,
            "kept $kept ms",
        )
        assertTrue(rec.earliestOffset > 0)
        assertEquals(
            TimeshiftRecorder.FELL_BEHIND,
            rec.read(
                0,
                ByteArray(188),
                188,
                0,
            ),
        )
        assertEquals(
            seen,
            rec.latestOffset,
        )
    }

    @Test
    fun flattensLiveHls() {
        val segment = { n: Int ->
            packets(
                n * 100,
                50,
            )
        }
        server.createContext("/hls/index.m3u8") { ex ->
            val body = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:7
                #EXTINF:6.0,
                seg7.ts
                #EXTINF:6.0,
                seg8.ts
                #EXTINF:6.0,
                seg9.ts
                #EXTINF:6.0,
                seg10.ts
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(
                200,
                body.size.toLong(),
            )
            ex.responseBody.write(body)
            ex.close()
        }
        server.createContext("/hls/") { ex ->
            val n = ex.requestURI.path.substringAfter("seg").substringBefore(".ts").toInt()
            val body = segment(n)
            ex.sendResponseHeaders(
                200,
                body.size.toLong(),
            )
            ex.responseBody.write(body)
            ex.close()
        }
        val rec = recorder("/hls/index.m3u8")
        // Starts three segments from the end: 8, 9, 10.
        waitFor { rec.latestOffset == 3L * 50 * 188 }
        val all = ByteArray(3 * 50 * 188)
        var read = 0
        while (read < all.size) {
            val chunk = ByteArray(all.size - read)
            val n = rec.read(
                read.toLong(),
                chunk,
                chunk.size,
                0,
            )
            check(n > 0) { "read returned $n at $read" }
            chunk.copyInto(
                all,
                read,
                0,
                n,
            )
            read += n
        }
        assertContentEquals(
            segment(8) + segment(9) + segment(10),
            all,
        )
        // 18 s of media ending at "now".
        assertEquals(
            now.get(),
            rec.liveWall,
        )
        assertEquals(
            now.get() - 18_000,
            rec.earliestWall,
        )
    }

    @Test
    fun reportsUnsupportedStreams() {
        server.createContext("/vod.m3u8") { ex ->
            val body = (
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" +
                    "#EXTINF:6.0,\na.ts\n#EXT-X-ENDLIST\n"
                ).toByteArray()
            ex.sendResponseHeaders(
                200,
                body.size.toLong(),
            )
            ex.responseBody.write(body)
            ex.close()
        }
        server.createContext("/movie.mp4") { ex ->
            val body = ByteArray(1024) { 1 }
            ex.sendResponseHeaders(
                200,
                body.size.toLong(),
            )
            ex.responseBody.write(body)
            ex.close()
        }
        val vod = recorder("/vod.m3u8")
        val mp4 = recorder("/movie.mp4")
        waitFor {
            vod.status.value != TimeshiftRecorder.Status.Starting &&
                mp4.status.value != TimeshiftRecorder.Status.Starting
        }
        assertIs<TimeshiftRecorder.Status.Unsupported>(vod.status.value)
        assertIs<TimeshiftRecorder.Status.Unsupported>(mp4.status.value)
    }
}

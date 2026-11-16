// Ported from Overtime for Windows (fi.severi.overtime.player); keep the two in step.
package dev.anilbeesetti.nextplayer.feature.player.timeshift

import org.junit.Test

/** Synthetic MPEG-TS packets: PAT → PMT (video on PID 0x100) → PES packets. */
internal object TsPackets {
    const val VIDEO_PID = 0x100
    private const val PMT_PID = 0x1000

    private fun packet(
        pid: Int,
        pusi: Boolean,
        payload: ByteArray,
        randomAccess: Boolean = false,
    ): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((if (pusi) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        var at = 4
        if (randomAccess) {
            p[3] = 0x30 // adaptation field + payload
            p[4] = 1 // adaptation field length
            p[5] = 0x40 // random_access_indicator
            at = 6
        } else {
            p[3] = 0x10 // payload only
        }
        payload.copyInto(
            p,
            at,
            0,
            minOf(
                payload.size,
                188 - at,
            ),
        )
        return p
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    fun pat(): ByteArray = packet(
        0,
        true,
        bytes(
            0x00, // pointer
            0x00,
            0xB0,
            13,
            0x00,
            0x01,
            0xC1,
            0x00,
            0x00, // table header, section length 13
            0x00,
            0x01,
            0xE0 or (PMT_PID shr 8),
            PMT_PID and 0xFF, // program 1 → PMT PID
            0,
            0,
            0,
            0, // CRC (unchecked)
        ),
    )

    /** PMT naming one video stream of [streamType] (0x1B H.264, 0x24 HEVC) on [VIDEO_PID]. */
    fun pmt(streamType: Int): ByteArray = packet(
        PMT_PID,
        true,
        bytes(
            0x00,
            0x02,
            0xB0,
            18,
            0x00,
            0x01,
            0xC1,
            0x00,
            0x00, // section length 18
            0xE0 or (VIDEO_PID shr 8),
            VIDEO_PID and 0xFF, // PCR PID
            0xF0,
            0x00, // no program info
            streamType,
            0xE0 or (VIDEO_PID shr 8),
            VIDEO_PID and 0xFF,
            0xF0,
            0x00,
            0,
            0,
            0,
            0,
        ),
    )

    /** A video PES start whose first NAL unit has [nalHeader] (after an access unit delimiter). */
    fun pes(nalHeader: Int, randomAccess: Boolean = false, audHeader: Int = 0x09): ByteArray =
        packet(
            VIDEO_PID,
            true,
            bytes(
                0x00,
                0x00,
                0x01,
                0xE0,
                0x00,
                0x00, // PES start, video stream id, length
                0x80,
                0x00,
                0x00, // flags, no optional header fields
                0x00,
                0x00,
                0x00,
                0x01,
                audHeader,
                0xF0, // access unit delimiter
                0x00,
                0x00,
                0x01,
                nalHeader,
                0x42,
            ),
            randomAccess,
        )
}

class TsKeyframeScannerTest {
    private fun scan(scanner: TsKeyframeScanner, packet: ByteArray) = scanner.isKeyframe(
        packet,
        0,
    )

    @Test
    fun findsH264Keyframes() {
        val scanner = TsKeyframeScanner()
        assertFalse(
            scan(
                scanner,
                TsPackets.pat(),
            ),
        )
        assertFalse(
            scan(
                scanner,
                TsPackets.pmt(0x1B),
            ),
        )
        assertTrue(scanner.knowsVideo)
        assertTrue(
            scan(
                scanner,
                TsPackets.pes(0x67),
            ),
            "SPS starts a keyframe",
        )
        assertTrue(
            scan(
                scanner,
                TsPackets.pes(0x65),
            ),
            "IDR slice",
        )
        assertFalse(
            scan(
                scanner,
                TsPackets.pes(0x41),
            ),
            "ordinary slice",
        )
    }

    @Test
    fun findsHevcKeyframes() {
        val scanner = TsKeyframeScanner()
        scan(
            scanner,
            TsPackets.pat(),
        )
        scan(
            scanner,
            TsPackets.pmt(0x24),
        )
        // HEVC NAL type sits in bits 1..6: VPS 32 → 0x40, IDR_W_RADL 19 → 0x26, TRAIL_R 1 → 0x02. AUD is 35 → 0x46.
        assertTrue(
            scan(
                scanner,
                TsPackets.pes(
                    0x40,
                    audHeader = 0x46,
                ),
            ),
        )
        assertTrue(
            scan(
                scanner,
                TsPackets.pes(
                    0x26,
                    audHeader = 0x46,
                ),
            ),
        )
        assertFalse(
            scan(
                scanner,
                TsPackets.pes(
                    0x02,
                    audHeader = 0x46,
                ),
            ),
        )
    }

    @Test
    fun trustsTheRandomAccessFlag() {
        val scanner = TsKeyframeScanner()
        scan(
            scanner,
            TsPackets.pat(),
        )
        scan(
            scanner,
            TsPackets.pmt(0x1B),
        )
        assertTrue(
            scan(
                scanner,
                TsPackets.pes(
                    0x41,
                    randomAccess = true,
                ),
            ),
        )
    }

    @Test
    fun knowsNothingWithoutTables() {
        val scanner = TsKeyframeScanner()
        assertFalse(
            scan(
                scanner,
                TsPackets.pes(0x67),
            ),
        )
        assertFalse(scanner.knowsVideo)
    }

    @Test
    fun ignoresOtherStreams() {
        val scanner = TsKeyframeScanner()
        scan(
            scanner,
            TsPackets.pat(),
        )
        scan(
            scanner,
            TsPackets.pmt(0x0F),
        ) // AAC audio only
        assertFalse(scanner.knowsVideo)
        assertEquals(
            false,
            scan(
                scanner,
                TsPackets.pes(0x67),
            ),
        )
    }
}

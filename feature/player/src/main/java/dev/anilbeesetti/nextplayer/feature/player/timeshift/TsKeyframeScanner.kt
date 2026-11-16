// Ported from Overtime for Windows (fi.severi.overtime.player); keep the two in step.
package dev.anilbeesetti.nextplayer.feature.player.timeshift

/**
 * Spots where video keyframes start in an MPEG-TS stream, packet by packet.
 *
 * A decoder can only start on a keyframe (H.264/HEVC IDR or IRAP, MPEG-2 sequence header); started
 * anywhere else, VLC plays sound over a black picture until the next one, often several seconds.
 * Knowing where keyframes are lets playback start exactly on one.
 *
 * Reads the PAT and PMT to find the first video stream and its codec, then checks each packet that
 * starts a PES on that stream: the random-access flag if the muxer sets it, otherwise the first NAL
 * units (or MPEG-2 start codes) in the packet. Sections and parameter sets are assumed to start inside
 * one packet, which is how broadcast muxers write them.
 */
internal class TsKeyframeScanner {
    private enum class Codec { H264, HEVC, MPEG2 }

    private val pmtPids = HashSet<Int>()
    private var videoPid = -1
    private var codec: Codec? = null

    /** True once the PMT named a video stream this scanner understands. */
    val knowsVideo: Boolean get() = codec != null

    /** Whether the packet at [at] starts a PAT, the table a decoder needs before anything else. */
    fun isPat(data: ByteArray, at: Int): Boolean =
        data[at] == SYNC && data[at + 1].toInt() and 0x5F == 0x40 && data[at + 2].toInt() == 0

    /** Whether the 188-byte packet at [at] starts a video keyframe. */
    fun isKeyframe(data: ByteArray, at: Int): Boolean {
        if (data[at] != SYNC) return false
        val b1 = data[at + 1].toInt() and 0xFF
        val pusi = b1 and 0x40 != 0
        val pid = ((b1 and 0x1F) shl 8) or (data[at + 2].toInt() and 0xFF)
        val control = (data[at + 3].toInt() shr 4) and 0x3
        if (control and 0x1 == 0) return false // no payload
        var payload = at + 4
        var randomAccess = false
        if (control and 0x2 != 0) {
            val afLength = data[at + 4].toInt() and 0xFF
            if (afLength > 0) randomAccess = data[at + 5].toInt() and 0x40 != 0
            payload += 1 + afLength
        }
        val end = at + PACKET
        if (payload >= end || !pusi) return false

        when {
            pid == 0 -> readPat(
                data,
                payload,
                end,
            )

            pid in pmtPids -> readPmt(
                data,
                payload,
                end,
            )

            pid == videoPid -> return randomAccess || startsWithKeyframe(
                data,
                payload,
                end,
            )
        }
        return false
    }

    private fun readPat(data: ByteArray, payload: Int, end: Int) {
        val table = payload + 1 + (data[payload].toInt() and 0xFF)
        if (table + 8 > end || data[table].toInt() != 0x00) return
        val sectionEnd = minOf(
            table + 3 + sectionLength(
                data,
                table,
            ) - 4,
            end,
        ) // minus CRC
        var i = table + 8
        while (i + 4 <= sectionEnd) {
            val program = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val pid = ((data[i + 2].toInt() and 0x1F) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (program != 0) pmtPids += pid
            i += 4
        }
    }

    private fun readPmt(data: ByteArray, payload: Int, end: Int) {
        if (codec != null) return
        val table = payload + 1 + (data[payload].toInt() and 0xFF)
        if (table + 12 > end || data[table].toInt() != 0x02) return
        val sectionEnd = minOf(
            table + 3 + sectionLength(
                data,
                table,
            ) - 4,
            end,
        )
        val programInfo =
            ((data[table + 10].toInt() and 0x0F) shl 8) or (data[table + 11].toInt() and 0xFF)
        var i = table + 12 + programInfo
        while (i + 5 <= sectionEnd) {
            val type = data[i].toInt() and 0xFF
            val pid = ((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF)
            val infoLength =
                ((data[i + 3].toInt() and 0x0F) shl 8) or (data[i + 4].toInt() and 0xFF)
            val found = when (type) {
                0x1B -> Codec.H264
                0x24 -> Codec.HEVC
                0x01, 0x02 -> Codec.MPEG2
                else -> null
            }
            if (found != null) {
                codec = found
                videoPid = pid
                return
            }
            i += 5 + infoLength
        }
    }

    /** Looks past the PES header for the access unit's first NAL units or start codes. */
    private fun startsWithKeyframe(data: ByteArray, payload: Int, end: Int): Boolean {
        if (payload + 9 > end || data[payload].toInt() != 0 || data[payload + 1].toInt() != 0 ||
            data[payload + 2].toInt() != 1
        ) {
            return false
        }
        var i = payload + 9 + (data[payload + 8].toInt() and 0xFF)
        while (i + 3 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val header = data[i + 3].toInt() and 0xFF
                when (codec) {
                    Codec.H264 -> when (header and 0x1F) {
                        5, 7 -> return true

                        // IDR slice, SPS
                        1 -> return false // ordinary slice: not a keyframe
                    }

                    Codec.HEVC -> when ((header shr 1) and 0x3F) {
                        in 16..21, 32, 33 -> return true

                        // IRAP slice, VPS, SPS
                        in 0..9 -> return false
                    }

                    Codec.MPEG2 -> when (header) {
                        0xB3 -> return true

                        // sequence header
                        0x00 -> return false // picture without a new sequence
                    }

                    null -> return false
                }
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    private fun sectionLength(data: ByteArray, table: Int) =
        ((data[table + 1].toInt() and 0x0F) shl 8) or (data[table + 2].toInt() and 0xFF)

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
    }
}

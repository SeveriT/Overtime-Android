package dev.anilbeesetti.nextplayer.feature.player.timeshift

import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

/**
 * Reads the live recording (always MPEG-TS) with a TS extractor whose clock restarts at 0 whenever
 * reading restarts.
 *
 * A rewind reopens the recording at another keyframe and seeks the player to 0. TsExtractor only
 * resets its timestamp offset on a seek when the old offset came from a non-zero position, so it
 * kept counting from the moment the channel was first opened: the picture after a rewind carried
 * timestamps tens of seconds ahead of the player's clock, the audio output jumped the clock forward
 * to match, and the bar snapped back to live. Resetting the adjuster on every seek keeps the
 * player's position counting from the new start, as [TimeshiftPlayer] expects.
 *
 * Otherwise built like DefaultExtractorsFactory builds its TsExtractor (single PMT, subtitles
 * parsed during extraction).
 */
@UnstableApi
class TimeshiftExtractorsFactory : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> {
        val adjuster = TimestampAdjuster(0)
        val ts = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            0,
            DefaultSubtitleParserFactory(),
            adjuster,
            DefaultTsPayloadReaderFactory(0),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        return arrayOf(
            object : Extractor by ts {
                override fun seek(position: Long, timeUs: Long) {
                    ts.seek(
                        position,
                        timeUs,
                    )
                    adjuster.reset(timeUs)
                }
            },
        )
    }
}

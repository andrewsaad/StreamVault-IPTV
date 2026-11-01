package com.streamvault.surround

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

@UnstableApi
class UpmixAudioSinkTest {

    private val fake = FakeAudioSink()
    private val encoders = mutableListOf<FakeEncoder>()
    private var enabled = true
    private var offsetUs = 0L

    private fun sink(available: Boolean = true) = UpmixAudioSink(
        delegate = fake,
        enabled = { enabled },
        encoderFactory = { FakeEncoder(blockFrames = 2).also { encoders += it } },
        encoderAvailable = { available },
        latencyOffsetUs = { offsetUs }
    )

    private fun pcm(channels: Int = 2, rate: Int = 48_000, encoding: Int = C.ENCODING_PCM_16BIT): Format =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(channels).setSampleRate(rate)
            .setPcmEncoding(encoding).build()

    private fun configure(s: UpmixAudioSink, f: Format) = s.configure(AudioSink.AudioSinkConfig.Builder(f).build())

    private fun frames(n: Int): ByteBuffer =
        ByteBuffer.allocate(n * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(n * 2) { putShort(1000) }
            flip()
        }

    @Test
    fun stereoPcmIsSentAsAc3() {
        val s = sink()
        configure(s, pcm())
        assertThat(s.isUpmixing).isTrue()
        assertThat(fake.configured.last().sampleMimeType).isEqualTo(MimeTypes.AUDIO_AC3)
        assertThat(fake.configured.last().channelCount).isEqualTo(6)

        assertThat(s.handleBuffer(frames(5), 0L, 1)).isTrue()
        // 5 frames -> 2 full blocks of 2 written, 1 frame waiting for the next buffer.
        assertThat(fake.written.map { it.first }).containsExactly(0L, 2 * 1_000_000L / 48_000).inOrder()
    }

    @Test
    fun passesThroughWhenDisabledMultichannelOddRateOrNoEncoder() {
        enabled = false
        configure(sink(), pcm())
        enabled = true
        configure(sink(), pcm(channels = 6))
        configure(sink(), pcm(rate = 96_000))
        configure(sink(), pcm(encoding = C.ENCODING_PCM_24BIT))
        configure(sink(available = false), pcm())
        assertThat(fake.configured.map { it.sampleMimeType }).containsExactlyElementsIn(List(5) { MimeTypes.AUDIO_RAW })
        assertThat(encoders).isEmpty()
    }

    @Test
    fun passesThroughWhenOutputCannotTakeAc3() {
        fake.ac3Supported = false
        val s = sink()
        configure(s, pcm())
        assertThat(s.isUpmixing).isFalse()
        val input = frames(2)
        assertThat(s.handleBuffer(input, 7L, 1)).isTrue()
        assertThat(fake.written.single().first).isEqualTo(7L)
        assertThat(fake.written.single().second.size).isEqualTo(8)   // raw PCM, untouched
    }

    @Test
    fun fullOutputKeepsFrameAndResumesSameBuffer() {
        val s = sink()
        configure(s, pcm())
        fake.full = true
        val input = frames(4)
        assertThat(s.handleBuffer(input, 0L, 1)).isFalse()          // first block encoded, not accepted
        assertThat(s.hasPendingData()).isTrue()
        fake.full = false
        assertThat(s.handleBuffer(input, 0L, 1)).isTrue()
        assertThat(fake.written.map { it.first }).containsExactly(0L, 2 * 1_000_000L / 48_000).inOrder()
    }

    @Test
    fun flushDropsPartialBlock() {
        val s = sink()
        configure(s, pcm())
        s.handleBuffer(frames(1), 0L, 1)
        s.flush()
        assertThat(fake.flushed).isEqualTo(1)
        s.handleBuffer(frames(2), 50_000L, 1)
        assertThat(fake.written.single().first).isEqualTo(50_000L)
    }

    @Test
    fun endOfStreamFlushesPartialBlock() {
        val s = sink()
        configure(s, pcm())
        s.handleBuffer(frames(1), 3_000L, 1)
        s.playToEndOfStream()
        assertThat(fake.written.single().first).isEqualTo(3_000L)
        assertThat(s.isEnded()).isTrue()
    }

    @Test
    fun encoderReusedForSameRateAndClosedOnRelease() {
        val s = sink()
        configure(s, pcm())
        configure(s, pcm())
        configure(s, pcm(rate = 44_100))
        assertThat(encoders).hasSize(2)
        assertThat(encoders[0].closed).isTrue()
        s.release()
        assertThat(encoders[1].closed).isTrue()
        assertThat(fake.released).isTrue()
    }

    @Test
    fun volumeAndMuteApplyBeforeEncoding() {
        val s = sink()
        s.setVolume(0.5f)                                   // set before configure: carried into the pipeline
        configure(s, pcm())
        s.handleBuffer(frames(2), 0L, 1)
        s.setVolume(0f)                                     // mute while playing
        s.handleBuffer(frames(2), 0L, 1)
        val fl = encoders.single().blocks.map { it[0] }
        assertThat(fl[0]).isWithin(1e-6f).of(1000 / 32768f * 0.5f)
        assertThat(fl[1]).isEqualTo(0f)
    }

    @Test
    fun latencyOffsetDelaysPositionOnlyWhileUpmixing() {
        offsetUs = 200_000L
        fake.positionUs = 1_000_000L
        val s = sink()
        configure(s, pcm(channels = 6))                      // passthrough: position untouched
        assertThat(s.getCurrentPositionUs(false)).isEqualTo(1_000_000L)
        configure(s, pcm())
        assertThat(s.getCurrentPositionUs(false)).isEqualTo(800_000L)
        offsetUs = 300_000L                                  // read on configure, not on every position query
        assertThat(s.getCurrentPositionUs(false)).isEqualTo(800_000L)
        fake.positionUs = AudioSink.CURRENT_POSITION_NOT_SET
        assertThat(s.getCurrentPositionUs(false)).isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET)
    }

    @Test
    fun ac3FormatDescribes51() {
        val f = UpmixAudioSink.ac3Format(44_100)
        assertThat(f.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AC3)
        assertThat(f.channelCount).isEqualTo(6)
        assertThat(f.sampleRate).isEqualTo(44_100)
    }
}

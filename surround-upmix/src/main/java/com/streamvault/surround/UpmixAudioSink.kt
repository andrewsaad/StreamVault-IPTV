package com.streamvault.surround

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * Wraps an [AudioSink]: when [enabled] and the input is stereo PCM that the output can take as AC-3 passthrough,
 * upmixes it to 5.1 and hands the delegate AC-3 frames instead. Anything else passes through untouched.
 */
@UnstableApi
class UpmixAudioSink(
    private val delegate: AudioSink,
    private val enabled: () -> Boolean,
    private val encoderFactory: (sampleRate: Int) -> BlockEncoder = { Ac3Encoder(it) },
    private val encoderAvailable: () -> Boolean = { Ac3Encoder.isAvailable }
) : AudioSink by delegate {

    private var pipeline: UpmixPipeline? = null
    private var encoder: BlockEncoder? = null
    private var encoderRate = 0
    private var lastInput: ByteBuffer? = null
    private var lastInputTimeUs = 0L
    private var lastInputStart = 0
    private var volume = 1f

    /** True while stereo input is being upmixed (for diagnostics and tests). */
    val isUpmixing: Boolean get() = pipeline != null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configure(AudioSink.AudioSinkConfig.Builder(inputFormat)
            .setPreferredBufferSizeOverride(specifiedBufferSize)
            .setOutputChannelMapping(outputChannels?.let { com.google.common.primitives.ImmutableIntArray.copyOf(it) })
            .build())
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        val ac3 = ac3Format(format.sampleRate)
        if (!shouldUpmix(format) || !delegate.supportsFormat(ac3)) {
            stopUpmixing()
            delegate.configure(audioSinkConfig)
            return
        }
        delegate.configure(AudioSink.AudioSinkConfig.Builder(ac3)
            .setTimeline(audioSinkConfig.timeline)
            .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
            .build())
        if (encoderRate != format.sampleRate) {
            encoder?.close()
            encoder = encoderFactory(format.sampleRate)
            encoderRate = format.sampleRate
        }
        pipeline = UpmixPipeline(requireNotNull(encoder), format.sampleRate, format.pcmEncoding == C.ENCODING_PCM_FLOAT)
            .also { it.gain = volume }
        lastInput = null
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        pipeline?.gain = volume
        delegate.setVolume(volume)
    }

    private fun shouldUpmix(format: Format): Boolean =
        enabled() &&
            format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.channelCount == 2 &&
            format.sampleRate in Ac3Encoder.SUPPORTED_SAMPLE_RATES &&
            (format.pcmEncoding == C.ENCODING_PCM_16BIT || format.pcmEncoding == C.ENCODING_PCM_FLOAT) &&
            encoderAvailable()

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val p = pipeline ?: return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // The renderer calls again with the same buffer until it is consumed; remember where it started.
        if (buffer !== lastInput || presentationTimeUs != lastInputTimeUs) {
            lastInput = buffer
            lastInputTimeUs = presentationTimeUs
            lastInputStart = buffer.position()
        }
        while (true) {
            if (!pushPending(p)) return false
            if (!buffer.hasRemaining()) {
                lastInput = null
                return true
            }
            p.consume(buffer, presentationTimeUs, lastInputStart)
        }
    }

    /** Hands the pending AC-3 frame to the delegate; false while it can't take it yet. */
    private fun pushPending(p: UpmixPipeline): Boolean {
        val frame = p.pendingFrame ?: return true
        if (!delegate.handleBuffer(frame, p.pendingTimeUs, 1) || frame.hasRemaining()) return false
        p.takeFrame()
        return true
    }

    override fun playToEndOfStream() {
        pipeline?.let { p ->
            if (p.pendingFrame == null) p.finish()
            pushPending(p)
        }
        delegate.playToEndOfStream()
    }

    override fun hasPendingData(): Boolean = delegate.hasPendingData() || pipeline?.pendingFrame != null

    override fun isEnded(): Boolean = delegate.isEnded() && pipeline?.pendingFrame == null

    override fun flush() {
        pipeline?.reset()
        lastInput = null
        delegate.flush()
    }

    override fun reset() {
        pipeline?.reset()
        lastInput = null
        delegate.reset()
    }

    override fun release() {
        stopUpmixing()
        encoder?.close()
        encoder = null
        encoderRate = 0
        delegate.release()
    }

    private fun stopUpmixing() {
        pipeline = null
        lastInput = null
    }

    companion object {
        fun ac3Format(sampleRate: Int): Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(StereoUpmixer.OUTPUT_CHANNELS)
            .setSampleRate(sampleRate)
            .setAverageBitrate(Ac3Encoder.DEFAULT_BIT_RATE)
            .build()
    }
}

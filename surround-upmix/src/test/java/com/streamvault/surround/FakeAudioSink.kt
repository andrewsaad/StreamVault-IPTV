package com.streamvault.surround

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/** Records what it is given; accepts AC-3 only when [ac3Supported]; refuses buffers while [full]. */
@UnstableApi
class FakeAudioSink(var ac3Supported: Boolean = true) : AudioSink {
    val configured = mutableListOf<Format>()
    val written = mutableListOf<Pair<Long, ByteArray>>()
    var full = false
    var flushed = 0
    var released = false
    var positionUs = 0L

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        configured += audioSinkConfig.format
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int = when {
        format.sampleMimeType == MimeTypes.AUDIO_AC3 && !ac3Supported -> AudioSink.SINK_FORMAT_UNSUPPORTED
        else -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (full) return false
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        written += presentationTimeUs to bytes
        return true
    }

    override fun flush() { flushed++ }
    override fun release() { released = true }
    override fun setListener(listener: AudioSink.Listener) = Unit
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = positionUs
    override fun play() = Unit
    override fun handleDiscontinuity() = Unit
    override fun playToEndOfStream() = Unit
    override fun isEnded(): Boolean = true
    override fun hasPendingData(): Boolean = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = Unit
    override fun getAudioAttributes(): AudioAttributes? = null
    override fun setAudioSessionId(audioSessionId: Int) = Unit
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit
    override fun getAudioTrackBufferSizeUs(): Long = 0L
    override fun enableTunnelingV21() = Unit
    override fun disableTunneling() = Unit
    override fun setVolume(volume: Float) = Unit
    override fun pause() = Unit
    override fun reset() = Unit
}

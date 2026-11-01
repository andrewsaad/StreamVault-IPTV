package com.streamvault.surround

/**
 * Stereo to 5.1 (FL FR FC LFE SL SR), the layout the AC-3 encoder takes. Front and surround pairs both carry the
 * full left/right signal, so every speaker plays the programme (like Kodi's stereo upmix); the centre gets the
 * mono sum for dialogue. LFE stays silent: the soundbar's bass management feeds the subwoofer from all channels.
 */
object StereoUpmixer {
    const val OUTPUT_CHANNELS = 6
    private const val CENTER_GAIN = 0.5f

    /** Upmixes [frames] interleaved stereo frames from [stereo] into interleaved 5.1 [out]. */
    fun upmix(stereo: FloatArray, frames: Int, out: FloatArray) {
        require(stereo.size >= frames * 2) { "stereo buffer too small" }
        require(out.size >= frames * OUTPUT_CHANNELS) { "output buffer too small" }
        for (i in 0 until frames) {
            val l = stereo[2 * i]
            val r = stereo[2 * i + 1]
            val o = i * OUTPUT_CHANNELS
            out[o] = l
            out[o + 1] = r
            out[o + 2] = (l + r) * CENTER_GAIN
            out[o + 3] = 0f
            out[o + 4] = l
            out[o + 5] = r
        }
    }
}

package com.streamvault.domain.model

enum class AudioOutputPreference {
    AUTO,
    PREFER_PASSTHROUGH,
    DISABLE_PASSTHROUGH,
    /** Stereo is upmixed to 5.1 and sent as Dolby Digital, so surround speakers play stereo content too. */
    SURROUND_UPMIX;

    companion object {
        /** Used until the user picks one. Upmix only acts on stereo when the output takes Dolby Digital. */
        val DEFAULT = SURROUND_UPMIX
    }
}

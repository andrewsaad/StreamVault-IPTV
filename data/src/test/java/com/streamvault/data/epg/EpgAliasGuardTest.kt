package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * F21: guards the hand-kept loose-match alias table. Each alias merges two names into one guide
 * key, so a careless entry can silently give one channel another channel's programmes.
 * EpgUsSportsAliasTest covers how the aliases match; this covers the table itself.
 */
class EpgAliasGuardTest {

    private val aliases = EpgNameNormalizer.canonicalAliases

    @Test
    fun `aliasCount_staysAtTheReviewedSize`() {
        assertWithMessage(
            "A new alias needs the provider and guide names that justify it in the KDoc of " +
                "EpgNameNormalizer.canonicalAliases; then raise this limit in the same change.",
        ).that(aliases.size).isAtMost(MAX_ALIASES)
    }

    @Test
    fun `noAliasPointsAtAnotherAlias`() {
        // A chain would make the result depend on lookup order; the table is applied once.
        assertThat(aliases.values.toSet().intersect(aliases.keys)).isEmpty()
    }

    @Test
    fun `everyAliasKey_isALooseKey_soItCanMatchAtAll`() {
        // Keys are compared with the joined loose key, which never holds spaces, capitals or "sports".
        aliases.keys.forEach { key ->
            assertWithMessage("alias key '$key'").that(key).matches("[a-z0-9]+")
            assertWithMessage("alias key '$key'").that(key).doesNotContain("sports")
        }
    }

    @Test
    fun `deliberatelyDistinctChannels_areNeverAliased`() {
        // Named in the KDoc as separate channels with their own programmes.
        val distinct = setOf("espn2", "espnu", "espndeportes", "foxsoccerplus", "onsportmax")
        assertThat(aliases.keys.intersect(distinct)).isEmpty()
        assertThat(aliases.values.toSet().intersect(distinct)).isEmpty()
        distinct.forEach { key ->
            assertWithMessage("loose key of '$key'").that(EpgNameNormalizer.normalizeLoose(key)).isEqualTo(key)
        }
    }

    private companion object {
        const val MAX_ALIASES = 10
    }
}

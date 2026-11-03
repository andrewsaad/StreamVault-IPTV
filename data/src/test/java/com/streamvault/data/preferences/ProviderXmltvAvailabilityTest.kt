package com.streamvault.data.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderXmltvAvailabilityTest {
    @Test
    fun roundTripsOnlyForTheSameEndpoint() {
        val encoded = encodeProviderXmltvUnavailable(1_234L, "abc123")

        assertThat(decodeProviderXmltvUnavailable(encoded, "abc123")).isEqualTo(1_234L)
        assertThat(decodeProviderXmltvUnavailable(encoded, "other")).isNull()
    }

    @Test
    fun ignoresMissingOrCorruptValues() {
        assertThat(decodeProviderXmltvUnavailable(null, "abc123")).isNull()
        assertThat(decodeProviderXmltvUnavailable("", "abc123")).isNull()
        assertThat(decodeProviderXmltvUnavailable("1234", "abc123")).isNull()
        assertThat(decodeProviderXmltvUnavailable("soon|abc123", "abc123")).isNull()
        assertThat(decodeProviderXmltvUnavailable("0|abc123", "abc123")).isNull()
    }
}

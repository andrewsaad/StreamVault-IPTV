package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ChannelHealthRecord
import com.streamvault.domain.model.ChannelHealthRules
import com.streamvault.domain.model.ChannelHealthStatus
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class ChannelHealthTrackerTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val noon = 12L * 60L * 60L * 1000L

    private fun apply(previous: ChannelHealthRecord?, status: ChannelHealthStatus, at: Long, zone: ZoneId = utc) =
        ChannelHealthTracker.apply(previous, channelId = 7L, status = status, checkedAt = at, zone = zone)

    @Test
    fun `empty on day one counts one failed day, again on the same day still one, day two is a suggestion`() {
        val dayOne = apply(null, ChannelHealthStatus.EMPTY, noon)!!
        assertThat(dayOne.failedDays).isEqualTo(1)
        assertThat(ChannelHealthRules.isSuggestion(dayOne)).isFalse()

        val sameDay = apply(dayOne, ChannelHealthStatus.EMPTY, noon + 60_000L)!!
        assertThat(sameDay.failedDays).isEqualTo(1)
        assertThat(ChannelHealthRules.isSuggestion(sameDay)).isFalse()

        val dayTwo = apply(sameDay, ChannelHealthStatus.EMPTY, noon + DAY_MS)!!
        assertThat(dayTwo.failedDays).isEqualTo(2)
        assertThat(ChannelHealthRules.isSuggestion(dayTwo)).isTrue()
    }

    @Test
    fun `empty and dead on separate days both count`() {
        val dayOne = apply(null, ChannelHealthStatus.EMPTY, noon)!!

        val dayTwo = apply(dayOne, ChannelHealthStatus.DEAD, noon + DAY_MS)!!

        assertThat(dayTwo.status).isEqualTo(ChannelHealthStatus.DEAD)
        assertThat(dayTwo.failedDays).isEqualTo(2)
    }

    @Test
    fun `an OK probe resets failed days to zero and the next failure starts over`() {
        val failed = apply(apply(null, ChannelHealthStatus.DEAD, noon), ChannelHealthStatus.DEAD, noon + DAY_MS)!!
        assertThat(failed.failedDays).isEqualTo(2)

        val ok = apply(failed, ChannelHealthStatus.OK, noon + 2 * DAY_MS)!!
        assertThat(ok.failedDays).isEqualTo(0)
        assertThat(ok.status).isEqualTo(ChannelHealthStatus.OK)
        assertThat(ChannelHealthRules.isSuggestion(ok)).isFalse()

        val failedAgain = apply(ok, ChannelHealthStatus.EMPTY, noon + 3 * DAY_MS)!!
        assertThat(failedAgain.failedDays).isEqualTo(1)
    }

    @Test
    fun `an UNCLEAR probe leaves the record unchanged`() {
        val failed = apply(null, ChannelHealthStatus.EMPTY, noon)!!

        assertThat(apply(failed, ChannelHealthStatus.UNCLEAR, noon + DAY_MS)).isSameInstanceAs(failed)
        assertThat(apply(null, ChannelHealthStatus.UNCLEAR, noon)).isNull()
    }

    @Test
    fun `an UNCLEAR day in between does not count as a failed day`() {
        val dayOne = apply(null, ChannelHealthStatus.EMPTY, noon)
        val afterUnclear = apply(dayOne, ChannelHealthStatus.UNCLEAR, noon + DAY_MS)

        val dayThree = apply(afterUnclear, ChannelHealthStatus.EMPTY, noon + 2 * DAY_MS)!!

        assertThat(dayThree.failedDays).isEqualTo(2)
    }

    @Test
    fun `days are local calendar days, not UTC days`() {
        val pacific = ZoneOffset.ofHours(-8)
        // 23:00 UTC and 01:00 UTC the next day are 15:00 and 17:00 on the same Pacific day.
        val first = apply(null, ChannelHealthStatus.EMPTY, 23L * 3_600_000L, pacific)!!

        val second = apply(first, ChannelHealthStatus.EMPTY, DAY_MS + 3_600_000L, pacific)!!

        assertThat(second.failedDays).isEqualTo(1)
    }

    @Test
    fun `a clock that moves backwards does not add a day`() {
        val later = apply(null, ChannelHealthStatus.EMPTY, noon + DAY_MS)!!

        val earlier = apply(later, ChannelHealthStatus.EMPTY, noon)!!

        assertThat(earlier.failedDays).isEqualTo(1)
    }
}

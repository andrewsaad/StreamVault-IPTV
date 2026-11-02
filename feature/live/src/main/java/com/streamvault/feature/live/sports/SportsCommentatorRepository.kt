package com.streamvault.feature.live.sports

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun interface SportsCommentatorPageFetcher {
    /** The btolat page for [date], or null on any failure. */
    suspend fun fetch(date: LocalDate): String?
}

internal data class StoredSportsCommentators(
    val date: LocalDate,
    val fetchedAt: Instant,
    val entries: List<SportsCommentatorEntry>,
)

internal interface SportsCommentatorStore {
    fun load(): StoredSportsCommentators?
    fun save(value: StoredSportsCommentators)
}

/**
 * Today's beIN commentators from btolat.com. Fetched at most once an hour and only when the
 * Sports hub asks; a failed fetch also waits out the hour and keeps whatever was shown before.
 */
@Singleton
internal class SportsCommentatorRepository(
    private val fetcher: SportsCommentatorPageFetcher,
    private val store: SportsCommentatorStore,
    private val clock: Clock,
) {
    @Inject constructor(
        okHttpClient: OkHttpClient,
        @ApplicationContext context: Context,
    ) : this(
        BtolatCommentatorPageFetcher(okHttpClient),
        SharedPreferencesSportsCommentatorStore(context),
        Clock.systemUTC(),
    )

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<SportsCommentatorEntry>>(emptyList())
    val entries: StateFlow<List<SportsCommentatorEntry>> = _entries.asStateFlow()
    private var restored = false
    private var attemptedDate: LocalDate? = null
    private var attemptedAt: Instant? = null

    /** Never throws; a concurrent caller simply returns while another refresh is running. */
    suspend fun refreshIfStale() {
        if (!mutex.tryLock()) return
        try {
            val now = clock.instant()
            val today = now.atZone(BtolatCommentatorParser.SOURCE_ZONE).toLocalDate()
            if (!restored) {
                restored = true
                runCatching(store::load).getOrNull()?.takeIf { it.date == today }?.let { stored ->
                    _entries.value = stored.entries
                    attemptedDate = stored.date
                    attemptedAt = stored.fetchedAt
                }
            }
            val lastAttempt = attemptedAt
            if (attemptedDate == today && lastAttempt != null &&
                Duration.between(lastAttempt, now) < REFRESH_INTERVAL && !lastAttempt.isAfter(now)
            ) return
            attemptedDate = today
            attemptedAt = now
            val html = fetcher.fetch(today) ?: return
            val parsed = withKnownKickoffs(BtolatCommentatorParser.parse(html, today, now), _entries.value, today, now)
            _entries.value = parsed
            runCatching { store.save(StoredSportsCommentators(today, now, parsed)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Commentators are decoration; any failure just leaves them hidden.
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        val REFRESH_INTERVAL: Duration = Duration.ofHours(1)

        /**
         * btolat hides the kick-off of a match in play. When an earlier read today saw the same
         * channel and commentator with a kick-off that has passed, keep that exact entry instead.
         */
        internal fun withKnownKickoffs(
            parsed: List<SportsCommentatorEntry>,
            previous: List<SportsCommentatorEntry>,
            today: LocalDate,
            now: Instant,
        ): List<SportsCommentatorEntry> {
            if (parsed.none(SportsCommentatorEntry::live)) return parsed
            val known = previous.filter { entry ->
                !entry.live && !entry.kickoff.isAfter(now) &&
                    entry.kickoff.atZone(BtolatCommentatorParser.SOURCE_ZONE).toLocalDate() == today
            }
            return parsed.map { entry ->
                if (!entry.live) return@map entry
                known.filter { it.beinChannel == entry.beinChannel && it.commentator == entry.commentator }
                    .maxByOrNull(SportsCommentatorEntry::kickoff) ?: entry
            }.distinct()
        }
    }
}

internal class BtolatCommentatorPageFetcher(okHttpClient: OkHttpClient) : SportsCommentatorPageFetcher {
    private val client = okHttpClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(date: LocalDate): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.btolat.com/match-score".toHttpUrl().newBuilder()
                .addQueryParameter("d", "${date.monthValue}.${date.dayOfMonth}.${date.year}")
                .build()
            val request = Request.Builder().url(url)
                // btolat sits behind Cloudflare, which only lets browser-like requests through.
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body ?: return@use null
                if (!response.isSuccessful || body.contentLength() > MAX_PAGE_BYTES) return@use null
                val source = body.source()
                if (source.request(MAX_PAGE_BYTES + 1)) return@use null
                source.buffer.readUtf8()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_PAGE_BYTES = 2L * 1024 * 1024
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}

/** Keeps only the latest day so a process restart within the hour does not refetch. */
internal class SharedPreferencesSportsCommentatorStore(context: Context) : SportsCommentatorStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredSportsCommentators? {
        val date = preferences.getString(KEY_DATE, null)?.let(LocalDate::parse) ?: return null
        val fetchedAt = Instant.ofEpochMilli(preferences.getLong(KEY_FETCHED_AT, 0L))
        val entries = preferences.getString(KEY_ENTRIES, null).orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            // Three fields before live entries existed; the fourth is "1" for a match seen in play.
            if (parts.size != 3 && parts.size != 4) return@mapNotNull null
            SportsCommentatorEntry(
                beinChannel = parts[0].toIntOrNull() ?: return@mapNotNull null,
                kickoff = Instant.ofEpochMilli(parts[1].toLongOrNull() ?: return@mapNotNull null),
                commentator = parts[2].takeIf(String::isNotBlank) ?: return@mapNotNull null,
                live = parts.getOrNull(3) == "1",
            )
        }.toList()
        return StoredSportsCommentators(date, fetchedAt, entries)
    }

    override fun save(value: StoredSportsCommentators) {
        preferences.edit {
            putString(KEY_DATE, value.date.toString())
            putLong(KEY_FETCHED_AT, value.fetchedAt.toEpochMilli())
            putString(KEY_ENTRIES, value.entries.joinToString("\n") { entry ->
                val name = entry.commentator.replace('\t', ' ').replace('\n', ' ')
                "${entry.beinChannel}\t${entry.kickoff.toEpochMilli()}\t$name\t${if (entry.live) 1 else 0}"
            })
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "sports_commentators"
        const val KEY_DATE = "date"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_ENTRIES = "entries"
    }
}

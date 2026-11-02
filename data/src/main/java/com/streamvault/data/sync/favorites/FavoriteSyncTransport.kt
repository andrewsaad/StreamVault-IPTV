package com.streamvault.data.sync.favorites

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface FavoriteSyncFetchResult {
    data class Found(val body: ByteArray, val etag: String) : FavoriteSyncFetchResult
    data class NotModified(val etag: String) : FavoriteSyncFetchResult
    data object Missing : FavoriteSyncFetchResult
}

sealed interface FavoriteSyncStoreResult {
    data class Stored(val etag: String) : FavoriteSyncStoreResult
    /** Another TV wrote first (or created the document); fetch, merge and retry. */
    data object Conflict : FavoriteSyncStoreResult
}

/** Transport failures mapped to what the status line can explain. */
class FavoriteSyncTransportException(
    val reason: FavoriteSyncFailure,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

enum class FavoriteSyncFailure {
    UNREACHABLE,
    UNAUTHORIZED,
    TOO_LARGE,
    SERVER_ERROR
}

/** Compare-and-swap blob store for the single encrypted favorites document. */
interface FavoriteSyncTransport {
    suspend fun fetch(ifNoneMatch: String?): FavoriteSyncFetchResult

    /** Creates the document when [ifMatch] is null, otherwise replaces the version with that ETag. */
    suspend fun store(body: ByteArray, ifMatch: String?): FavoriteSyncStoreResult
}

/**
 * HTTP client for the home sync server contract (v1). Uses a private OkHttp client without
 * logging interceptors so the bearer token can never reach logcat.
 */
class HttpFavoriteSyncTransport(
    private val documentUrl: HttpUrl,
    private val token: String,
    private val client: OkHttpClient = defaultClient()
) : FavoriteSyncTransport {

    override suspend fun fetch(ifNoneMatch: String?): FavoriteSyncFetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(documentUrl)
            .get()
            .header("Authorization", "Bearer $token")
            .apply { ifNoneMatch?.let { header("If-None-Match", it) } }
            .build()
        execute(request) { code, etag, body ->
            when (code) {
                200 -> FavoriteSyncFetchResult.Found(body(), etag ?: missingEtag())
                304 -> FavoriteSyncFetchResult.NotModified(etag ?: ifNoneMatch ?: missingEtag())
                404 -> FavoriteSyncFetchResult.Missing
                else -> throw failure(code)
            }
        }
    }

    override suspend fun store(body: ByteArray, ifMatch: String?): FavoriteSyncStoreResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(documentUrl)
            .put(body.toRequestBody(OCTET_STREAM))
            .header("Authorization", "Bearer $token")
            .apply {
                if (ifMatch == null) header("If-None-Match", "*") else header("If-Match", ifMatch)
            }
            .build()
        execute(request) { code, etag, _ ->
            when (code) {
                200, 201 -> FavoriteSyncStoreResult.Stored(etag ?: missingEtag())
                // 404: the version named in If-Match no longer exists (document deleted on the server).
                404, 412 -> FavoriteSyncStoreResult.Conflict
                else -> throw failure(code)
            }
        }
    }

    private inline fun <T> execute(
        request: Request,
        handle: (code: Int, etag: String?, body: () -> ByteArray) -> T
    ): T {
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw FavoriteSyncTransportException(FavoriteSyncFailure.UNREACHABLE, "Sync server unreachable", error)
        }
        return response.use {
            handle(it.code, it.header("ETag")) {
                try {
                    it.body?.bytes() ?: ByteArray(0)
                } catch (error: IOException) {
                    throw FavoriteSyncTransportException(FavoriteSyncFailure.UNREACHABLE, "Sync download interrupted", error)
                }
            }
        }
    }

    private fun failure(code: Int): FavoriteSyncTransportException = when (code) {
        401, 403 -> FavoriteSyncTransportException(FavoriteSyncFailure.UNAUTHORIZED, "Sync server rejected the token")
        413 -> FavoriteSyncTransportException(FavoriteSyncFailure.TOO_LARGE, "Favorites document too large")
        // 428 means this client sent no precondition, a protocol bug rather than a user problem.
        else -> FavoriteSyncTransportException(FavoriteSyncFailure.SERVER_ERROR, "Sync server returned HTTP $code")
    }

    private fun missingEtag(): Nothing =
        throw FavoriteSyncTransportException(FavoriteSyncFailure.SERVER_ERROR, "Sync server response had no ETag")

    companion object {
        const val DOCUMENT_ID = "streamvault-favorites"
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** Builds the document URL from the base URL the user typed, or null when it is not http(s). */
        fun documentUrl(baseUrl: String): HttpUrl? {
            val parsed = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            return parsed.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("docs")
                .addPathSegment(DOCUMENT_ID)
                .build()
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

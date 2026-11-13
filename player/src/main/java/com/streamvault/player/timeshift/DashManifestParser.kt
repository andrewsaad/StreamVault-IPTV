package com.streamvault.player.timeshift

import java.io.IOException
import java.net.URI
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal data class ParsedDashManifest(
    val isDynamic: Boolean,
    val minimumUpdatePeriodMs: Long,
    val initSegmentUrl: String?,
    val mediaSegments: List<DashMediaSegmentRef>
)

/**
 * Parses a DASH MPD and extracts:
 * - Whether it is a live (dynamic) stream
 * - The minimumUpdatePeriod in ms (for live re-poll timing)
 * - The init segment URL for the selected representation
 * - The list of new media segments via SegmentTemplate + SegmentTimeline
 *
 * Selects the middle-bandwidth video Adaptation Set to balance quality vs storage,
 * consistent with the HLS variant selection policy. [depthMs] sizes the fixed-duration
 * segment window and [nowMs] is the wall clock used to number fixed-duration segments.
 */
internal fun parseDashManifest(
    baseUrl: String,
    rawXml: String,
    depthMs: Long,
    nowMs: Long = System.currentTimeMillis()
): ParsedDashManifest {
    val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
    val xpp = factory.newPullParser()
    xpp.setInput(rawXml.reader())

    var isDynamic = false
    var minimumUpdatePeriodMs = 5_000L
    var availabilityStartTimeMs = 0L
    var timescale = 1L
    var segmentDuration = 0L
    var startNumber = 1L
    var mediaTemplate = ""
    var initTemplate = ""

    // Collect all Representations from the first video AdaptationSet.
    // Structure: MPD > Period > AdaptationSet (contentType=video) > Representation
    data class RepresentationInfo(val id: String, val bandwidth: Int, val initTemplate: String, val mediaTemplate: String, val timescale: Long, val segmentDuration: Long, val startNumber: Long)
    val representations = mutableListOf<RepresentationInfo>()
    val timelineSegments = mutableListOf<Pair<Long, Long>>()  // (t, d) pairs

    var inVideoAdaptationSet = false
    var inRepresentation = false
    var inSegmentTimeline = false
    var currentRepBandwidth = 0
    var currentRepId = ""
    var currentInitTemplate = ""
    var currentMediaTemplate = ""
    var currentTimescale = 1L
    var currentSegDuration = 0L
    var currentStartNumber = 1L
    var currentTimelineSegments = mutableListOf<Pair<Long, Long>>()

    var eventType = xpp.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> when (xpp.name) {
                "MPD" -> {
                    isDynamic = xpp.getAttributeValue(null, "type")?.equals("dynamic", ignoreCase = true) == true
                    val mup = xpp.getAttributeValue(null, "minimumUpdatePeriod")
                    if (mup != null) minimumUpdatePeriodMs = parsePtDuration(mup)
                    val ast = xpp.getAttributeValue(null, "availabilityStartTime")
                    if (ast != null) availabilityStartTimeMs = parseIso8601ToMs(ast)
                }
                "AdaptationSet" -> {
                    val contentType = xpp.getAttributeValue(null, "contentType")
                        ?: xpp.getAttributeValue(null, "mimeType") ?: ""
                    inVideoAdaptationSet = contentType.contains("video", ignoreCase = true)
                }
                "Representation" -> if (inVideoAdaptationSet) {
                    inRepresentation = true
                    currentRepBandwidth = xpp.getAttributeValue(null, "bandwidth")?.toIntOrNull() ?: 0
                    currentRepId = xpp.getAttributeValue(null, "id") ?: ""
                    currentInitTemplate = initTemplate
                    currentMediaTemplate = mediaTemplate
                    currentTimescale = timescale
                    currentSegDuration = segmentDuration
                    currentStartNumber = startNumber
                    currentTimelineSegments = mutableListOf()
                }
                "SegmentTemplate" -> {
                    val ts = xpp.getAttributeValue(null, "timescale")?.toLongOrNull() ?: 1L
                    val sd = xpp.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0L
                    val sn = xpp.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: 1L
                    val it = xpp.getAttributeValue(null, "initialization") ?: ""
                    val mt = xpp.getAttributeValue(null, "media") ?: ""
                    if (inRepresentation) {
                        currentTimescale = ts; currentSegDuration = sd; currentStartNumber = sn
                        currentInitTemplate = it; currentMediaTemplate = mt
                    } else {
                        timescale = ts; segmentDuration = sd; startNumber = sn
                        initTemplate = it; mediaTemplate = mt
                    }
                }
                "SegmentTimeline" -> inSegmentTimeline = true
                "S" -> if (inSegmentTimeline) {
                    val t = xpp.getAttributeValue(null, "t")?.toLongOrNull()
                        ?: (currentTimelineSegments.lastOrNull()?.let { it.first + it.second } ?: 0L)
                    val d = xpp.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                    val r = xpp.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                    var tCurrent = t
                    repeat(r + 1) {
                        currentTimelineSegments += tCurrent to d
                        tCurrent += d
                    }
                }
            }
            XmlPullParser.END_TAG -> when (xpp.name) {
                "Representation" -> if (inVideoAdaptationSet && inRepresentation) {
                    representations += RepresentationInfo(
                        id = currentRepId,
                        bandwidth = currentRepBandwidth,
                        initTemplate = currentInitTemplate,
                        mediaTemplate = currentMediaTemplate,
                        timescale = currentTimescale,
                        segmentDuration = currentSegDuration,
                        startNumber = currentStartNumber
                    )
                    if (currentTimelineSegments.isNotEmpty()) {
                        timelineSegments.clear()
                        timelineSegments += currentTimelineSegments
                    }
                    inRepresentation = false
                }
                "AdaptationSet" -> inVideoAdaptationSet = false
                "SegmentTimeline" -> inSegmentTimeline = false
            }
        }
        eventType = xpp.next()
    }

    // Pick the median-bandwidth representation (same policy as HLS variant selection).
    val rep = representations.sortedBy { it.bandwidth }.getOrNull(representations.size / 2)
        ?: throw IOException("No video representations found in MPD.")

    val resolvedInit: String? = rep.initTemplate.takeIf { it.isNotEmpty() }
        ?.replace("\$RepresentationID\$", rep.id)
        ?.replace("\$Bandwidth\$", rep.bandwidth.toString())
        ?.let { resolveDashUrl(baseUrl, it) }

    val ts = rep.timescale.takeIf { it > 0L } ?: 1L
    val mediaSegments: List<DashMediaSegmentRef> = if (timelineSegments.isNotEmpty()) {
        // SegmentTimeline mode: each (t, d) pair is one segment.
        timelineSegments.mapIndexed { index, (t, d) ->
            val number = rep.startNumber + index
            val uri = rep.mediaTemplate
                .replace("\$RepresentationID\$", rep.id)
                .replace("\$Bandwidth\$", rep.bandwidth.toString())
                .replace("\$Number\$", number.toString())
                .replace("\$Time\$", t.toString())
                .let { resolveDashUrl(baseUrl, it) }
            DashMediaSegmentRef(
                uri = uri,
                durationMs = d * 1000L / ts,
                position = t,
                startMs = t * 1000L / ts
            )
        }
    } else if (rep.segmentDuration > 0L) {
        // Fixed-duration mode: compute number from wall clock and availabilityStartTime.
        val elapsedSecs = ((nowMs - availabilityStartTimeMs) / 1000L).coerceAtLeast(0L)
        val totalSegments = elapsedSecs * ts / rep.segmentDuration
        val windowSegments = (depthMs / 1000L * ts / rep.segmentDuration + 2L).toInt()
        val firstNumber = (totalSegments - windowSegments).coerceAtLeast(rep.startNumber)
        (firstNumber..totalSegments).map { number ->
            val uri = rep.mediaTemplate
                .replace("\$RepresentationID\$", rep.id)
                .replace("\$Bandwidth\$", rep.bandwidth.toString())
                .replace("\$Number\$", number.toString())
                .let { resolveDashUrl(baseUrl, it) }
            val segmentDurationMs = rep.segmentDuration * 1000L / ts
            DashMediaSegmentRef(
                uri = uri,
                durationMs = segmentDurationMs,
                position = number,
                startMs = number * segmentDurationMs
            )
        }
    } else {
        emptyList()
    }

    return ParsedDashManifest(
        isDynamic = isDynamic,
        minimumUpdatePeriodMs = minimumUpdatePeriodMs,
        initSegmentUrl = resolvedInit,
        mediaSegments = mediaSegments
    )
}

private fun resolveDashUrl(baseUrl: String, value: String): String =
    runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)

/** Parses ISO 8601 duration strings like `PT2.5S`, `PT1M30S`, `P1DT2H`. */
private fun parsePtDuration(value: String): Long {
    val upper = value.uppercase(Locale.ROOT)
    if (!upper.startsWith("P")) return 5_000L
    var ms = 0L
    val afterP = upper.removePrefix("P")
    val (datePart, timePart) = if (afterP.contains('T')) {
        afterP.substringBefore('T') to afterP.substringAfter('T')
    } else {
        afterP to ""
    }
    Regex("""(\d+(?:\.\d+)?)D""").find(datePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 86_400_000).toLong() }
    Regex("""(\d+(?:\.\d+)?)H""").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 3_600_000).toLong() }
    Regex("""(\d+(?:\.\d+)?)M""").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 60_000).toLong() }
    Regex("""(\d+(?:\.\d+)?)S""").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 1_000).toLong() }
    return ms.takeIf { it > 0L } ?: 5_000L
}

/** Parses an ISO 8601 datetime string to milliseconds since epoch, best-effort. */
private fun parseIso8601ToMs(value: String): Long = runCatching {
    java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
}.getOrDefault(0L)

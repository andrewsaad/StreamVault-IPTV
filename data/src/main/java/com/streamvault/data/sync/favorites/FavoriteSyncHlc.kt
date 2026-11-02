package com.streamvault.data.sync.favorites

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Hybrid logical clock timestamp. Ordering is wall time, then counter, then device id, so two
 * devices can never produce equal stamps and every merge has one deterministic winner.
 */
@Serializable(with = FavoriteSyncHlcSerializer::class)
data class FavoriteSyncHlc(
    val wallMs: Long,
    val counter: Int,
    val deviceId: String
) : Comparable<FavoriteSyncHlc> {
    override fun compareTo(other: FavoriteSyncHlc): Int = compareValuesBy(
        this,
        other,
        FavoriteSyncHlc::wallMs,
        FavoriteSyncHlc::counter,
        FavoriteSyncHlc::deviceId
    )

    fun encode(): String = "$wallMs.$counter.$deviceId"

    companion object {
        fun decode(value: String): FavoriteSyncHlc {
            val parts = value.split('.', limit = 3)
            require(parts.size == 3) { "Malformed sync timestamp" }
            return FavoriteSyncHlc(parts[0].toLong(), parts[1].toInt(), parts[2])
        }
    }
}

internal object FavoriteSyncHlcSerializer : KSerializer<FavoriteSyncHlc> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FavoriteSyncHlc", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FavoriteSyncHlc) = encoder.encodeString(value.encode())

    override fun deserialize(decoder: Decoder): FavoriteSyncHlc = FavoriteSyncHlc.decode(decoder.decodeString())
}

/**
 * Issues monotonic stamps for local edits and absorbs stamps seen from other TVs, so an edit made
 * after seeing a change always sorts after it even when this TV's clock runs behind.
 */
class FavoriteSyncClock(
    private val deviceId: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    initial: FavoriteSyncHlc? = null
) {
    private var lastWall: Long = initial?.wallMs ?: 0L
    private var lastCounter: Int = initial?.counter ?: 0

    @Synchronized
    fun now(): FavoriteSyncHlc {
        val wall = nowMs()
        if (wall > lastWall) {
            lastWall = wall
            lastCounter = 0
        } else {
            lastCounter += 1
        }
        return FavoriteSyncHlc(lastWall, lastCounter, deviceId)
    }

    @Synchronized
    fun observe(remote: FavoriteSyncHlc) {
        if (remote.wallMs > lastWall) {
            lastWall = remote.wallMs
            lastCounter = remote.counter
        } else if (remote.wallMs == lastWall && remote.counter > lastCounter) {
            lastCounter = remote.counter
        }
    }

    @Synchronized
    fun last(): FavoriteSyncHlc = FavoriteSyncHlc(lastWall, lastCounter, deviceId)
}

package com.streambox.mobile.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Provider responses sometimes carry numeric fields as JSON integers and sometimes
 * as strings (varies per endpoint/region, observed in Phase 1). These helpers accept
 * both forms without inventing data.
 */
object FlexibleIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Int? {
        val raw = decoder.decodeString().trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return null
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        encoder.encodeString(value?.toString() ?: "")
    }
}

object FlexibleLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long? {
        val raw = decoder.decodeString().trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return null
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        encoder.encodeString(value?.toString() ?: "")
    }
}

package com.sendspin.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents three states for a JSON field:
 * - [Absent]  — field was not present in the JSON object
 * - [Present] — field was present; [Present.value] is null if the JSON value was null
 *
 * Used to distinguish "server sent null (clear this field)" from "server omitted the field
 * (keep previous value)" when accumulating partial metadata updates.
 *
 * Serialization contract (see [JsonOptionalSerializer]): every [JsonOptional] property MUST
 * default to [Absent] so a missing key decodes back to [Absent]. These types are decode-only
 * (no outgoing message carries a [JsonOptional]), so the `Json` `encodeDefaults` setting is
 * irrelevant to the tri-state behaviour.
 */
@Serializable(with = JsonOptionalSerializer::class)
sealed class JsonOptional<out T> {
    object Absent : JsonOptional<Nothing>()
    data class Present<out T>(val value: T?) : JsonOptional<T>()
}

/**
 * Returns the present value (which may be null), or [fallback] if [Absent].
 *
 * Use `orFallback(prev) ?: ""` for non-nullable fields where null means "clear to empty".
 */
fun <T> JsonOptional<T>.orFallback(fallback: T?): T? =
    if (this is JsonOptional.Absent) fallback else (this as JsonOptional.Present).value

/**
 * Generic serializer for [JsonOptional]. The kotlinx plugin instantiates it with the element
 * serializer for `T` because the class is annotated `@Serializable(with = ...)`.
 *
 * Behaviour, matching the former Moshi adapter 1:1:
 * - **Absent key** — never reaches this serializer; the property default [JsonOptional.Absent]
 *   is used (requires `encodeDefaults = false`, which omits it on encode).
 * - **Explicit null** — [deserialize] sees no not-null mark → [JsonOptional.Present]`(null)`;
 *   [serialize] writes `null`.
 * - **Value** — round-trips through [inner].
 *
 * The descriptor is marked nullable so the JSON codec permits the explicit-null case on a
 * non-nullable Kotlin property.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonOptionalSerializer<T>(private val inner: KSerializer<T>) : KSerializer<JsonOptional<T>> {
    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    override fun serialize(encoder: Encoder, value: JsonOptional<T>) {
        when (value) {
            // Reached only if a host sets encodeDefaults = true; normally Absent is skipped.
            is JsonOptional.Absent -> encoder.encodeNull()
            is JsonOptional.Present -> {
                val v = value.value
                if (v == null) encoder.encodeNull() else encoder.encodeSerializableValue(inner, v)
            }
        }
    }

    override fun deserialize(decoder: Decoder): JsonOptional<T> =
        if (decoder.decodeNotNullMark()) {
            JsonOptional.Present(decoder.decodeSerializableValue(inner))
        } else {
            decoder.decodeNull()
            JsonOptional.Present(null)
        }
}

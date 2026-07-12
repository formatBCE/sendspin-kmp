package com.sendspin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the absent / null / value tri-state that [JsonOptionalSerializer] must preserve.
 * A regression here silently corrupts partial metadata merges, so these are exhaustive.
 */
class JsonOptionalTest {

    @Serializable
    private data class Holder(
        @SerialName("s") val s: JsonOptional<String> = JsonOptional.Absent,
        @SerialName("n") val n: JsonOptional<Int> = JsonOptional.Absent,
    )

    private fun decode(json: String) = ProtocolJson.decodeFromString(Holder.serializer(), json)

    @Test
    fun absent_key_decodes_to_Absent() {
        val h = decode("""{}""")
        assertEquals(JsonOptional.Absent, h.s)
        assertEquals(JsonOptional.Absent, h.n)
    }

    @Test
    fun explicit_null_decodes_to_Present_null() {
        val h = decode("""{"s":null,"n":null}""")
        assertEquals(JsonOptional.Present(null), h.s)
        assertEquals(JsonOptional.Present<Int>(null), h.n)
    }

    @Test
    fun value_decodes_to_Present_value() {
        val h = decode("""{"s":"hi","n":42}""")
        assertEquals(JsonOptional.Present("hi"), h.s)
        assertEquals(JsonOptional.Present(42), h.n)
    }

    @Test
    fun mixed_absent_null_value() {
        val h = decode("""{"s":"only"}""")
        assertEquals(JsonOptional.Present("only"), h.s)
        assertEquals(JsonOptional.Absent, h.n)
    }

    @Test
    fun present_value_round_trips_through_encode() {
        // encodeDefaults=true here, but a Present(value) must always emit its value.
        val json = ProtocolJson.encodeToString(Holder.serializer(), Holder(s = JsonOptional.Present("x"), n = JsonOptional.Present(7)))
        val back = decode(json)
        assertEquals(JsonOptional.Present("x"), back.s)
        assertEquals(JsonOptional.Present(7), back.n)
    }

    @Test
    fun present_null_encodes_as_json_null() {
        val json = ProtocolJson.encodeToString(Holder.serializer(), Holder(s = JsonOptional.Present(null)))
        // Must contain an explicit null for "s" so the peer can distinguish clear-vs-keep.
        assertEquals(JsonOptional.Present(null), decode(json).s)
    }
}

package com.sendspin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests the repeat/shuffle merge logic: controller values take precedence over metadata (legacy)
 * values, with metadata as a fallback for old servers that don't send controller state.
 */
@Suppress("DEPRECATION")
class ControllerMergeTest {

    private val prefs = ClientPreferences(
        supportedFormats = listOf(AudioFormat("flac", 2, 48000, 16)),
        artworkChannels = emptyList(),
    )

    private fun newClient() = testClient(preferences = prefs)

    @Test
    fun new_server_repeat_and_shuffle_from_controller_are_used() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":80,"muted":false,"repeat":"all","shuffle":true}}}""")
        val ctrl = assertNotNull(client.controllerState.value)
        assertEquals(JsonOptional.Present("all"), ctrl.repeat)
        assertEquals(JsonOptional.Present(true), ctrl.shuffle)
    }

    @Test
    fun old_server_controller_volume_only_metadata_supplies_repeat_and_shuffle() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":75,"muted":false},"metadata":{"repeat":"one","shuffle":true}}}""")
        val ctrl = assertNotNull(client.controllerState.value)
        assertEquals(JsonOptional.Present("one"), ctrl.repeat)
        assertEquals(JsonOptional.Present(true), ctrl.shuffle)
        assertEquals(75, ctrl.volume)
    }

    @Test
    fun old_server_metadata_repeat_and_shuffle_update_existing_controller_state() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":75,"muted":false}}}""")
        client.handleTextMessage("""{"type":"server/state","payload":{"metadata":{"title":"Track","repeat":"one","shuffle":false}}}""")
        val ctrl = assertNotNull(client.controllerState.value)
        assertEquals(JsonOptional.Present("one"), ctrl.repeat)
        assertEquals(JsonOptional.Present(false), ctrl.shuffle)
        assertEquals(75, ctrl.volume)
    }

    @Test
    fun old_server_metadata_repeat_without_prior_controller_state_is_ignored() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"metadata":{"repeat":"all","shuffle":true}}}""")
        assertNull(client.controllerState.value)
    }

    @Test
    fun controller_repeat_wins_over_metadata_repeat_when_both_present() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":100,"muted":false,"repeat":"all"},"metadata":{"repeat":"one"}}}""")
        assertEquals(JsonOptional.Present("all"), assertNotNull(client.controllerState.value).repeat)
    }

    @Test
    fun controller_null_repeat_is_not_overridden_by_metadata_repeat() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":100,"muted":false,"repeat":null},"metadata":{"repeat":"one"}}}""")
        assertEquals(JsonOptional.Present(null), assertNotNull(client.controllerState.value).repeat)
    }

    @Test
    fun old_server_metadata_explicit_null_clears_previously_set_repeat() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":80,"muted":false,"repeat":"all"}}}""")
        assertEquals(JsonOptional.Present("all"), assertNotNull(client.controllerState.value).repeat)
        client.handleTextMessage("""{"type":"server/state","payload":{"metadata":{"repeat":null}}}""")
        assertEquals(JsonOptional.Present(null), assertNotNull(client.controllerState.value).repeat)
    }

    @Test
    fun old_server_metadata_explicit_null_clears_previously_set_shuffle() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":80,"muted":false,"shuffle":true}}}""")
        assertEquals(JsonOptional.Present(true), assertNotNull(client.controllerState.value).shuffle)
        client.handleTextMessage("""{"type":"server/state","payload":{"metadata":{"shuffle":null}}}""")
        assertEquals(JsonOptional.Present(null), assertNotNull(client.controllerState.value).shuffle)
    }

    @Test
    fun controller_volume_only_update_preserves_previously_merged_repeat_and_shuffle() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":80,"muted":false,"repeat":"all","shuffle":true}}}""")
        assertEquals(JsonOptional.Present("all"), assertNotNull(client.controllerState.value).repeat)
        client.handleTextMessage("""{"type":"server/state","payload":{"controller":{"volume":50,"muted":false}}}""")
        val ctrl = assertNotNull(client.controllerState.value)
        assertEquals(50, ctrl.volume)
        assertEquals(JsonOptional.Present("all"), ctrl.repeat)
        assertEquals(JsonOptional.Present(true), ctrl.shuffle)
    }

    @Test
    fun no_repeat_or_shuffle_in_either_source_leaves_controller_state_null() {
        val client = newClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"metadata":{"title":"Track"}}}""")
        assertNull(client.controllerState.value)
    }
}

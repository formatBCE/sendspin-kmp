package com.sendspin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class StaticDelayPersistenceTest {

    private val prefs = ClientPreferences(
        supportedFormats = listOf(AudioFormat("pcm", 2, 48000, 16)),
        artworkChannels = emptyList(),
    )

    @Test
    fun setStaticDelayMs_persists_value_to_settings_store() {
        val store = FakeSettingsStore()
        val client = testClient(preferences = prefs, settingsStore = store)
        client.setStaticDelayMs(1500)
        assertEquals(1500, store.ints[ClientSettingsKeys.STATIC_DELAY_MS])
    }

    @Test
    fun static_delay_is_loaded_from_settings_store_on_construction() {
        val store = FakeSettingsStore()
        store.ints[ClientSettingsKeys.STATIC_DELAY_MS] = 1500
        val client = testClient(preferences = prefs, settingsStore = store)
        assertEquals(1_500_000L, client.audioBuffer.staticDelayMicros)
    }
}

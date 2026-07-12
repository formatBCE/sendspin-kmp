package com.sendspin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendSpinClientHelloTest {

    private val defaultPreferences = ClientPreferences(
        supportedFormats = listOf(
            AudioFormat("flac", 2, 48000, 16),
            AudioFormat("opus", 2, 48000, 16),
            AudioFormat("pcm", 2, 48000, 16),
        ),
        artworkChannels = listOf(
            ArtworkChannel("album", "jpeg", 800, 800),
            ArtworkChannel("artist", "jpeg", 1920, 1080),
        ),
    )

    private fun parseHello(json: String): ClientHello =
        ProtocolJson.decodeFromString(ClientHello.serializer(), json)

    @Test
    fun hello_includes_device_info() {
        val json = testClient(manufacturer = "Acme", productName = "SmartTV-9000", softwareVersion = "14")
            .buildClientHelloJson()
        val deviceInfo = assertNotNull(parseHello(json).payload.deviceInfo)
        assertEquals("Acme", deviceInfo.manufacturer)
        assertEquals("SmartTV-9000", deviceInfo.productName)
        assertEquals("14", deviceInfo.softwareVersion)
    }

    @Test
    fun hello_includes_client_id_and_name() {
        val json = testClient(clientId = "my-id", clientName = "Living Room TV").buildClientHelloJson()
        val payload = parseHello(json).payload
        assertEquals("my-id", payload.clientId)
        assertEquals("Living Room TV", payload.name)
    }

    @Test
    fun hello_advertises_artwork_channels_from_preferences() {
        val channels = listOf(
            ArtworkChannel("album", "jpeg", 800, 800),
            ArtworkChannel("artist", "jpeg", 1920, 1080),
        )
        val json = testClient(preferences = defaultPreferences.copy(artworkChannels = channels)).buildClientHelloJson()
        assertEquals(channels, assertNotNull(parseHello(json).payload.artworkSupport).channels)
    }

    @Test
    fun hello_includes_controller_support_as_empty_object() {
        val json = testClient().buildClientHelloJson()
        assertTrue(json.contains(""""controller@v1_support""""), "controller@v1_support missing")
        assertNotNull(parseHello(json).payload.controllerSupport)
    }

    @Test
    fun stream_end_with_versioned_player_role_clears_streamFormat() {
        val client = testClient()
        client.handleTextMessage(
            """{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,"channels":2,"bit_depth":16}}}""",
        )
        assertNotNull(client.streamFormat.value)
        client.handleTextMessage("""{"type":"stream/end","payload":{"roles":["player@v1"]}}""")
        assertNull(client.streamFormat.value)
    }

    @Test
    fun hello_advertises_color_in_supported_roles() {
        val json = testClient().buildClientHelloJson()
        val payload = parseHello(json).payload
        assertTrue(payload.supportedRoles.contains("color@v1"))
        assertTrue(json.contains(""""color@v1_support""""))
        assertNotNull(payload.colorSupport)
    }

    @Test
    fun stream_end_with_color_role_clears_colorState() {
        val client = testClient()
        client.handleTextMessage("""{"type":"server/state","payload":{"color":{"timestamp":1000,"primary":[255,0,0]}}}""")
        assertNotNull(client.colorState.value)
        client.handleTextMessage("""{"type":"stream/end","payload":{"roles":["color@v1"]}}""")
        assertNull(client.colorState.value)
    }

    @Test
    fun hello_includes_mac_address_when_provided() {
        val json = testClient(macAddress = "AA:BB:CC:DD:EE:FF").buildClientHelloJson()
        assertEquals("AA:BB:CC:DD:EE:FF", parseHello(json).payload.macAddress)
    }

    @Test
    fun hello_omits_mac_address_when_not_provided() {
        assertNull(parseHello(testClient().buildClientHelloJson()).payload.macAddress)
    }

    @Test
    fun hello_advertises_visualizer_when_support_set() {
        val support = VisualizerSupport(types = listOf("loudness", "beat"), bufferCapacity = 65536, rateMax = 60)
        val json = testClient(preferences = defaultPreferences.copy(visualizerSupport = support)).buildClientHelloJson()
        val payload = parseHello(json).payload
        assertTrue(payload.supportedRoles.contains("visualizer@v1"))
        val vs = assertNotNull(payload.visualizerSupport)
        assertEquals(listOf("loudness", "beat"), vs.types)
        assertEquals(65536, vs.bufferCapacity)
        assertEquals(60, vs.rateMax)
    }

    @Test
    fun hello_omits_visualizer_when_support_null() {
        val payload = parseHello(testClient().buildClientHelloJson()).payload
        assertTrue(!payload.supportedRoles.contains("visualizer@v1"))
        assertNull(payload.visualizerSupport)
    }

    @Test
    fun stream_end_with_visualizer_role_clears_config() {
        val client = testClient(
            preferences = defaultPreferences.copy(visualizerSupport = VisualizerSupport(listOf("loudness"), 65536, 60)),
        )
        client.handleTextMessage("""{"type":"stream/start","payload":{"visualizer":{"types":["loudness"],"rate_max":60}}}""")
        assertNotNull(client.visualizerStreamConfig.value)
        client.handleTextMessage("""{"type":"stream/end","payload":{"roles":["visualizer@v1"]}}""")
        assertNull(client.visualizerStreamConfig.value)
    }

    @Test
    fun hello_advertises_exactly_flac_opus_pcm_48k_16bit_in_order() {
        val json = testClient().buildClientHelloJson()
        val formats = assertNotNull(parseHello(json).payload.playerSupport).supportedFormats
        assertEquals(
            listOf(
                AudioFormat("flac", 2, 48000, 16),
                AudioFormat("opus", 2, 48000, 16),
                AudioFormat("pcm", 2, 48000, 16),
            ),
            formats,
        )
        assertTrue(formats.none { it.sampleRate == 44100 })
        assertTrue(formats.none { it.bitDepth == 24 })
    }
}

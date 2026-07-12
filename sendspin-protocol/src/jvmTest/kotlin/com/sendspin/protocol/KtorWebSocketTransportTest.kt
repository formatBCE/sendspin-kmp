package com.sendspin.protocol

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validates [KtorWebSocketTransport] end-to-end against an embedded echo server. */
class KtorWebSocketTransportTest {

    private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private var port = 0

    @BeforeTest
    fun startServer() {
        server = embeddedServer(io.ktor.server.cio.CIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> send(Frame.Text("echo:" + frame.readText()))
                            is Frame.Binary -> send(Frame.Binary(true, frame.readBytes()))
                            else -> {}
                        }
                    }
                }
            }
        }.start(wait = false)
        // Resolve the ephemeral port the engine actually bound to.
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServer() {
        server.stop(500, 500)
    }

    private fun newClient() = HttpClient(CIO) { install(WebSockets) }

    @Test
    fun connects_and_echoes_text_frame() = runBlocking {
        val client = newClient()
        val transport = KtorWebSocketTransport(client, "ws://127.0.0.1:$port/ws", Dispatchers.IO)
        try {
            withTimeout(10_000) {
                transport.connect()
                assertEquals(TransportState.Connected, transport.state.value)

                // UNDISPATCHED so the collector subscribes to the SharedFlow before we send.
                val received = async(start = CoroutineStart.UNDISPATCHED) { transport.textFrames.first() }
                assertTrue(transport.send("hello"))
                assertEquals("echo:hello", received.await())
            }
        } finally {
            transport.close()
            client.close()
        }
    }

    @Test
    fun echoes_binary_frame_unchanged() = runBlocking {
        val client = newClient()
        val transport = KtorWebSocketTransport(client, "ws://127.0.0.1:$port/ws", Dispatchers.IO)
        try {
            withTimeout(10_000) {
                transport.connect()
                val payload = byteArrayOf(0x04, 0x00, 0x01, 0xFF.toByte(), 0x7F)
                val received = async(start = CoroutineStart.UNDISPATCHED) { transport.binaryFrames.first() }
                assertTrue(transport.send(payload))
                assertTrue(payload.contentEquals(received.await()))
            }
        } finally {
            transport.close()
            client.close()
        }
    }
}

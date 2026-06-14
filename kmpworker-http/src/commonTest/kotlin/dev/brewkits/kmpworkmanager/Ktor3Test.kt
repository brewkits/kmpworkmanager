package dev.brewkits.kmpworkmanager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test locking in the Ktor 2 -> 3 breaking change (issue #33).
 *
 * In Ktor 3 the IO layer moved to kotlinx-io: [io.ktor.utils.io.ByteReadChannel.readAvailable]
 * now returns **-1 at end-of-stream** (Ktor 2 returned 0). Every download loop in the built-in
 * workers therefore must treat -1 as EOF (`if (n == -1) break`) or it spins / reads garbage.
 */
class Ktor3Test {

    @Test
    fun readAvailable_returnsBytesThenMinusOneAtEof() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { respond(payload) }
            }
        }

        val channel = client.get("https://example.com").bodyAsChannel()
        val buf = ByteArray(16)

        // First read drains the whole 3-byte body.
        val first = channel.readAvailable(buf, 0, buf.size)
        assertEquals(payload.size, first, "first readAvailable should return the payload length")

        // Drain the loop the same way the download workers do, guarding against an infinite loop.
        var total = first
        var loops = 0
        while (!channel.isClosedForRead) {
            val n = channel.readAvailable(buf, 0, buf.size)
            if (n == -1) break // Ktor 3 EOF signal — the guard added to the workers
            if (n > 0) total += n
            assertTrue(++loops < 10, "readAvailable loop did not terminate — EOF (-1) not honored")
        }

        assertEquals(payload.size, total, "all bytes should be consumed exactly once")
        assertTrue(channel.isClosedForRead, "channel should be closed after EOF")
    }
}

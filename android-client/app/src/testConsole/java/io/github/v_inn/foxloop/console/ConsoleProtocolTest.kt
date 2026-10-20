package io.github.v_inn.foxloop.console

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden vectors here are the same bytes `console-daemon/src/frame.rs`
 * tests against (`golden_*`). Each side is held to the bytes rather than to
 * the other side's code, so a change to either half that the other has not
 * made fails a test instead of a session.
 */
class ConsoleProtocolTest {
    private val nonce = 0x0102030405060708L

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val goldenHello = bytes(
        0x01, 0, 0, 0, 18,
        'F'.code, 'X'.code, 'C'.code, 'N'.code,
        0, 1,
        1, 2, 3, 4, 5, 6, 7, 8,
        0, 0, 0, 0,
    )

    private val goldenWelcomePayload = bytes(
        1, 2, 3, 4, 5, 6, 7, 8,
        0, 1,
        0, 0, 0, 0,
        0, 2, 'p'.code, 'i'.code,
    )

    @Test
    fun helloMatchesTheGoldenBytes() {
        assertArrayEquals(goldenHello, ConsoleProtocol.hello(nonce))
    }

    @Test
    fun welcomeParsesFromTheGoldenBytes() {
        val w = ConsoleProtocol.parseWelcome(goldenWelcomePayload)!!
        assertEquals(nonce, w.nonce)
        assertEquals(1, w.version)
        assertEquals(0, w.caps)
        assertEquals("pi", w.hostname)
    }

    @Test
    fun closeMatchesTheGoldenBytes() {
        assertArrayEquals(
            bytes(0x11, 0, 0, 0, 5, 1, 0, 2, 'n'.code, 'o'.code),
            ConsoleProtocol.close(ConsoleProtocol.REASON_SSHD_REFUSED, "no"),
        )
        val c = ConsoleProtocol.parseClose(bytes(1, 0, 2, 'n'.code, 'o'.code))!!
        assertEquals(ConsoleProtocol.REASON_SSHD_REFUSED, c.reason)
        assertEquals("no", c.detail)
    }

    @Test
    fun statusParsesTheDaemonsSentinelsAndSigns() {
        val s = ConsoleProtocol.parseStatus(bytes(0, 5, 0, 5, 0xFF, 0xFF, 0xFB, 0x2E))!!
        assertEquals(0x50005, s.throttled)
        assertEquals(-1234, s.cpuTempMilli)

        val unknown = ConsoleProtocol.parseStatus(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x80, 0, 0, 0))!!
        assertEquals(ConsoleProtocol.THROTTLED_UNKNOWN, unknown.throttled)
        assertEquals(ConsoleProtocol.TEMP_UNKNOWN, unknown.cpuTempMilli)
    }

    @Test
    fun pingIsAnEmptyFrame() {
        assertArrayEquals(bytes(0x21, 0, 0, 0, 0), ConsoleProtocol.ping())
    }

    @Test
    fun dataFramesCarryASlice() {
        val f = ConsoleProtocol.frame(ConsoleProtocol.DATA, bytes(9, 8, 7, 6), 1, 2)
        assertArrayEquals(bytes(0x10, 0, 0, 0, 2, 8, 7), f)
    }

    @Test
    fun truncatedPayloadsParseToNull() {
        assertNull(ConsoleProtocol.parseWelcome(goldenWelcomePayload.copyOf(10)))
        assertNull(ConsoleProtocol.parseStatus(bytes(0, 0, 0)))
    }

    @Test
    fun welcomeStartIsRecognisedOnlyForItsOwnNonce() {
        val frame = ConsoleProtocol.frame(ConsoleProtocol.WELCOME, goldenWelcomePayload)
        val window = frame.copyOf(ConsoleProtocol.WELCOME_PROBE)
        assertTrue(ConsoleProtocol.isWelcomeStart(window, nonce))
        assertFalse(ConsoleProtocol.isWelcomeStart(window, nonce + 1))
    }

    @Test
    fun aStaleWelcomeOrGarbageIsNotAWelcomeStart() {
        // The right type and nonce, but a length no WELCOME has.
        val window = ConsoleProtocol.frame(ConsoleProtocol.WELCOME, goldenWelcomePayload).copyOf(ConsoleProtocol.WELCOME_PROBE)
        window[1] = 0x7F
        assertFalse(ConsoleProtocol.isWelcomeStart(window, nonce))
        // A DATA frame whose payload happens to start with the nonce.
        val data = ConsoleProtocol.frame(ConsoleProtocol.DATA, goldenWelcomePayload).copyOf(ConsoleProtocol.WELCOME_PROBE)
        assertFalse(ConsoleProtocol.isWelcomeStart(data, nonce))
    }
}

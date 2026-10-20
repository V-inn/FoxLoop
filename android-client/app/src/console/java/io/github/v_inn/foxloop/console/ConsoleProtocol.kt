package io.github.v_inn.foxloop.console

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The FoxLoop Console wire protocol, version 1: the app's half.
 *
 * The documentation and the daemon's half live in
 * `console-daemon/src/frame.rs`; everything here mirrors it and must change
 * with it. In short: every byte in both directions is a frame of
 * `u8 type, u32 payload_len (big-endian), payload`, strings are `u16 len` +
 * UTF-8, and an unknown type is skipped by its length.
 *
 * Pure Kotlin on purpose -- no Android types -- so it is tested on the JVM.
 */
internal object ConsoleProtocol {
    const val HELLO = 0x01
    const val WELCOME = 0x02
    const val DATA = 0x10
    const val CLOSE = 0x11
    const val STATUS = 0x20
    const val PING = 0x21

    /** `"FXCN"`. Not the display's `"QUIL"`, so neither daemon misreads the other's client. */
    val MAGIC = byteArrayOf('F'.code.toByte(), 'X'.code.toByte(), 'C'.code.toByte(), 'N'.code.toByte())
    const val VERSION = 1

    /** Largest DATA payload either side sends. */
    const val MAX_DATA = 16 * 1024
    /** A length beyond this means the stream is out of step, not that a huge frame is coming. */
    const val MAX_PAYLOAD = 64 * 1024

    const val HEARTBEAT_INTERVAL_MS = 1_000L
    const val HEARTBEAT_TIMEOUT_MS = 5_000L

    /** STATUS `throttled` on a machine that cannot report it: u32::MAX on the wire. */
    const val THROTTLED_UNKNOWN = -1
    /** STATUS `cpu_temp_millideg` with no thermal zone: i32::MIN on the wire. */
    const val TEMP_UNKNOWN = Int.MIN_VALUE

    const val REASON_APP_CLOSED = 0
    const val REASON_SSHD_REFUSED = 1
    const val REASON_TCP_CLOSED = 2

    data class Welcome(val nonce: Long, val version: Int, val caps: Int, val hostname: String)
    data class Status(val throttled: Int, val cpuTempMilli: Int)
    data class Close(val reason: Int, val detail: String)

    /** One frame as one buffer, so it goes out in a single write and frames never interleave. */
    fun frame(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val out = ByteArray(5 + length)
        out[0] = type.toByte()
        out[1] = (length ushr 24).toByte()
        out[2] = (length ushr 16).toByte()
        out[3] = (length ushr 8).toByte()
        out[4] = length.toByte()
        System.arraycopy(payload, offset, out, 5, length)
        return out
    }

    fun hello(nonce: Long, caps: Int = 0): ByteArray = frame(HELLO, build {
        write(MAGIC)
        writeShort(VERSION)
        writeLong(nonce)
        writeInt(caps)
    })

    fun ping(): ByteArray = frame(PING, ByteArray(0))

    fun close(reason: Int, detail: String): ByteArray = frame(CLOSE, build {
        writeByte(reason)
        writeStr(detail)
    })

    fun parseWelcome(payload: ByteArray): Welcome? = parse(payload) {
        Welcome(nonce = readLong(), version = readUnsignedShort(), caps = readInt(), hostname = readStr())
    }

    fun parseStatus(payload: ByteArray): Status? = parse(payload) {
        Status(throttled = readInt(), cpuTempMilli = readInt())
    }

    fun parseClose(payload: ByteArray): Close? = parse(payload) {
        Close(reason = readUnsignedByte(), detail = readStr())
    }

    /**
     * True when the 13 bytes at the end of a sliding window are the start of
     * the WELCOME for `nonce`: type, a plausible length, then the nonce itself.
     *
     * The app's counterpart of the daemon's `find_hello`. When the accessory is
     * opened, what arrives first may be the tail of the previous session's
     * frames, cut anywhere; reading that as frames would take a length out of
     * the middle of a payload and could swallow the real WELCOME with it.
     * Matching on a fresh random 64-bit nonce instead cannot be fooled by
     * anything a previous session sent.
     */
    fun isWelcomeStart(window: ByteArray, nonce: Long): Boolean {
        if (window.size != WELCOME_PROBE || window[0].toInt() != WELCOME) return false
        val len = ((window[1].toInt() and 0xFF) shl 24) or ((window[2].toInt() and 0xFF) shl 16) or
            ((window[3].toInt() and 0xFF) shl 8) or (window[4].toInt() and 0xFF)
        if (len < WELCOME_MIN_LEN || len > MAX_PAYLOAD) return false
        for (i in 0 until 8) {
            if (window[5 + i] != (nonce ushr (56 - 8 * i)).toByte()) return false
        }
        return true
    }

    /** type + length + nonce: what [isWelcomeStart] looks at. */
    const val WELCOME_PROBE = 13
    /** nonce, version, caps, and an empty hostname's length. */
    private const val WELCOME_MIN_LEN = 8 + 2 + 4 + 2

    private fun build(body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).body()
        return bytes.toByteArray()
    }

    private fun <T> parse(payload: ByteArray, body: DataInputStream.() -> T): T? = try {
        DataInputStream(payload.inputStream()).body()
    } catch (e: IOException) {
        null
    }

    private fun DataOutputStream.writeStr(s: String) {
        var bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size > 0xFFFF) bytes = bytes.copyOf(0xFFFF)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readStr(): String {
        val bytes = ByteArray(readUnsignedShort())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

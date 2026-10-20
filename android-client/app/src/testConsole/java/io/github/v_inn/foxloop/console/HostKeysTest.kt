package io.github.v_inn.foxloop.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeysTest {
    /** An ssh-ed25519 public key blob: the type string, then the 32-byte key. */
    private val ed25519 = run {
        val type = "ssh-ed25519".toByteArray()
        val key = ByteArray(32) { it.toByte() }
        byteArrayOf(0, 0, 0, type.size.toByte()) + type + byteArrayOf(0, 0, 0, 32) + key
    }

    @Test
    fun firstSightIsUnknown() {
        assertTrue(HostKeys.decide(null, ed25519) is HostKeys.Verdict.Unknown)
    }

    @Test
    fun theSameKeyMatches() {
        assertTrue(HostKeys.decide(ed25519.copyOf(), ed25519) is HostKeys.Verdict.Match)
    }

    @Test
    fun aDifferentKeyIsAMismatchCarryingBoth() {
        val other = ed25519.copyOf().also { it[it.size - 1] = 99 }
        val v = HostKeys.decide(ed25519, other)
        assertTrue(v is HostKeys.Verdict.Mismatch)
        v as HostKeys.Verdict.Mismatch
        assertEquals(ed25519.toList(), v.saved.toList())
        assertEquals(other.toList(), v.offered.toList())
    }

    @Test
    fun keyTypeIsReadFromTheBlob() {
        assertEquals("ssh-ed25519", HostKeys.keyTypeOf(ed25519))
        assertNull(HostKeys.keyTypeOf(byteArrayOf(0, 0)))
        assertNull(HostKeys.keyTypeOf(byteArrayOf(0x7F, 0, 0, 0, 1)))
    }

    @Test
    fun fingerprintIsOpenSshShaped() {
        val fp = HostKeys.fingerprint(ed25519)
        assertTrue(fp, fp.startsWith("SHA256:"))
        // 32 bytes of digest in unpadded base64 is 43 characters.
        assertEquals(43, fp.removePrefix("SHA256:").length)
        assertTrue(fp, !fp.endsWith("="))
    }
}

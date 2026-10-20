package io.github.v_inn.foxloop.console

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.PublicKey
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Pins each machine's SSH host key to the serial string its daemon sends in
 * the AOA identification -- a hash of that machine's `/etc/machine-id`.
 *
 * sshj's own known-hosts check is useless here: `connectVia` has no socket, so
 * every machine reports the same placeholder host and port, and a known_hosts
 * file would either accept all of them as one or flag every second machine as
 * an impostor. The serial is the only thing that tells two machines apart
 * before SSH has said anything.
 *
 * Trust on first use, like OpenSSH: the first key seen for a serial is shown to
 * the person to accept, and a different key later is refused outright.
 */
internal class HostKeys(context: Context) {
    private val prefs = context.getSharedPreferences("foxloop_console_hosts", Context.MODE_PRIVATE)

    fun saved(serial: String): ByteArray? =
        prefs.getString("key.$serial", null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun trust(serial: String, blob: ByteArray) {
        prefs.edit().putString("key.$serial", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun forget(serial: String) {
        prefs.edit().remove("key.$serial").apply()
    }

    fun lastUser(serial: String): String = prefs.getString("user.$serial", "") ?: ""

    fun rememberUser(serial: String, user: String) {
        prefs.edit().putString("user.$serial", user).apply()
    }

    /** A verifier for one connection attempt; read [Verifier.verdict] if the connect fails. */
    fun verifier(serial: String) = Verifier(saved(serial))

    class Verifier(private val saved: ByteArray?) : HostKeyVerifier {
        @Volatile var verdict: Verdict? = null
            private set

        override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
            val v = decide(saved, blobOf(key))
            verdict = v
            return v is Verdict.Match
        }

        /**
         * Steers negotiation towards the algorithm of the key already pinned. A
         * machine has several host keys; without this, a change in sshj's
         * preference order would pick a different one and look exactly like an
         * impostor.
         */
        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> =
            saved?.let { keyTypeOf(it) }?.let { listOf(it) } ?: emptyList()
    }

    sealed interface Verdict {
        val offered: ByteArray
        class Match(override val offered: ByteArray) : Verdict
        /** Never seen this machine: ask. */
        class Unknown(override val offered: ByteArray) : Verdict
        /** Seen it with a different key: refuse, and say so. */
        class Mismatch(val saved: ByteArray, override val offered: ByteArray) : Verdict
    }

    companion object {
        fun decide(saved: ByteArray?, offered: ByteArray): Verdict = when {
            saved == null -> Verdict.Unknown(offered)
            saved.contentEquals(offered) -> Verdict.Match(offered)
            else -> Verdict.Mismatch(saved, offered)
        }

        /**
         * `SHA256:` + unpadded base64 of the key blob: the same string
         * `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` prints on the
         * machine itself, so the two can be compared by eye.
         */
        fun fingerprint(blob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        /** The algorithm name at the head of an SSH public key blob, e.g. `ssh-ed25519`. */
        fun keyTypeOf(blob: ByteArray): String? {
            if (blob.size < 4) return null
            val len = ((blob[0].toInt() and 0xFF) shl 24) or ((blob[1].toInt() and 0xFF) shl 16) or
                ((blob[2].toInt() and 0xFF) shl 8) or (blob[3].toInt() and 0xFF)
            if (len <= 0 || len > blob.size - 4) return null
            return String(blob, 4, len, Charsets.US_ASCII)
        }

        fun blobOf(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData
    }
}

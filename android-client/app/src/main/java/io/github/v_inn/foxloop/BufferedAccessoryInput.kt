package io.github.v_inn.foxloop

/** Minimal replacement for `BufferedInputStream` over the USB accessory
 * fd. `BufferedInputStream` itself can't be used there -- confirmed
 * live: it calls `FileInputStream.available()` as a read optimization,
 * which throws `IOException("Invalid argument")` on this fd type (the
 * FIONREAD ioctl isn't supported on it). But going fully unbuffered is
 * *worse*: USB bulk transfers are packet-oriented, and confirmed live,
 * requesting fewer bytes than a single incoming packet holds silently
 * drops the remainder of that packet instead of queuing it for the next
 * read() call (unlike a stream socket) -- every read after the first
 * then desyncs onto whatever arrives next (observed: `readClockOffset`'s
 * first 8-byte field always came back correct, every field after it did
 * not, byte-for-byte reproducible across runs). Always refilling from a
 * buffer at least as large as the daemon's largest single write (video
 * frames can be ~100KB) avoids both problems at once. */
internal class BufferedAccessoryInput(
    private val underlying: java.io.InputStream,
    /** The `ParcelFileDescriptor` the stream was built from, when there is
     * one. It, not the stream, actually owns the accessory fd -- see
     * [close]. */
    private val owner: java.io.Closeable? = null,
    size: Int = 1 shl 20,
) {
    private val buf = ByteArray(size)
    private var pos = 0
    private var limit = 0

    /** Force-unblocks a read() that's currently stuck waiting for bytes
     * that will never come (e.g. the daemon process died without the
     * underlying USB transport itself signaling an error) -- closing
     * the stream out from under a blocked read is the standard way to
     * make it throw instead of hanging forever. Called only from the
     * watchdog thread, never from the thread actually doing the
     * reading.
     *
     * **The `ParcelFileDescriptor` has to be closed too, and it is the one
     * that matters.** Confirmed live: a `FileInputStream` built from
     * `pfd.fileDescriptor` does not own that descriptor, so closing the
     * stream left the blocked `read()` blocked. The watchdog logged
     * "forcing reconnect" and then nothing further ever happened -- the
     * decode thread never returned, the retry loop in `surfaceCreated`
     * never fired, and both sides deadlocked: the app blocked reading, the
     * daemon blocked waiting for a handshake the app would now never send.
     * Only a manual app relaunch recovered it, which is the exact symptom
     * Milestone 11 set out to remove. */
    fun close() {
        try {
            underlying.close()
        } catch (e: Exception) {
            // Already closed/broken -- fine, that's the whole point.
        }
        try {
            owner?.close()
        } catch (e: Exception) {
        }
    }

    private fun fill() {
        val n = underlying.read(buf, 0, buf.size)
        if (n < 0) throw java.io.EOFException("accessory stream closed")
        pos = 0
        limit = n
    }

    fun readExact(len: Int): ByteArray {
        val result = ByteArray(len)
        var got = 0
        while (got < len) {
            if (pos >= limit) fill()
            val n = minOf(limit - pos, len - got)
            System.arraycopy(buf, pos, result, got, n)
            pos += n
            got += n
        }
        return result
    }

    fun readLong(): Long {
        val b = readExact(8)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun readInt(): Int {
        val b = readExact(4)
        var v = 0
        for (i in 0 until 4) v = (v shl 8) or (b[i].toInt() and 0xFF)
        return v
    }
}

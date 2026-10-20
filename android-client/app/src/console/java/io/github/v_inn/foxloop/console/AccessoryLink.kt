package io.github.v_inn.foxloop.console

import android.os.SystemClock
import android.util.Log
import io.github.v_inn.foxloop.BufferedAccessoryInput
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "FoxLoopConsole"

/** How long a HELLO may go unanswered before the daemon is presumed absent. */
private const val WELCOME_TIMEOUT_MS = 10_000L

/**
 * One open accessory: the only thread that reads it, the heartbeat, and
 * whichever [Session] the incoming frames currently belong to.
 *
 * Sessions come and go over one link -- the probe that fetches the hostname,
 * each login attempt, a reconnect after logout -- while the link lives as long
 * as the file descriptor. That shape is forced by one fact: **a blocked read on
 * the accessory cannot be interrupted except by closing the descriptor** (see
 * [BufferedAccessoryInput.close]). A reader per session would leave the
 * previous one blocked forever, so there is exactly one, and a session is just
 * where its frames are delivered.
 */
internal class AccessoryLink(
    private val input: BufferedAccessoryInput,
    private val output: OutputStream,
    /** Called once, on a link thread, when the link dies by itself. */
    private val onLost: (String) -> Unit,
) {
    @Volatile private var current: Session? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile var isClosed = false
        private set

    private val writeLock = Any()
    private val random = SecureRandom()
    private val reader = Thread(::readLoop, "console-link-reader")
    // Two threads, not one, because a write to the accessory has no timeout: if
    // the daemon dies with the phone still in accessory mode, nothing on the
    // host reads the endpoint and a PING blocks forever. A watchdog that also
    // sent the PINGs would block with it and never notice.
    private val pinger = Thread(::pingLoop, "console-link-ping")
    private val watchdog = Thread(::watchdogLoop, "console-link-watchdog")

    fun start() {
        reader.start()
        pinger.start()
        watchdog.start()
    }

    /**
     * Ends whatever session is current and starts a new one with a fresh
     * nonce. Frames for the old one that are still in flight are ignored:
     * nothing is delivered to a session before the WELCOME carrying its nonce.
     */
    fun openSession(listener: Session.Listener): Session {
        current?.close()
        val session = Session(this, random.nextLong(), listener)
        current = session
        send(ConsoleProtocol.hello(session.nonce))
        return session
    }

    /** Closes the descriptor, which is the only thing that unblocks the reader. */
    fun close(reason: String) {
        if (isClosed) return
        current?.let {
            // Best effort, and only from a thread that is not the one stuck: the
            // daemon would notice the silence within its heartbeat timeout anyway.
            if (Thread.currentThread() !== watchdog) {
                try { send(ConsoleProtocol.close(ConsoleProtocol.REASON_APP_CLOSED, reason)) } catch (_: IOException) {}
            }
        }
        isClosed = true
        current?.finish(reason)
        pinger.interrupt()
        watchdog.interrupt()
        input.close()
    }

    internal fun send(frame: ByteArray) {
        if (isClosed) throw IOException("link closed")
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    internal fun detach(session: Session) {
        if (current === session) current = null
    }

    private fun lost(reason: String) {
        if (isClosed) return
        Log.w(TAG, "link lost: $reason")
        close(reason)
        onLost(reason)
    }

    private fun readLoop() {
        try {
            alignOnWelcome()
            while (!isClosed) {
                val type = input.readExact(1)[0].toInt() and 0xFF
                val len = input.readInt()
                if (len < 0 || len > ConsoleProtocol.MAX_PAYLOAD) {
                    throw IOException("frame type 0x%02x claims %d bytes -- stream out of step".format(type, len))
                }
                val payload = input.readExact(len)
                lastFrameAt = SystemClock.elapsedRealtime()
                deliver(type, payload)
            }
        } catch (e: IOException) {
            if (!isClosed) lost(e.message ?: "the cable stopped answering")
        }
    }

    /**
     * Skips whatever precedes the first WELCOME for the current session, byte
     * by byte. Only needed once per descriptor: after that the stream is in
     * step, because every frame the daemon writes is read here whole.
     */
    private fun alignOnWelcome() {
        val window = input.readExact(ConsoleProtocol.WELCOME_PROBE)
        var skipped = 0
        while (true) {
            val session = current
            if (session != null && ConsoleProtocol.isWelcomeStart(window, session.nonce)) {
                val len = ((window[1].toInt() and 0xFF) shl 24) or ((window[2].toInt() and 0xFF) shl 16) or
                    ((window[3].toInt() and 0xFF) shl 8) or (window[4].toInt() and 0xFF)
                val payload = window.copyOfRange(5, ConsoleProtocol.WELCOME_PROBE) + input.readExact(len - 8)
                if (skipped > 0) Log.i(TAG, "skipped $skipped stale byte(s) before WELCOME")
                lastFrameAt = SystemClock.elapsedRealtime()
                deliver(ConsoleProtocol.WELCOME, payload)
                return
            }
            System.arraycopy(window, 1, window, 0, window.size - 1)
            window[window.size - 1] = input.readExact(1)[0]
            skipped++
        }
    }

    private fun deliver(type: Int, payload: ByteArray) {
        val session = current ?: return
        if (!session.isWelcomed) {
            if (type == ConsoleProtocol.WELCOME) {
                val w = ConsoleProtocol.parseWelcome(payload)
                if (w != null && w.nonce == session.nonce) session.welcome(w)
            }
            return
        }
        when (type) {
            ConsoleProtocol.DATA -> session.data(payload)
            ConsoleProtocol.STATUS -> ConsoleProtocol.parseStatus(payload)?.let { session.listener.onStatus(it) }
            ConsoleProtocol.CLOSE -> session.finish(describe(ConsoleProtocol.parseClose(payload)))
            // Anything newer than this app is skipped by length, as promised.
        }
    }

    private fun pingLoop() {
        while (!isClosed) {
            try {
                Thread.sleep(ConsoleProtocol.HEARTBEAT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (current?.isWelcomed != true) continue
            try {
                send(ConsoleProtocol.ping())
            } catch (e: IOException) {
                lost(e.message ?: "write failed")
                return
            }
        }
    }

    private fun watchdogLoop() {
        while (!isClosed) {
            try {
                Thread.sleep(ConsoleProtocol.HEARTBEAT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val session = current ?: continue
            val now = SystemClock.elapsedRealtime()
            if (session.isWelcomed) {
                if (now - lastFrameAt > ConsoleProtocol.HEARTBEAT_TIMEOUT_MS) {
                    lost("no word from ${session.hostname} for ${ConsoleProtocol.HEARTBEAT_TIMEOUT_MS / 1000} s")
                    return
                }
            } else if (now - session.startedAt > WELCOME_TIMEOUT_MS) {
                // The link may be fine and the daemon simply not running yet:
                // end the attempt, keep the descriptor.
                session.finish("no answer from foxloop-console -- is it installed and running on that machine?")
            }
        }
    }

    private fun describe(close: ConsoleProtocol.Close?): String = when {
        close == null -> "the session was closed"
        close.reason == ConsoleProtocol.REASON_SSHD_REFUSED -> "sshd is not answering: ${close.detail}"
        close.detail.isNotEmpty() -> close.detail
        else -> "the session was closed"
    }
}

/**
 * One HELLO..CLOSE exchange: what sshj reads from and writes to.
 *
 * [input] and [output] carry SSH bytes only; framing happens here, so sshj sees
 * an ordinary byte stream and `connectVia` needs no socket and no permission.
 */
internal class Session(
    private val link: AccessoryLink,
    val nonce: Long,
    val listener: Listener,
) {
    interface Listener {
        /** On a link thread. */
        fun onStatus(status: ConsoleProtocol.Status) {}
        /** On a link thread, exactly once. */
        fun onEnded(reason: String) {}
    }

    val startedAt = SystemClock.elapsedRealtime()
    @Volatile var isWelcomed = false
        private set
    @Volatile var hostname = "the machine"
        private set
    @Volatile var isEnded = false
        private set

    private val welcomed = CountDownLatch(1)
    @Volatile private var welcome: ConsoleProtocol.Welcome? = null
    @Volatile private var endReason = ""
    // Unbounded, and only ever fed with `offer`, never `put`. `put` throws
    // InterruptedException on an interrupted thread, and [finish] is reached
    // from sshj's reader thread *after* sshj has interrupted it: tearing a
    // connection down closes [output] from there. Confirmed on the tablet --
    // every first login, which ends in exactly that teardown so the host-key
    // prompt can be shown, killed the app with the exception uncaught.
    private val incoming = LinkedBlockingQueue<ByteArray>()

    /** Blocks until WELCOME, or throws with the reason the session ended first. */
    fun awaitWelcome(): ConsoleProtocol.Welcome {
        welcomed.await(WELCOME_TIMEOUT_MS + ConsoleProtocol.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        return welcome ?: throw IOException(endReason.ifEmpty { "no answer from foxloop-console" })
    }

    /** Ends the session from this side: CLOSE to the daemon, which hangs up on sshd. */
    fun close() {
        if (isEnded) return
        try { link.send(ConsoleProtocol.close(ConsoleProtocol.REASON_APP_CLOSED, "closed by the app")) } catch (_: IOException) {}
        finish("closed")
    }

    internal fun welcome(w: ConsoleProtocol.Welcome) {
        hostname = w.hostname
        welcome = w
        isWelcomed = true
        welcomed.countDown()
    }

    internal fun data(payload: ByteArray) {
        if (payload.isNotEmpty()) incoming.offer(payload)
    }

    internal fun finish(reason: String) {
        if (isEnded) return
        isEnded = true
        endReason = reason
        link.detach(this)
        incoming.offer(EOF)
        welcomed.countDown()
        listener.onEnded(reason)
    }

    val input: InputStream = object : InputStream() {
        private var chunk: ByteArray = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= chunk.size) {
                if (chunk === EOF) return -1
                chunk = try { incoming.take() } catch (_: InterruptedException) { throw java.io.InterruptedIOException() }
                pos = 0
                if (chunk === EOF) {
                    incoming.offer(EOF) // every later read sees the end too
                    return -1
                }
            }
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = if (chunk === EOF) 0 else chunk.size - pos
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (isEnded) throw IOException("session ended: $endReason")
            var at = off
            val end = off + len
            while (at < end) {
                val n = minOf(ConsoleProtocol.MAX_DATA, end - at)
                link.send(ConsoleProtocol.frame(ConsoleProtocol.DATA, b, at, n))
                at += n
            }
        }

        override fun close() = this@Session.close()
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}

package io.github.v_inn.foxloop.console

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session as SshSession
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

private const val TAG = "FoxLoopConsole"

/**
 * An SSH shell over one [Session] of the accessory link: sshd checks the
 * password, opens the PTY and runs the shell, and nothing here knows a
 * keystroke from a byte.
 */
internal class ConsoleShell private constructor(
    private val ssh: SSHClient,
    private val channel: SshSession,
    private val shell: SshSession.Shell,
) {
    /** What the shell prints, PTY-merged: stderr arrives here too. */
    val output: InputStream get() = shell.inputStream

    // One thread for everything sent towards the shell: keystrokes from the
    // main thread must not block on the cable, and their order must survive.
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "console-shell-input") }

    fun send(bytes: ByteArray) = sender.execute {
        try {
            shell.outputStream.write(bytes)
            shell.outputStream.flush()
        } catch (e: IOException) {
            Log.w(TAG, "shell write failed: ${e.message}")
        }
    }

    fun resize(columns: Int, rows: Int) = sender.execute {
        try {
            shell.changeWindowDimensions(columns, rows, 0, 0)
        } catch (e: IOException) {
            Log.w(TAG, "window resize failed: ${e.message}")
        }
    }

    private val forwards = ConcurrentHashMap<Int, LocalPortForwarder>()

    /**
     * Listens on 127.0.0.1:[localPort] on the phone and carries every connection
     * made there to 127.0.0.1:[remotePort] on the machine, as a `direct-tcpip`
     * channel on this same SSH connection: behind the same login, multiplexed
     * with the terminal, each channel with its own SSH window.
     *
     * Idempotent per local port, so reopening a dashboard reuses its forward.
     * Blocking only for the bind; run off the main thread anyway.
     */
    @Throws(IOException::class)
    fun forward(localPort: Int, remotePort: Int) {
        if (forwards.containsKey(localPort)) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(Dashboard.LOOPBACK), localPort))
        val params = Parameters(Dashboard.LOOPBACK, localPort, Dashboard.LOOPBACK, remotePort)
        val forwarder = ssh.newLocalPortForwarder(params, socket)
        forwards[localPort] = forwarder
        // listen() blocks for the forward's whole life; close() ends it.
        thread(name = "console-forward-$localPort", isDaemon = true) {
            try {
                forwarder.listen()
            } catch (e: IOException) {
                Log.i(TAG, "forward $localPort -> $remotePort ended: ${e.message}")
            } finally {
                forwards.remove(localPort, forwarder)
            }
        }
    }

    fun close() {
        for (f in forwards.values) {
            try { f.close() } catch (_: IOException) {}
        }
        forwards.clear()
        sender.execute {
            try { channel.close() } catch (_: IOException) {}
            try { ssh.disconnect() } catch (_: IOException) {}
        }
        sender.shutdown()
    }

    sealed interface Opened {
        class Ok(val shell: ConsoleShell) : Opened
        /** The host key was not accepted; the verdict says whether it is new or changed. */
        class HostKey(val verdict: HostKeys.Verdict) : Opened
        object WrongPassword : Opened
        class Failed(val reason: String) : Opened
    }

    companion object {
        /** sshj's algorithm tables. Built once: construction is slow on a phone. */
        private val config by lazy { DefaultConfig() }

        /**
         * Connect, verify, authenticate and start a shell, blocking. Run off the
         * main thread. On anything but [Opened.Ok] the link session is closed.
         */
        fun open(
            session: Session,
            verifier: HostKeys.Verifier,
            user: String,
            password: CharArray,
            columns: Int,
            rows: Int,
        ): Opened {
            val ssh = SSHClient(config)
            ssh.addHostKeyVerifier(verifier)
            try {
                ssh.connectVia(session.input, session.output)
            } catch (e: IOException) {
                disconnect(ssh, session)
                val verdict = verifier.verdict
                return if (verdict != null && verdict !is HostKeys.Verdict.Match) Opened.HostKey(verdict)
                else Opened.Failed(e.message ?: "could not start SSH")
            }

            // Password first, then keyboard-interactive with the same answer:
            // a stock Debian sshd allows the first, and some hardened configs
            // allow only the second.
            val finder = object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = password.copyOf()
                override fun shouldRetry(resource: Resource<*>?) = false
            }
            try {
                ssh.auth(user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
            } catch (e: UserAuthException) {
                disconnect(ssh, session)
                return Opened.WrongPassword
            } catch (e: IOException) {
                disconnect(ssh, session)
                return Opened.Failed(e.message ?: "authentication failed")
            }

            return try {
                val channel = ssh.startSession()
                channel.allocatePTY("xterm-256color", columns, rows, 0, 0, emptyMap<PTYMode, Int>())
                Opened.Ok(ConsoleShell(ssh, channel, channel.startShell()))
            } catch (e: IOException) {
                disconnect(ssh, session)
                Opened.Failed(e.message ?: "could not start a shell")
            }
        }

        private fun disconnect(ssh: SSHClient, session: Session) {
            try { ssh.disconnect() } catch (_: IOException) {}
            session.close()
        }
    }
}

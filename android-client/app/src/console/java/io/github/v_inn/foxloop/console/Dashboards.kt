package io.github.v_inn.foxloop.console

import android.content.Context

/**
 * A web dashboard on the machine, reached through an SSH local port forward on
 * the terminal's own session -- so it sits behind the same login, and needs no
 * network between phone and machine at all.
 *
 * [localPort] is chosen once, when the dashboard is added, and saved with it.
 * A WebView origin includes the port, so a stable port is what lets a
 * dashboard's local storage -- where many of them keep their login token --
 * survive to the next session. Note what it does **not** separate: cookies are
 * scoped by host alone, port ignored, so two dashboards that use the same
 * cookie name on 127.0.0.1 do share it. Isolating those would mean a different
 * loopback address per dashboard, and a Host header many servers refuse.
 */
internal data class Dashboard(
    val name: String,
    /** The port the dashboard listens on, on the machine's loopback. */
    val remotePort: Int,
    /** Always starts with `/`. */
    val path: String,
    val localPort: Int,
) {
    val url: String get() = "http://$LOOPBACK:$localPort$path"

    companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/** Parsing, validation and storage format. Pure, for the JVM tests. */
internal object DashboardRules {
    /** Where local ports come from when the remote one cannot be reused. */
    private const val SPARE_PORTS_FROM = 20_000

    sealed interface Added {
        class Ok(val dashboard: Dashboard) : Added
        class Invalid(val reason: String) : Added
    }

    fun add(name: String, port: String, path: String, existing: List<Dashboard>): Added {
        val remotePort = port.trim().toIntOrNull()
        if (remotePort == null || remotePort !in 1..65535) {
            return Added.Invalid("The port is a number from 1 to 65535, like 3001.")
        }
        var p = path.trim()
        if ("://" in p) return Added.Invalid("Just the path, like /dashboard -- the address is always this machine.")
        if (p.any { it.isWhitespace() }) return Added.Invalid("The path cannot contain spaces.")
        if (!p.startsWith("/")) p = "/$p"
        val label = clean(name).ifBlank { "Port $remotePort" }
        val local = localPortFor(remotePort, existing.map { it.localPort }.toSet())
        return Added.Ok(Dashboard(label, remotePort, p, local))
    }

    /**
     * The remote port itself when possible, so the address reads like the real
     * one (`127.0.0.1:3001` for Uptime Kuma). Not below 1024, which an app may
     * not bind: port 80 becomes 8080, 443 becomes 8443. Otherwise the first
     * free port from [SPARE_PORTS_FROM], below Linux's ephemeral range so an
     * outgoing connection never holds it.
     */
    fun localPortFor(remotePort: Int, taken: Set<Int>): Int {
        val preferred = if (remotePort >= 1024) remotePort else remotePort + 8000
        if (preferred !in taken && preferred <= 65535) return preferred
        return generateSequence(SPARE_PORTS_FROM) { it + 1 }.first { it !in taken }
    }

    /** One dashboard per line, tab-separated: name, remote port, path, local port. */
    fun encode(list: List<Dashboard>): String =
        list.joinToString("\n") { "${clean(it.name)}\t${it.remotePort}\t${it.path}\t${it.localPort}" }

    fun decode(text: String): List<Dashboard> = text.lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 4) return@mapNotNull null
        val remote = f[1].toIntOrNull() ?: return@mapNotNull null
        val local = f[3].toIntOrNull() ?: return@mapNotNull null
        if (!f[2].startsWith("/")) return@mapNotNull null
        Dashboard(f[0], remote, f[2], local)
    }

    /** Tabs and line breaks are the storage format's separators. */
    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()
}

/** Dashboards per machine, keyed by the AOA serial like the pinned host key. */
internal class DashboardStore(context: Context) {
    private val prefs = context.getSharedPreferences("foxloop_console_dashboards", Context.MODE_PRIVATE)

    fun load(serial: String): List<Dashboard> = DashboardRules.decode(prefs.getString(serial, "") ?: "")

    fun save(serial: String, list: List<Dashboard>) {
        prefs.edit().putString(serial, DashboardRules.encode(list)).apply()
    }
}

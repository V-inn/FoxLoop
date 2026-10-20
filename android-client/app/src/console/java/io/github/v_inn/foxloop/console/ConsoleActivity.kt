package io.github.v_inn.foxloop.console

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.termux.terminal.TerminalOutput
import io.github.v_inn.foxloop.BufferedAccessoryInput
import io.github.v_inn.foxloop.ui.Eyebrow
import io.github.v_inn.foxloop.ui.FoxLoopTheme
import io.github.v_inn.foxloop.ui.FoxLoopTokens
import io.github.v_inn.foxloop.ui.FoxLoopType
import io.github.v_inn.foxloop.ui.Note
import io.github.v_inn.foxloop.ui.PrimaryButton
import io.github.v_inn.foxloop.ui.SecondaryButton
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "FoxLoopConsole"
private const val ACTION_PERMISSION = "io.github.v_inn.foxloop.console.USB_PERMISSION"

/** What console-daemon/src/main.rs sends; accessory_filter.xml matches the same. */
private const val MANUFACTURER = "FoxLoop"
private const val MODEL = "FoxLoop Console"

private const val PLUG_IN =
    "Plug this phone into the machine running foxloop-console, and unlock it. The app opens by itself."

private sealed interface Screen {
    data class Waiting(val message: String) : Screen
    data class Busy(val message: String) : Screen
    data class Login(val error: String? = null) : Screen
    class TrustHost(val verdict: HostKeys.Verdict) : Screen
    data object Terminal : Screen
    /** The saved dashboards, while the terminal session runs underneath. */
    data object Dashboards : Screen
    data class Web(val dashboard: Dashboard) : Screen
    data class Ended(val message: String) : Screen
}

/**
 * FoxLoop Console's one screen: a terminal on a headless machine, over the
 * cable.
 *
 * Accessory -> [AccessoryLink] -> a [Session] per attempt -> sshj in
 * [ConsoleShell] -> the terminal. The daemon on the other end is a pipe to that
 * machine's sshd, so logging in here is logging in over SSH, password checks
 * and rate limits included, and it works with no network at all.
 */
class ConsoleActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var hostKeys: HostKeys
    private lateinit var dashboardStore: DashboardStore
    private lateinit var terminal: ConsoleTerminalView
    private val main = Handler(Looper.getMainLooper())
    // Connecting and logging in block; one at a time, in order, off the main thread.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "console-worker") }

    private var link: AccessoryLink? = null
    private var shell: ConsoleShell? = null
    private var serial = ""
    @Volatile private var sessionEndReason: String? = null

    /** Held only between a login attempt and its host-key prompt; wiped after. */
    private var pendingUser = ""
    private var pendingPassword = CharArray(0)

    private var screen by mutableStateOf<Screen>(Screen.Waiting(PLUG_IN))
    private var hostname by mutableStateOf("")
    private var status by mutableStateOf<ConsoleProtocol.Status?>(null)
    private var ctrlLatched by mutableStateOf(false)
    private var altLatched by mutableStateOf(false)

    /** True while a shell is open: dashboards ride its SSH connection. */
    private var loggedIn by mutableStateOf(false)
    private var dashboards by mutableStateOf<List<Dashboard>>(emptyList())
    /** Kept per local port while the session lasts, so switching back to the
     * terminal and returning does not reload a dashboard or drop its socket. */
    private val webViews = mutableMapOf<Int, WebView>()
    private var webNotice by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usbManager = getSystemService(UsbManager::class.java)
        hostKeys = HostKeys(this)
        dashboardStore = DashboardStore(this)
        terminal = ConsoleTerminalView(this).apply { host = terminalHost }

        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
                addAction(ACTION_PERMISSION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Back leaves the session running instead of tearing it down; closing
        // it is what `exit` is for.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (val s = screen) {
                    Screen.Terminal -> moveTaskToBack(true)
                    Screen.Dashboards -> showTerminal()
                    is Screen.Web -> {
                        val web = webViews[s.dashboard.localPort]
                        if (web?.canGoBack() == true) web.goBack() else screen = Screen.Dashboards
                    }
                    else -> finish()
                }
            }
        })

        setContent { FoxLoopTheme { ConsoleScreen() } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        worker.shutdownNow()
        dropDashboards()
        shell?.close()
        link?.close("the app was closed")
        wipePassword()
        super.onDestroy()
    }

    // --- connecting ------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        if (link?.isClosed == false) return // already on this cable
        val accessory = intent?.let {
            IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } ?: usbManager.accessoryList?.firstOrNull { it.manufacturer == MANUFACTURER && it.model == MODEL }
        when {
            accessory == null -> screen = Screen.Waiting(PLUG_IN)
            usbManager.hasPermission(accessory) -> open(accessory)
            else -> {
                // Reached after a reinstall, which drops the grant that came with
                // the attach intent. The display app has no way back from that
                // but a replug; here the system can simply be asked.
                val pending = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                usbManager.requestPermission(accessory, pending)
                screen = Screen.Waiting("Allow FoxLoop Console to use the cable, in the dialog.")
            }
        }
    }

    private fun open(accessory: UsbAccessory) {
        // Throws, rather than returning null, when `accessory` is not the one
        // attached now. Seen on the tablet: after switching from the display to
        // the console without a replug, a permission broadcast arrived carrying
        // the console accessory while the system still called the display's
        // current, and the uncaught IllegalArgumentException killed the app.
        val pfd = try {
            usbManager.openAccessory(accessory)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "openAccessory refused: ${e.message}")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "openAccessory refused: ${e.message}")
            null
        }
        if (pfd == null) {
            screen = Screen.Waiting("Could not open the cable. Unplug it and plug it back in.")
            return
        }
        serial = accessory.serial ?: ""
        val fd = pfd.fileDescriptor
        val l = AccessoryLink(
            BufferedAccessoryInput(FileInputStream(fd), pfd),
            FileOutputStream(fd),
            onLost = { reason -> main.post { onLinkLost(reason) } },
        )
        link = l
        l.start()
        screen = Screen.Busy("Connecting…")
        worker.execute { probe(l) }
    }

    /**
     * A session opened only to learn the machine's name, then closed: the login
     * form should say which machine it logs into. Closed straight away because
     * the daemon connects to sshd on HELLO, and sshd drops a connection that
     * has not authenticated within its LoginGraceTime -- two minutes is less
     * than someone may spend on the form.
     */
    private fun probe(l: AccessoryLink) {
        val session = l.openSession(object : Session.Listener {})
        val welcome = try {
            session.awaitWelcome()
        } catch (e: IOException) {
            main.post { if (link === l) screen = Screen.Ended(e.message ?: "no answer") }
            return
        }
        session.close()
        main.post {
            if (link !== l) return@post
            hostname = welcome.hostname
            screen = Screen.Login()
        }
    }

    private fun login(user: String, password: CharArray) {
        val l = link ?: return
        pendingUser = user
        pendingPassword = password
        screen = Screen.Busy("Logging in to $hostname…")
        sessionEndReason = null
        // A fresh screen now, so the PTY can be asked for at its real size. The
        // view is already laid out underneath this panel.
        terminal.attach(terminalOutput)
        val columns = terminal.emulator?.mColumns ?: 80
        val rows = terminal.emulator?.mRows ?: 24
        val verifier = hostKeys.verifier(serial)
        val secret = password.copyOf()
        worker.execute {
            val session = l.openSession(sessionListener)
            val opened = try {
                val welcome = session.awaitWelcome()
                main.post { hostname = welcome.hostname }
                ConsoleShell.open(session, verifier, user, secret, columns, rows)
            } catch (e: IOException) {
                session.close()
                ConsoleShell.Opened.Failed(e.message ?: "no answer")
            } finally {
                secret.fill('\u0000')
            }
            main.post { onOpened(opened) }
        }
    }

    private fun onOpened(opened: ConsoleShell.Opened) {
        when (opened) {
            is ConsoleShell.Opened.Ok -> {
                hostKeys.rememberUser(serial, pendingUser)
                wipePassword()
                startShell(opened.shell)
            }
            is ConsoleShell.Opened.HostKey -> screen = Screen.TrustHost(opened.verdict)
            ConsoleShell.Opened.WrongPassword -> {
                wipePassword()
                screen = Screen.Login("That user name and password were not accepted.")
            }
            is ConsoleShell.Opened.Failed -> {
                wipePassword()
                screen = if (link?.isClosed == false) Screen.Login(opened.reason) else Screen.Ended(opened.reason)
            }
        }
    }

    private fun startShell(sh: ConsoleShell) {
        shell = sh
        loggedIn = true
        dashboards = dashboardStore.load(serial)
        screen = Screen.Terminal
        terminal.showKeyboard()
        thread(name = "console-shell-output") {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = sh.output.read(buf)
                    if (n < 0) break
                    val chunk = buf.copyOf(n)
                    main.post { terminal.append(chunk, n) }
                }
            } catch (e: IOException) {
                Log.i(TAG, "shell output ended: ${e.message}")
            }
            main.post {
                if (shell !== sh) return@post
                shell = null
                dropDashboards()
                sh.close()
                screen = Screen.Ended(sessionEndReason?.let { "The session on $hostname ended: $it." }
                    ?: "Logged out of $hostname.")
            }
        }
    }

    private fun onLinkLost(reason: String) {
        link = null
        dropDashboards()
        shell?.close()
        shell = null
        status = null
        screen = Screen.Ended("Lost the connection: $reason.")
    }

    /** From an ended session: log in again on the same cable, or reopen it. */
    private fun reconnect() {
        val l = link
        when {
            l == null || l.isClosed -> handleIntent(null)
            hostname.isEmpty() -> {
                screen = Screen.Busy("Connecting…")
                worker.execute { probe(l) }
            }
            else -> screen = Screen.Login()
        }
    }

    private fun wipePassword() {
        pendingPassword.fill('\u0000')
        pendingPassword = CharArray(0)
    }

    private val sessionListener = object : Session.Listener {
        override fun onStatus(status: ConsoleProtocol.Status) {
            main.post { this@ConsoleActivity.status = status }
        }

        override fun onEnded(reason: String) {
            sessionEndReason = reason
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        // Looked up again rather than taken from the intent,
                        // which can describe an accessory that is no longer
                        // the attached one.
                        handleIntent(null)
                    } else {
                        screen = Screen.Waiting("FoxLoop Console needs the cable to do anything. Unplug it and plug it back in to be asked again.")
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    dropDashboards()
                    shell?.close()
                    shell = null
                    link?.close("the cable was unplugged")
                    link = null
                    status = null
                    hostname = ""
                    screen = Screen.Waiting(PLUG_IN)
                }
            }
        }
    }

    // --- dashboards ----------------------------------------------------------------

    private fun showTerminal() {
        screen = Screen.Terminal
        terminal.showKeyboard()
    }

    private fun addDashboard(d: Dashboard) {
        dashboards = dashboards + d
        dashboardStore.save(serial, dashboards)
    }

    private fun removeDashboard(d: Dashboard) {
        dashboards = dashboards - d
        dashboardStore.save(serial, dashboards)
        webViews.remove(d.localPort)?.destroy()
    }

    /**
     * Forward first, then load: the WebView's first request must find something
     * listening on the phone's side, or it fails before the page ever had a
     * chance. A dashboard opened before keeps its page and its sockets.
     */
    private fun openDashboard(d: Dashboard) {
        val sh = shell ?: return
        webNotice = null
        screen = Screen.Web(d)
        val fresh = d.localPort !in webViews
        val web = webViewFor(d)
        worker.execute {
            try {
                sh.forward(d.localPort, d.remotePort)
                if (fresh) main.post { web.loadUrl(d.url) }
            } catch (e: IOException) {
                main.post { webNotice = "Could not listen on port ${d.localPort} on this phone: ${e.message}" }
            }
        }
    }

    private fun webViewFor(d: Dashboard): WebView = webViews.getOrPut(d.localPort) {
        WebView(this).apply {
            settings.javaScriptEnabled = true // every dashboard worth the name needs it
            settings.domStorageEnabled = true // and many keep their login there
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            webViewClient = DashboardClient(d)
        }
    }

    /** Session over: its forwards are closing with it, so are the pages on them. */
    private fun dropDashboards() {
        loggedIn = false
        webNotice = null
        for (w in webViews.values) {
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        webViews.clear()
    }

    /**
     * Keeps a dashboard on its own forward. A main-frame navigation anywhere
     * else -- typically a redirect to a public "base URL" the app was configured
     * with -- would leave the cable for the phone's own network, so it is
     * stopped and explained instead.
     */
    private inner class DashboardClient(private val d: Dashboard) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.scheme == "http" && u.host == Dashboard.LOOPBACK && u.port == d.localPort) return false
            if (request.isForMainFrame) {
                webNotice = "${d.name} tried to open $u, which is not on the cable. If it has a " +
                    "public or base URL setting, leaving that empty usually fixes this."
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            webNotice = null
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            webNotice = "Nothing answered on port ${d.remotePort} of $hostname (${error.description}). " +
                "Is it running, and listening on 127.0.0.1 or on all addresses? A Docker container's " +
                "port has to be published, e.g. -p ${d.remotePort}:${d.remotePort}."
        }
    }

    // --- terminal plumbing -------------------------------------------------------

    /** What the emulator sends: keystrokes, and its answers to terminal queries. */
    private val terminalOutput = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            shell?.send(data.copyOfRange(offset, offset + count))
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() = terminal.invalidate()
    }

    private val terminalHost = object : ConsoleTerminalView.Host {
        override fun onTerminalSize(columns: Int, rows: Int) {
            shell?.resize(columns, rows)
        }

        override fun readCtrl(): Boolean = ctrlLatched.also { ctrlLatched = false }
        override fun readAlt(): Boolean = altLatched.also { altLatched = false }
    }

    // --- UI ----------------------------------------------------------------------

    @Composable
    private fun ConsoleScreen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(FoxLoopTokens.Slate)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            if (hostname.isNotEmpty()) StatusLine()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Always composed, so it has a size -- and the PTY a real one --
                // before the first login. Covered by the panel until then.
                AndroidView(factory = { terminal }, modifier = Modifier.fillMaxSize())
                val s = screen
                if (s != Screen.Terminal) {
                    // Or the soft keyboard keeps typing into the hidden terminal.
                    SideEffect { if (terminal.hasFocus()) terminal.clearFocus() }
                    if (s is Screen.Web) WebPane(s.dashboard) else Panel(s)
                }
            }
            if (screen == Screen.Terminal) KeysRow()
        }
    }

    @Composable
    private fun StatusLine() {
        val s = status
        val power = s?.let { PowerReadout.power(it.throttled) }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FoxLoopTokens.SpaceSm, vertical = FoxLoopTokens.SpaceXs),
            horizontalArrangement = Arrangement.spacedBy(FoxLoopTokens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(hostname, style = FoxLoopType.data.copy(color = FoxLoopTokens.Chalk))
            if (power != null) {
                val color = if (power.level == PowerReadout.Level.Fine) FoxLoopTokens.Graphite else FoxLoopTokens.CopperLit
                val chip = if (power.level == PowerReadout.Level.Alert) {
                    Modifier.border(1.dp, FoxLoopTokens.Copper, FoxLoopTokens.ChipShape).padding(horizontal = 6.dp)
                } else {
                    Modifier
                }
                BasicText(power.text, modifier = chip, style = FoxLoopType.data.copy(color = color))
            }
            s?.let { PowerReadout.temperature(it.cpuTempMilli) }?.let {
                BasicText(it, style = FoxLoopType.data.copy(color = FoxLoopTokens.Graphite))
            }
            if (loggedIn) {
                Spacer(Modifier.weight(1f))
                val onTerminal = screen == Screen.Terminal
                BasicText(
                    if (onTerminal) "Dashboards" else "Terminal",
                    modifier = Modifier
                        .clip(FoxLoopTokens.ChipShape)
                        .clickable(role = Role.Button) { if (onTerminal) screen = Screen.Dashboards else showTerminal() }
                        .padding(horizontal = FoxLoopTokens.SpaceSm, vertical = FoxLoopTokens.SpaceXs),
                    style = FoxLoopType.data.copy(color = FoxLoopTokens.CopperLit),
                )
            }
        }
    }

    @Composable
    private fun WebPane(d: Dashboard) {
        Column(Modifier.fillMaxSize().background(FoxLoopTokens.Slate)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FoxLoopTokens.SpaceSm, vertical = FoxLoopTokens.SpaceXs),
                horizontalArrangement = Arrangement.spacedBy(FoxLoopTokens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(d.name, style = FoxLoopType.label.copy(color = FoxLoopTokens.Chalk))
                BasicText(":${d.remotePort}${d.path}", style = FoxLoopType.data.copy(color = FoxLoopTokens.Graphite))
                Spacer(Modifier.weight(1f))
                BasicText(
                    "Reload",
                    modifier = Modifier
                        .clip(FoxLoopTokens.ChipShape)
                        .clickable(role = Role.Button) { openDashboard(d); webViews[d.localPort]?.reload() }
                        .padding(horizontal = FoxLoopTokens.SpaceSm, vertical = FoxLoopTokens.SpaceXs),
                    style = FoxLoopType.data.copy(color = FoxLoopTokens.CopperLit),
                )
            }
            webNotice?.let {
                BasicText(
                    it,
                    modifier = Modifier.fillMaxWidth().background(FoxLoopTokens.Raise).padding(FoxLoopTokens.SpaceSm),
                    style = FoxLoopType.caption.copy(color = FoxLoopTokens.CopperLit),
                )
            }
            // Keyed, and detached from any previous parent: the same WebView is
            // handed back each time this dashboard is shown, and a view that
            // still has a parent cannot be added to a new one.
            key(d.localPort) {
                AndroidView(
                    factory = { webViewFor(d).also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Composable
    private fun DashboardList() {
        Eyebrow("Dashboards on $hostname")
        Body(
            "Web pages served on $hostname, opened through the SSH login you are already in. " +
                "They travel over the cable; no network is involved."
        )
        for (d in dashboards) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(FoxLoopTokens.RowShape)
                    .background(FoxLoopTokens.Raise)
                    .clickable(role = Role.Button) { openDashboard(d) }
                    .padding(FoxLoopTokens.SpaceMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(d.name, style = FoxLoopType.label.copy(color = FoxLoopTokens.Chalk))
                    BasicText(":${d.remotePort}${d.path}", style = FoxLoopType.data.copy(color = FoxLoopTokens.Graphite))
                }
                BasicText(
                    "Remove",
                    modifier = Modifier
                        .clip(FoxLoopTokens.ChipShape)
                        .clickable(role = Role.Button) { removeDashboard(d) }
                        .padding(FoxLoopTokens.SpaceSm),
                    style = FoxLoopType.caption.copy(color = FoxLoopTokens.Graphite),
                )
            }
        }
        AddDashboardForm()
    }

    @Composable
    private fun AddDashboardForm() {
        var name by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        var path by remember { mutableStateOf("/") }
        var error by remember { mutableStateOf<String?>(null) }
        Eyebrow("Add a dashboard")
        Field(
            value = name,
            onValueChange = { name = it },
            label = "Name",
            keyboard = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Field(
            value = port,
            onValueChange = { port = it },
            label = "Port on $hostname",
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        Field(
            value = path,
            onValueChange = { path = it },
            label = "Path",
            keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        )
        if (error != null) BasicText(error!!, style = FoxLoopType.caption.copy(color = FoxLoopTokens.CopperLit))
        SecondaryButton("Add", onClick = {
            when (val r = DashboardRules.add(name, port, path, dashboards)) {
                is DashboardRules.Added.Ok -> {
                    addDashboard(r.dashboard)
                    name = ""; port = ""; path = "/"; error = null
                }
                is DashboardRules.Added.Invalid -> error = r.reason
            }
        }, modifier = Modifier.fillMaxWidth())
        Note(
            "The dashboard has to listen on $hostname itself: on 127.0.0.1 or on all addresses. " +
                "A Docker container's port must be published to the host (-p 3001:3001). " +
                "If it redirects to a public address, clear its base URL setting."
        )
    }

    @Composable
    private fun Panel(s: Screen) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FoxLoopTokens.Slate)
                .verticalScroll(rememberScrollState())
                .padding(FoxLoopTokens.SpaceLg),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FoxLoopTokens.SpaceMd),
            ) {
                when (s) {
                    is Screen.Waiting -> {
                        Eyebrow("FoxLoop Console")
                        Body(s.message)
                    }
                    is Screen.Busy -> {
                        Eyebrow("FoxLoop Console")
                        Body(s.message)
                    }
                    is Screen.Login -> LoginForm(s.error)
                    is Screen.TrustHost -> TrustPrompt(s.verdict)
                    is Screen.Ended -> {
                        Eyebrow(hostname.ifEmpty { "FoxLoop Console" })
                        Body(s.message)
                        PrimaryButton(
                            if (hostname.isEmpty()) "Try again" else "Log in again",
                            onClick = ::reconnect,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Screen.Dashboards -> DashboardList()
                    Screen.Terminal, is Screen.Web -> {}
                }
            }
        }
    }

    @Composable
    private fun LoginForm(error: String?) {
        var user by remember { mutableStateOf(hostKeys.lastUser(serial)) }
        var password by remember { mutableStateOf("") }
        val submit = {
            if (user.isNotBlank() && password.isNotEmpty()) {
                val chars = password.toCharArray()
                password = ""
                login(user.trim(), chars)
            }
        }
        Eyebrow("Log in to $hostname")
        Field(
            value = user,
            onValueChange = { user = it },
            label = "User name",
            keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        Field(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            secret = true,
            keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { submit() }),
        )
        if (error != null) BasicText(error, style = FoxLoopType.caption.copy(color = FoxLoopTokens.CopperLit))
        PrimaryButton("Log in", onClick = { submit() }, enabled = user.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth())
        Note("Checked by sshd on $hostname, like any SSH login. Nothing is stored but the user name.")
    }

    @Composable
    private fun TrustPrompt(verdict: HostKeys.Verdict) {
        val offered = HostKeys.fingerprint(verdict.offered)
        when (verdict) {
            is HostKeys.Verdict.Mismatch -> {
                Eyebrow("Host key changed")
                Body(
                    "$hostname answered with a different SSH host key than last time. If the machine was " +
                        "reinstalled that is expected. If not, something else is answering in its place."
                )
                Fingerprint("Saved", HostKeys.fingerprint(verdict.saved))
                Fingerprint("Now", offered)
                Note("To check, run on the machine itself: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub")
                SecondaryButton("Forget the saved key", onClick = {
                    hostKeys.forget(serial)
                    login(pendingUser, pendingPassword)
                }, modifier = Modifier.fillMaxWidth())
                PrimaryButton("Cancel", onClick = { wipePassword(); screen = Screen.Login() }, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                Eyebrow("First connection to $hostname")
                Body("This machine's SSH host key has not been seen before. Its fingerprint is:")
                Fingerprint(null, offered)
                Note("To check, run on the machine itself: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub")
                PrimaryButton("Trust it and log in", onClick = {
                    hostKeys.trust(serial, verdict.offered)
                    login(pendingUser, pendingPassword)
                }, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Cancel", onClick = { wipePassword(); screen = Screen.Login() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun Body(text: String) = BasicText(text, style = FoxLoopType.body.copy(color = FoxLoopTokens.Chalk))

    @Composable
    private fun Fingerprint(label: String?, value: String) {
        Column {
            if (label != null) BasicText(label, style = FoxLoopType.caption.copy(color = FoxLoopTokens.Graphite))
            BasicText(value, style = FoxLoopType.data.copy(color = FoxLoopTokens.Chalk))
        }
    }

    @Composable
    private fun Field(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        keyboard: KeyboardOptions,
        secret: Boolean = false,
        actions: KeyboardActions = KeyboardActions.Default,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoxLoopTokens.SpaceXs)) {
            BasicText(label, style = FoxLoopType.label.copy(color = FoxLoopTokens.Graphite))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = FoxLoopType.body.copy(color = FoxLoopTokens.Chalk),
                cursorBrush = SolidColor(FoxLoopTokens.Copper),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = keyboard,
                keyboardActions = actions,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label }
                    .clip(FoxLoopTokens.RowShape)
                    .background(FoxLoopTokens.Raise)
                    .padding(horizontal = FoxLoopTokens.SpaceMd, vertical = FoxLoopTokens.SpaceMd),
            )
        }
    }

    /**
     * The keys a phone keyboard lacks or hides. Written here rather than taken
     * from Termux: its extra-keys row lives in termux-shared, which is GPLv3,
     * outside the Apache exception the two terminal modules have.
     *
     * Ctrl and Alt latch for the next key, typed or tapped, then release.
     */
    @Composable
    private fun KeysRow() {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(FoxLoopTokens.Recess),
        ) {
            val key = Modifier.weight(1f)
            Key("ESC", key) { terminal.sendKey(android.view.KeyEvent.KEYCODE_ESCAPE) }
            Toggle("CTRL", ctrlLatched, key) { ctrlLatched = !ctrlLatched }
            Toggle("ALT", altLatched, key) { altLatched = !altLatched }
            Key("TAB", key) { terminal.sendKey(android.view.KeyEvent.KEYCODE_TAB) }
            Key("←", key, repeats = true, description = "Left") { terminal.sendKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
            Key("↑", key, repeats = true, description = "Up") { terminal.sendKey(android.view.KeyEvent.KEYCODE_DPAD_UP) }
            Key("↓", key, repeats = true, description = "Down") { terminal.sendKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
            Key("→", key, repeats = true, description = "Right") { terminal.sendKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
            for (c in listOf("|", "~", "/", "-")) {
                Key(c, key) { terminal.sendCodePoint(c.codePointAt(0)) }
            }
        }
    }

    @Composable
    private fun Key(
        label: String,
        modifier: Modifier,
        repeats: Boolean = false,
        description: String = label,
        action: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        Box(
            modifier
                .fillMaxSize()
                .semantics { role = Role.Button; contentDescription = description }
                .pointerInput(repeats) {
                    detectTapGestures(onPress = {
                        action()
                        val repeater = if (repeats) scope.launch {
                            delay(400)
                            while (isActive) {
                                action()
                                delay(50)
                            }
                        } else null
                        tryAwaitRelease()
                        repeater?.cancel()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = FoxLoopType.data.copy(color = FoxLoopTokens.Chalk))
        }
    }

    @Composable
    private fun Toggle(label: String, on: Boolean, modifier: Modifier, onToggle: () -> Unit) {
        Box(
            modifier
                .fillMaxSize()
                .padding(3.dp)
                .clip(FoxLoopTokens.ChipShape)
                .background(if (on) FoxLoopTokens.Copper else FoxLoopTokens.Recess)
                .semantics { role = Role.Switch; contentDescription = "$label, ${if (on) "on" else "off"}" }
                .pointerInput(Unit) { detectTapGestures(onTap = { onToggle() }) },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = FoxLoopType.data.copy(color = if (on) FoxLoopTokens.Recess else FoxLoopTokens.Chalk),
            )
        }
    }
}

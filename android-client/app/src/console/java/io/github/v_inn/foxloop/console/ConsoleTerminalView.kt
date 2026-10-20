package io.github.v_inn.foxloop.console

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer
import java.util.Properties
import kotlin.math.ceil
import kotlin.math.max

/**
 * A terminal screen over Termux's `TerminalEmulator` and `TerminalRenderer`,
 * without Termux's `TerminalView`.
 *
 * `TerminalView` only works attached to a `TerminalSession`, which is a final
 * class that forks a local shell through JNI. Here the shell is at the far end
 * of an SSH channel, so this view drives the emulator directly: bytes from the
 * shell go into [append], and everything the emulator wants to send back --
 * keystrokes, answers to the terminal queries vim and htop make -- leaves
 * through the [TerminalOutput] given to [attach].
 *
 * Keyboard handling follows `TerminalView`'s own, including the workarounds it
 * carries for real keyboards; the notes on each say which.
 *
 * Everything here runs on the main thread, emulator included.
 */
internal class ConsoleTerminalView(context: Context) : View(context) {

    interface Host {
        /** The terminal's size changed; tell the remote PTY. */
        fun onTerminalSize(columns: Int, rows: Int)
        /** The latched modifiers from the keys row. Reading one releases it. */
        fun readCtrl(): Boolean
        fun readAlt(): Boolean
    }

    var host: Host? = null

    var emulator: TerminalEmulator? = null
        private set
    private var output: TerminalOutput? = null

    private var textSizePx = sp(DEFAULT_TEXT_SP)
    private var renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
    private var lineSpacingAndAscent = lineSpacingAndAscent(textSizePx)

    /** 0 is the live screen; negative rows look back into the scrollback. */
    private var topRow = 0
    private var scrollRemainder = 0f
    private var scaleAccumulator = 1f
    private var combiningAccent = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND])
    }

    /** Starts a fresh screen whose replies go to `output`. */
    fun attach(output: TerminalOutput) {
        this.output = output
        emulator = null
        topRow = 0
        updateSize()
        invalidate()
    }

    /** Bytes from the shell. */
    fun append(bytes: ByteArray, length: Int) {
        val e = emulator ?: return
        e.append(bytes, length)
        // Hold the reader's place in the scrollback while output streams in.
        if (topRow != 0) topRow = (topRow - e.scrollCounter).coerceIn(-e.screen.activeTranscriptRows, 0)
        e.clearScrollCounter()
        invalidate()
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    // --- size ----------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateSize()

    private fun updateSize() {
        val out = output ?: return
        if (width == 0 || height == 0) return
        val cellWidth = renderer.fontWidth
        val lineSpacing = renderer.fontLineSpacing
        val columns = max(4, (width / cellWidth).toInt())
        val rows = max(4, (height - lineSpacingAndAscent) / lineSpacing)
        val e = emulator
        if (e == null) {
            emulator = TerminalEmulator(out, columns, rows, cellWidth.toInt(), lineSpacing, TRANSCRIPT_ROWS, QuietClient)
        } else if (e.mColumns != columns || e.mRows != rows) {
            e.resize(columns, rows, cellWidth.toInt(), lineSpacing)
            topRow = 0
        } else {
            return
        }
        host?.onTerminalSize(columns, rows)
        invalidate()
    }

    private fun setTextSize(px: Int) {
        val clamped = px.coerceIn(sp(MIN_TEXT_SP), sp(MAX_TEXT_SP))
        if (clamped == textSizePx) return
        textSizePx = clamped
        renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
        lineSpacingAndAscent = lineSpacingAndAscent(textSizePx)
        updateSize()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val e = emulator ?: return
        renderer.render(e, canvas, topRow, -1, -1, -1, -1)
    }

    // --- touch ---------------------------------------------------------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val emu = emulator
            if (emu != null && emu.isMouseTrackingActive) {
                val (col, row) = cellAt(e)
                emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col, row, true)
                emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col, row, false)
            }
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollByPixels(dy, e2)
            return true
        }

        override fun onLongPress(e: MotionEvent) = paste()
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleAccumulator *= detector.scaleFactor
            if (scaleAccumulator > 1.1f || scaleAccumulator < 0.9f) {
                setTextSize(textSizePx + if (scaleAccumulator > 1f) sp(1f) else -sp(1f))
                scaleAccumulator = 1f
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    /**
     * What a vertical drag means depends on what is on screen, as in Termux: a
     * program tracking the mouse gets wheel events, a full-screen program
     * (less, man) gets arrow keys, and a plain shell scrolls the scrollback.
     */
    private fun scrollByPixels(dy: Float, at: MotionEvent) {
        val e = emulator ?: return
        scrollRemainder += dy
        val rows = (scrollRemainder / renderer.fontLineSpacing).toInt()
        if (rows == 0) return
        scrollRemainder -= rows * renderer.fontLineSpacing
        val older = rows < 0
        repeat(kotlin.math.abs(rows)) {
            when {
                e.isMouseTrackingActive -> {
                    val (col, row) = cellAt(at)
                    val button = if (older) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                    e.sendMouseEvent(button, col, row, true)
                }
                e.isAlternateBufferActive -> sendKey(if (older) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
                else -> topRow = (topRow + if (older) -1 else 1).coerceIn(-e.screen.activeTranscriptRows, 0)
            }
        }
        invalidate()
    }

    /** 1-based, as the mouse protocols count. */
    private fun cellAt(e: MotionEvent): Pair<Int, Int> {
        val col = (e.x / renderer.fontWidth).toInt() + 1
        val row = ((e.y - lineSpacingAndAscent) / renderer.fontLineSpacing).toInt() + 1
        return col to row
    }

    private fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) emulator?.paste(text) // bracketed when the program asked for it
    }

    // --- keyboard ------------------------------------------------------------

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A visible-password field with no suggestions: keyboards then send
        // characters as they are typed, instead of holding a word back to
        // autocorrect it -- Termux's "enforce char based input", which it
        // recommends for Samsung's keyboard in particular. And no learning,
        // because what is typed into a shell includes passwords.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

        return object : BaseInputConnection(this, true) {
            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                sendText(editable)
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                sendText(editable)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Samsung's keyboard with spell checking on asks to delete more
                // than one character at a time.
                repeat(beforeLength) { sendKey(KeyEvent.KEYCODE_DEL) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            private fun sendText(text: Editable?) {
                if (text == null) return
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    var codePoint = if (Character.isHighSurrogate(c) && i + 1 < text.length) {
                        Character.toCodePoint(c, text[++i])
                    } else {
                        c.code
                    }
                    var ctrl = false
                    if (codePoint <= 31 && codePoint != 27) {
                        // AOSP-derived keyboards send Enter as "\n" text; a
                        // terminal wants "\r". Other control characters arrive
                        // from keyboards with a ctrl key of their own.
                        if (codePoint == '\n'.code) codePoint = '\r'.code
                        ctrl = true
                        codePoint = when (codePoint) {
                            31 -> '_'.code
                            30 -> '^'.code
                            29 -> ']'.code
                            28 -> '\\'.code
                            else -> codePoint + 96
                        }
                    }
                    inputCodePoint(codePoint, ctrl, false, fromHardware = false)
                    i++
                }
                text.clear()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val ctrl = event.isCtrlPressed || host?.readCtrl() == true
        val leftAlt = event.metaState and KeyEvent.META_ALT_LEFT_ON != 0 || host?.readAlt() == true
        var keyMod = 0
        if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAlt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK
        if (!event.isFunctionPressed && sendKeyCode(keyCode, keyMod)) return true

        // Ctrl is applied by inputCodePoint; left Alt becomes an ESC prefix
        // there. Right Alt (AltGr) stays, to compose characters.
        var clear = KeyEvent.META_CTRL_MASK
        if (event.metaState and KeyEvent.META_ALT_RIGHT_ON == 0) {
            clear = clear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var result = event.getUnicodeChar(event.metaState and clear.inv())
        if (result == 0) return super.onKeyDown(keyCode, event)

        if (result and KeyCharacterMap.COMBINING_ACCENT != 0) {
            if (combiningAccent != 0) inputCodePoint(combiningAccent, ctrl, leftAlt, fromHardware = true)
            combiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            if (combiningAccent != 0) {
                val combined = KeyCharacterMap.getDeadChar(combiningAccent, result)
                if (combined > 0) result = combined
                combiningAccent = 0
            }
            inputCodePoint(result, ctrl, leftAlt, fromHardware = true)
        }
        return true
    }

    /** A key from the keys row, with whatever modifiers are latched there. */
    fun sendKey(keyCode: Int) {
        var keyMod = 0
        if (host?.readCtrl() == true) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (host?.readAlt() == true) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        sendKeyCode(keyCode, keyMod)
    }

    /** A character from the keys row. */
    fun sendCodePoint(codePoint: Int) = inputCodePoint(codePoint, false, false, fromHardware = false)

    private fun sendKeyCode(keyCode: Int, keyMod: Int): Boolean {
        val e = emulator ?: return false
        val code = KeyHandler.getCode(keyCode, keyMod, e.isCursorKeysApplicationMode, e.isKeypadApplicationMode)
            ?: return false
        write(code.toByteArray(Charsets.UTF_8))
        return true
    }

    private fun inputCodePoint(codePointIn: Int, ctrlIn: Boolean, altIn: Boolean, fromHardware: Boolean) {
        var codePoint = codePointIn
        val ctrl = ctrlIn || host?.readCtrl() == true
        val alt = altIn || host?.readAlt() == true
        if (ctrl) {
            codePoint = when (codePoint) {
                in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
                in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
                ' '.code, '2'.code -> 0
                '['.code, '3'.code -> 27
                '\\'.code, '4'.code -> 28
                ']'.code, '5'.code -> 29
                '^'.code, '6'.code -> 30
                '_'.code, '7'.code, '/'.code -> 31
                '8'.code -> 127
                else -> codePoint
            }
        }
        if (fromHardware) {
            // Bluetooth keyboards send spacing modifier letters where a
            // terminal program expects ASCII.
            codePoint = when (codePoint) {
                0x02DC -> '~'.code
                0x02CB -> '`'.code
                0x02C6 -> '^'.code
                else -> codePoint
            }
        }
        val text = String(Character.toChars(codePoint))
        write(((if (alt) "\u001b" else "") + text).toByteArray(Charsets.UTF_8))
    }

    private fun write(bytes: ByteArray) {
        val out = output ?: return
        out.write(bytes, 0, bytes.size)
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun sp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics).toInt()

    /** Mirrors TerminalRenderer's own arithmetic, which keeps this value private. */
    private fun lineSpacingAndAscent(textSize: Int): Int {
        val paint = Paint().apply {
            typeface = Typeface.MONOSPACE
            this.textSize = textSize.toFloat()
        }
        return ceil(paint.fontSpacing).toInt() + ceil(paint.ascent()).toInt()
    }

    /** The emulator logs and asks about cursor style through this; nothing else. */
    private object QuietClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(TAG, "$message") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$message") }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(TAG, "$message", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, "emulator", e) }
    }

    companion object {
        private const val TAG = "FoxLoopConsole"
        private const val DEFAULT_TEXT_SP = 13f
        private const val MIN_TEXT_SP = 8f
        private const val MAX_TEXT_SP = 32f
        private const val TRANSCRIPT_ROWS = 2_000

        init {
            // The FoxLoop palette's chalk on a ground darker than its graphite,
            // with the copper accent as the cursor. Global to the process,
            // which in this app holds only this view.
            TerminalColors.COLOR_SCHEME.updateWith(Properties().apply {
                setProperty("background", "#1d2125")
                setProperty("foreground", "#e8e6e1")
                setProperty("cursor", "#d08c4a")
            })
        }
    }
}

package dev.scrcpyandroid.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.scrcpyandroid.scrcpy.ControlMessages

/**
 * Invisible 1x1 text editor that forwards soft-keyboard input to the remote device:
 * committed text as inject-text messages, deletions as KEYCODE_DEL, key events as keycodes.
 * Composing text is never sent until the IME commits it.
 */
class RemoteInputView(context: Context, private val send: (ByteArray) -> Unit) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return RemoteInputConnection()
    }

    /** Focuses this view and shows the soft keyboard. */
    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.restartInput(this)
        imm.showSoftInput(this, 0)
    }

    /**
     * Hardware key events (physical/Bluetooth keyboard, `input text`) bypass the InputConnection and
     * arrive here. Forward them, except BACK (handled by the screen's BackHandler) and the
     * controller's own volume/power keys.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event) || super.onKeyUp(keyCode, event)

    private fun forwardKey(event: KeyEvent): Boolean {
        if (event.keyCode in LOCAL_KEYS) return false
        send(ControlMessages.keycode(event.action, event.keyCode, event.repeatCount, event.metaState))
        return true
    }

    private companion object {
        val LOCAL_KEYS = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        )
    }

    private fun sendKey(keycode: Int) {
        send(ControlMessages.keycode(KeyEvent.ACTION_DOWN, keycode))
        send(ControlMessages.keycode(KeyEvent.ACTION_UP, keycode))
    }

    private inner class RemoteInputConnection : BaseInputConnection(this@RemoteInputView, false) {
        /** Text the IME is composing; held back until committed. */
        private var composing: CharSequence = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = ""
            if (!text.isNullOrEmpty()) ControlMessages.text(text.toString()).forEach(send)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = text ?: ""
            return true
        }

        /** Some IMEs end composition this way instead of commitText: the composed text is final. */
        override fun finishComposingText(): Boolean {
            val c = composing
            composing = ""
            if (c.isNotEmpty()) ControlMessages.text(c.toString()).forEach(send)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { sendKey(KeyEvent.KEYCODE_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // The server only understands DOWN (0) and UP (1); KeyEvent uses the same values.
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                send(ControlMessages.keycode(event.action, event.keyCode, event.repeatCount, event.metaState))
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendKey(KeyEvent.KEYCODE_ENTER)
            return true
        }
    }
}

package xyz.block.trailblaze.host.macosax

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Synthetic HID event generation via CoreGraphics (`CGEvent*`) — the fallback interaction path
 * for the macOS AX driver when an element doesn't advertise a native AX action (`AXPress`, …).
 *
 * Unlike the AX-native path ([MacOsAxNative.performAction] / [MacOsAxNative.setStringAttribute]),
 * these move the real cursor and post real keyboard events to the frontmost app, so they're used
 * only as a fallback. All coordinates are **global screen points** (top-left origin), matching
 * the AX bounds space.
 *
 * Requires the **Accessibility** permission (same grant the AX APIs need) to post events.
 */
object MacOsAxEventSynthesizer {

  // CGEventType
  private const val K_CG_EVENT_LEFT_MOUSE_DOWN = 1
  private const val K_CG_EVENT_LEFT_MOUSE_UP = 2
  private const val K_CG_EVENT_RIGHT_MOUSE_DOWN = 3
  private const val K_CG_EVENT_RIGHT_MOUSE_UP = 4

  // CGMouseButton
  private const val K_CG_MOUSE_BUTTON_LEFT = 0
  private const val K_CG_MOUSE_BUTTON_RIGHT = 1

  /** `kCGMouseEventClickState` — the field that tells macOS "this is click number N of a series". */
  private const val K_CG_MOUSE_EVENT_CLICK_STATE = 1L

  // CGEventTapLocation
  private const val K_CG_HID_EVENT_TAP = 0

  /** `kCGEventFlagsChanged` — the event type macOS uses to report a modifier key going down/up. */
  private const val K_CG_EVENT_FLAGS_CHANGED = 12

  // CGScrollEventUnit
  private const val K_CG_SCROLL_EVENT_UNIT_PIXEL = 0

  private val cg: NativeLibrary by lazy {
    runCatching { NativeLibrary.getInstance("CoreGraphics") }
      .recoverCatching { NativeLibrary.getInstance("ApplicationServices") }
      .getOrThrow()
  }

  private val cf: NativeLibrary by lazy { NativeLibrary.getInstance("CoreFoundation") }

  private val cgEventCreateMouseEvent by lazy { cg.getFunction("CGEventCreateMouseEvent") }
  private val cgEventCreateScrollWheelEvent by lazy { cg.getFunction("CGEventCreateScrollWheelEvent") }
  private val cgEventCreateKeyboardEvent by lazy { cg.getFunction("CGEventCreateKeyboardEvent") }
  private val cgEventKeyboardSetUnicodeString by lazy { cg.getFunction("CGEventKeyboardSetUnicodeString") }
  private val cgEventPost by lazy { cg.getFunction("CGEventPost") }
  private val cgEventSetFlags by lazy { cg.getFunction("CGEventSetFlags") }
  private val cgEventSetType by lazy { cg.getFunction("CGEventSetType") }
  private val cgEventSetIntegerValueField by lazy { cg.getFunction("CGEventSetIntegerValueField") }
  private val cgEventCreate by lazy { cg.getFunction("CGEventCreate") }
  private val cgEventGetLocation by lazy { cg.getFunction("CGEventGetLocation") }
  private val cgWarpMouseCursorPosition by lazy { cg.getFunction("CGWarpMouseCursorPosition") }
  private val cfRelease by lazy { cf.getFunction("CFRelease") }

  /** `CGPoint { CGFloat x; CGFloat y }` passed by value to `CGEvent*` / `CGWarpMouse…`. */
  @Structure.FieldOrder("x", "y")
  open class CGPoint(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
  ) : Structure() {
    class ByValue(x: Double = 0.0, y: Double = 0.0) : CGPoint(x, y), Structure.ByValue
  }

  /**
   * Where the mouse cursor currently is, in global screen points. Every synthetic click warps the
   * cursor to its target first, so after a click this is that target — which is how a follow-up
   * tool can find the menu a right-click just opened without being told where it was.
   */
  fun cursorPosition(): Pair<Int, Int> {
    val event = cgEventCreate.invokePointer(arrayOf(Pointer.NULL)) ?: return 0 to 0
    return try {
      val point = cgEventGetLocation.invokeObject(arrayOf(event)) as? CGPoint.ByValue
      if (point == null) 0 to 0 else point.x.toInt() to point.y.toInt()
    } catch (e: Exception) {
      0 to 0
    } finally {
      cfRelease.invokeVoid(arrayOf(event))
    }
  }

  /** Synthesizes a left click (down+up) at global screen point ([x], [y]). */
  fun click(x: Int, y: Int) {
    val point = CGPoint.ByValue(x.toDouble(), y.toDouble())
    cgWarpMouseCursorPosition.invokeVoid(arrayOf(point))
    postMouse(K_CG_EVENT_LEFT_MOUSE_DOWN, point)
    postMouse(K_CG_EVENT_LEFT_MOUSE_UP, point)
  }

  /**
   * Right-click (secondary click) at a global screen point — how you reach a context menu, which
   * is where a large amount of desktop functionality lives (Finder's file actions, "Copy", "Open
   * With", "Inspect Element") and which no AXPress can summon.
   */
  fun rightClick(x: Int, y: Int) {
    val point = CGPoint.ByValue(x.toDouble(), y.toDouble())
    cgWarpMouseCursorPosition.invokeVoid(arrayOf(point))
    postMouse(K_CG_EVENT_RIGHT_MOUSE_DOWN, point, button = K_CG_MOUSE_BUTTON_RIGHT)
    postMouse(K_CG_EVENT_RIGHT_MOUSE_UP, point, button = K_CG_MOUSE_BUTTON_RIGHT)
  }

  /**
   * Double-click at a global screen point — Finder opens a file, a text view selects a word.
   *
   * Two clicks in quick succession are NOT a double-click: macOS decides that from the event's
   * `clickState` field, so the second click must be *stamped* as click 2 of a series or the app
   * just sees two independent clicks and opens nothing.
   */
  fun doubleClick(x: Int, y: Int) {
    val point = CGPoint.ByValue(x.toDouble(), y.toDouble())
    cgWarpMouseCursorPosition.invokeVoid(arrayOf(point))
    postMouse(K_CG_EVENT_LEFT_MOUSE_DOWN, point, clickState = 1)
    postMouse(K_CG_EVENT_LEFT_MOUSE_UP, point, clickState = 1)
    postMouse(K_CG_EVENT_LEFT_MOUSE_DOWN, point, clickState = 2)
    postMouse(K_CG_EVENT_LEFT_MOUSE_UP, point, clickState = 2)
  }

  private fun postMouse(
    type: Int,
    point: CGPoint.ByValue,
    button: Int = K_CG_MOUSE_BUTTON_LEFT,
    clickState: Int = 0,
  ) {
    val event = cgEventCreateMouseEvent.invokePointer(
      arrayOf(Pointer.NULL, type, point, button),
    )
    if (event == null || event == Pointer.NULL) return
    try {
      if (clickState > 0) {
        cgEventSetIntegerValueField.invokeVoid(
          arrayOf(event, K_CG_MOUSE_EVENT_CLICK_STATE.toInt(), clickState.toLong()),
        )
      }
      cgEventPost.invokeVoid(arrayOf(K_CG_HID_EVENT_TAP, event))
    } finally {
      cfRelease.invokeVoid(arrayOf(event))
    }
  }

  /**
   * Types [text] into the frontmost app one character at a time. Each character is posted as its
   * own key-down/up pair carrying a single-[UniChar] unicode payload — discrete per-character
   * events are what hardened fields (Safari's unified address bar) actually accept, where a single
   * batched multi-char event is silently dropped. Caller focuses the target first (e.g. ⌘L).
   */
  fun typeText(text: String) {
    for (ch in text) {
      postCharKey(ch, keyDown = true)
      postCharKey(ch, keyDown = false)
    }
  }

  private fun postCharKey(ch: Char, keyDown: Boolean) {
    val event = cgEventCreateKeyboardEvent.invokePointer(
      arrayOf(Pointer.NULL, 0.toShort(), if (keyDown) 1 else 0),
    )
    if (event == null || event == Pointer.NULL) return
    try {
      // `CGEventKeyboardSetUnicodeString` takes `UniChar*` — UTF-16 code units, 2 bytes each.
      // Write a single 2-byte short; NOT `Memory.setChar`, which writes a native `wchar_t`
      // (4 bytes on macOS) and overruns the buffer.
      val buf = Memory(2)
      buf.setShort(0, ch.code.toShort())
      cgEventKeyboardSetUnicodeString.invokeVoid(arrayOf(event, 1, buf))
      cgEventPost.invokeVoid(arrayOf(K_CG_HID_EVENT_TAP, event))
    } finally {
      cfRelease.invokeVoid(arrayOf(event))
    }
  }

  /** Virtual keycode for Delete/Backspace (`kVK_Delete`). */
  const val KEY_CODE_DELETE = 51

  // Common macOS virtual key codes (`kVK_*` from Carbon HIToolbox Events.h). Used by the
  // driver's PressKey action to submit fields (Return), move focus (Tab), dismiss (Escape), etc.
  /** `kVK_Return` — the main Return/Enter key. */
  const val KEY_CODE_RETURN = 36

  /** `kVK_Tab`. */
  const val KEY_CODE_TAB = 48

  /** `kVK_Escape`. */
  const val KEY_CODE_ESCAPE = 53

  /** `kVK_Home`. */
  const val KEY_CODE_HOME = 115

  /** `kVK_ANSI_L` — the "L" key (used for ⌘L "focus address bar" in browsers). */
  const val KEY_CODE_L = 37

  /** `kCGEventFlagMaskCommand` — the ⌘ modifier flag for [pressKeyCombo]. */
  const val FLAG_COMMAND = 0x100000L

  /** `kCGEventFlagMaskShift` — ⇧. */
  const val FLAG_SHIFT = 0x20000L

  /** `kCGEventFlagMaskAlternate` — ⌥ (Option/Alt). */
  const val FLAG_OPTION = 0x80000L

  /** `kCGEventFlagMaskControl` — ⌃. */
  const val FLAG_CONTROL = 0x40000L

  /**
   * Virtual key codes by name (`kVK_*`, Carbon HIToolbox `Events.h`), for chords like ⌘W / ⌘T /
   * ⌘⇧[ that the driver otherwise has no way to express.
   *
   * These are **positional**, not character codes — 0 is the key where "A" sits on a US ANSI
   * layout, which is why the numbers look arbitrary (A=0, S=1, D=2 …). They address a physical
   * key, so a chord means the same thing regardless of the user's keyboard layout, which is
   * exactly what you want for a shortcut like ⌘W.
   */
  val KEY_CODES: Map<String, Int> = mapOf(
    "A" to 0, "S" to 1, "D" to 2, "F" to 3, "H" to 4, "G" to 5, "Z" to 6, "X" to 7, "C" to 8,
    "V" to 9, "B" to 11, "Q" to 12, "W" to 13, "E" to 14, "R" to 15, "Y" to 16, "T" to 17,
    "O" to 31, "U" to 32, "I" to 34, "P" to 35, "L" to 37, "J" to 38, "K" to 40, "N" to 45,
    "M" to 46,
    "1" to 18, "2" to 19, "3" to 20, "4" to 21, "5" to 23, "6" to 22, "7" to 26, "8" to 28,
    "9" to 25, "0" to 29,
    "EQUAL" to 24, "MINUS" to 27, "LEFTBRACKET" to 33, "RIGHTBRACKET" to 30, "QUOTE" to 39,
    "SEMICOLON" to 41, "BACKSLASH" to 42, "COMMA" to 43, "SLASH" to 44, "PERIOD" to 47,
    "GRAVE" to 50,
    "RETURN" to KEY_CODE_RETURN, "ENTER" to KEY_CODE_RETURN, "TAB" to KEY_CODE_TAB,
    "SPACE" to 49, "DELETE" to KEY_CODE_DELETE, "BACKSPACE" to KEY_CODE_DELETE,
    "ESCAPE" to KEY_CODE_ESCAPE, "HOME" to KEY_CODE_HOME, "END" to 119,
    "PAGEUP" to 116, "PAGEDOWN" to 121,
    "LEFT" to 123, "RIGHT" to 124, "DOWN" to 125, "UP" to 126,
  )

  /**
   * Presses a chord while **holding** the modifiers down as real events, rather than merely
   * stamping the modifier bits onto the key event.
   *
   * ⌘Tab is why this exists. macOS's application switcher is driven by the WindowServer, which
   * watches for the Command key going down and coming back up: it opens the switcher on ⌘-down,
   * advances on each Tab, and commits the selection when ⌘ is *released*. A key event that merely
   * carries the command flag never announces a ⌘-down, so the switcher never opens and the
   * keystroke is dropped. Emitting explicit flagsChanged events for the modifiers — press, chord,
   * release — is what makes it behave like a real hand on a real keyboard.
   *
   * Plain shortcuts (⌘W, ⌘T) work either way; they're just handled by the focused app, which only
   * reads the flags on the key event. This path is correct for both, so it's the one used.
   */
  fun pressChord(keyCode: Int, modifierKeyCodes: List<Int>, flags: Long, repeats: Int = 1) {
    modifierKeyCodes.forEachIndexed { i, mod ->
      // Flags accumulate as each modifier goes down, mirroring a real keyboard.
      postFlagsChanged(mod, flagsFor(modifierKeyCodes.take(i + 1)))
    }
    try {
      repeat(repeats) {
        postKeyCode(keyCode, keyDown = true, flags = flags)
        postKeyCode(keyCode, keyDown = false, flags = flags)
      }
    } finally {
      // Release in reverse, shedding each modifier's flag as it comes up. In a finally block
      // because a modifier left stuck down would poison every subsequent keystroke on the machine
      // — including the user's own typing, long after the run ended.
      modifierKeyCodes.reversed().forEachIndexed { i, mod ->
        val stillHeld = modifierKeyCodes.take(modifierKeyCodes.size - i - 1)
        postFlagsChanged(mod, flagsFor(stillHeld))
      }
    }
  }

  /** Virtual key codes for the modifier keys themselves (needed to press/release them). */
  private const val KEY_CODE_COMMAND = 55
  private const val KEY_CODE_SHIFT = 56
  private const val KEY_CODE_OPTION = 58
  private const val KEY_CODE_CONTROL = 59

  /** The modifier key code that carries [flag], for the flagsChanged events in [pressChord]. */
  fun modifierKeyCodeFor(flag: Long): Int = when (flag) {
    FLAG_COMMAND -> KEY_CODE_COMMAND
    FLAG_SHIFT -> KEY_CODE_SHIFT
    FLAG_OPTION -> KEY_CODE_OPTION
    else -> KEY_CODE_CONTROL
  }

  private fun flagsFor(modifierKeyCodes: List<Int>): Long = modifierKeyCodes.fold(0L) { acc, code ->
    acc or when (code) {
      KEY_CODE_COMMAND -> FLAG_COMMAND
      KEY_CODE_SHIFT -> FLAG_SHIFT
      KEY_CODE_OPTION -> FLAG_OPTION
      else -> FLAG_CONTROL
    }
  }

  /** Posts a `flagsChanged` event — how macOS learns a modifier key itself went down or up. */
  private fun postFlagsChanged(keyCode: Int, flags: Long) {
    val event = cgEventCreateKeyboardEvent.invokePointer(
      arrayOf(Pointer.NULL, keyCode.toShort(), 1),
    )
    if (event == null || event == Pointer.NULL) return
    try {
      cgEventSetType.invokeVoid(arrayOf(event, K_CG_EVENT_FLAGS_CHANGED))
      cgEventSetFlags.invokeVoid(arrayOf(event, flags))
      cgEventPost.invokeVoid(arrayOf(K_CG_HID_EVENT_TAP, event))
    } finally {
      cfRelease.invokeVoid(arrayOf(event))
    }
  }

  /** Posts [times] key down+up pairs for the given virtual [keyCode] (e.g. [KEY_CODE_DELETE]). */
  fun pressKeyCode(keyCode: Int, times: Int = 1) {
    repeat(times) {
      postKeyCode(keyCode, keyDown = true, flags = 0L)
      postKeyCode(keyCode, keyDown = false, flags = 0L)
    }
  }

  /**
   * Posts a single key chord with modifier [flags] set (e.g. [FLAG_COMMAND] for ⌘L). One
   * down+up pair with the modifier applied to both events.
   */
  fun pressKeyCombo(keyCode: Int, flags: Long) {
    postKeyCode(keyCode, keyDown = true, flags = flags)
    postKeyCode(keyCode, keyDown = false, flags = flags)
  }

  private fun postKeyCode(keyCode: Int, keyDown: Boolean, flags: Long) {
    val event = cgEventCreateKeyboardEvent.invokePointer(
      arrayOf(Pointer.NULL, keyCode.toShort(), if (keyDown) 1 else 0),
    )
    if (event == null || event == Pointer.NULL) return
    try {
      // CGEventCreateKeyboardEvent seeds default flags; override explicitly so a plain key press
      // carries no stray modifiers and a combo carries exactly the ones requested.
      cgEventSetFlags.invokeVoid(arrayOf(event, flags))
      cgEventPost.invokeVoid(arrayOf(K_CG_HID_EVENT_TAP, event))
    } finally {
      cfRelease.invokeVoid(arrayOf(event))
    }
  }

  /**
   * Posts a pixel-unit scroll wheel event at the current cursor position. Positive [dy] scrolls
   * content up (wheel away from user); positive [dx] scrolls right. Warps the cursor to ([x], [y])
   * first so the scroll lands over the intended element.
   */
  fun scroll(x: Int, y: Int, dx: Int, dy: Int) {
    cgWarpMouseCursorPosition.invokeVoid(arrayOf(CGPoint.ByValue(x.toDouble(), y.toDouble())))
    // CGEventCreateScrollWheelEvent(source, units, wheelCount, wheel1(vertical), wheel2(horizontal))
    val event = cgEventCreateScrollWheelEvent.invokePointer(
      arrayOf(Pointer.NULL, K_CG_SCROLL_EVENT_UNIT_PIXEL, 2, dy, dx),
    )
    if (event == null || event == Pointer.NULL) return
    try {
      cgEventPost.invokeVoid(arrayOf(K_CG_HID_EVENT_TAP, event))
    } finally {
      cfRelease.invokeVoid(arrayOf(event))
    }
  }
}

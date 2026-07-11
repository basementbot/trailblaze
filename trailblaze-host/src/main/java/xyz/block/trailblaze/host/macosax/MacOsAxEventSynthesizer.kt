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

  // CGMouseButton
  private const val K_CG_MOUSE_BUTTON_LEFT = 0

  // CGEventTapLocation
  private const val K_CG_HID_EVENT_TAP = 0

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

  /** Synthesizes a left click (down+up) at global screen point ([x], [y]). */
  fun click(x: Int, y: Int) {
    val point = CGPoint.ByValue(x.toDouble(), y.toDouble())
    cgWarpMouseCursorPosition.invokeVoid(arrayOf(point))
    postMouse(K_CG_EVENT_LEFT_MOUSE_DOWN, point)
    postMouse(K_CG_EVENT_LEFT_MOUSE_UP, point)
  }

  private fun postMouse(type: Int, point: CGPoint.ByValue) {
    val event = cgEventCreateMouseEvent.invokePointer(
      arrayOf(Pointer.NULL, type, point, K_CG_MOUSE_BUTTON_LEFT),
    )
    if (event == null || event == Pointer.NULL) return
    try {
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

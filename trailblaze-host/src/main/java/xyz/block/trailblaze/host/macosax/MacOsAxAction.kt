package xyz.block.trailblaze.host.macosax

import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Native action vocabulary for the macOS desktop AX driver — the shape the driver dispatches
 * internally, parallel to [xyz.block.trailblaze.host.axe.AxeAction] (iOS) but using macOS-native
 * interaction primitives (AX actions / `CGEvent` synthesis / `NSWorkspace`-style app launch).
 */
sealed interface MacOsAxAction {
  val description: String

  // --- Coordinate gestures (global screen points) ---

  data class Tap(val x: Int, val y: Int) : MacOsAxAction {
    override val description get() = "Tap on ($x, $y)"
  }

  data class Scroll(val direction: Direction, val amountPx: Int = 300) : MacOsAxAction {
    override val description get() = "Scroll ${direction.name} ${amountPx}px"
  }

  // --- Element-based actions (resolved via TrailblazeNodeSelector) ---

  data class TapOnElement(
    val nodeSelector: TrailblazeNodeSelector,
    val fallbackX: Int? = null,
    val fallbackY: Int? = null,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : MacOsAxAction {
    override val description get() = "Tap on ${nodeSelector.description()}"
  }

  data class AssertVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : MacOsAxAction {
    override val description get() = "Assert visible: ${nodeSelector.description()}"
  }

  data class AssertNotVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : MacOsAxAction {
    override val description get() = "Assert not visible: ${nodeSelector.description()}"
  }

  // --- Text input (caller focuses first, e.g. via a preceding Tap) ---

  data class InputText(val text: String) : MacOsAxAction {
    override val description get() = "Input text \"$text\""
  }

  data class EraseText(val characters: Int) : MacOsAxAction {
    override val description get() = "Erase $characters characters"
  }

  /**
   * Press a single special key (Return / Tab / Escape / …) as a synthetic HID keystroke via
   * [MacOsAxEventSynthesizer]. [keyCode] is a macOS virtual key code (`kVK_*`); [label] is the
   * human-readable key name for logging. Used to submit fields (Return), move focus (Tab), etc.
   */
  data class PressKey(val keyCode: Int, val label: String) : MacOsAxAction {
    override val description get() = "Press $label key"
  }

  // --- App lifecycle (shells out to `open` / `osascript`) ---

  data class LaunchApp(val bundleId: String) : MacOsAxAction {
    override val description get() = "Launch app $bundleId"
  }

  data class ActivateApp(val bundleId: String) : MacOsAxAction {
    override val description get() = "Activate app $bundleId"
  }

  data class QuitApp(val bundleId: String) : MacOsAxAction {
    override val description get() = "Quit app $bundleId"
  }

  // --- Waiting ---

  data class WaitForSettle(val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS) : MacOsAxAction {
    override val description get() = "Wait for UI to settle (timeout: ${timeoutMs}ms)"
  }

  enum class Direction { UP, DOWN, LEFT, RIGHT }

  companion object {
    const val DEFAULT_ELEMENT_TIMEOUT_MS: Long = 5_000L
  }
}

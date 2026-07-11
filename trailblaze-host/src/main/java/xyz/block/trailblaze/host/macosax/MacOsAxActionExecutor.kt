package xyz.block.trailblaze.host.macosax

import com.sun.jna.Pointer
import xyz.block.trailblaze.api.MacOsAxAttributeValue
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import java.util.concurrent.TimeUnit

/**
 * Executes [MacOsAxAction]s against a live macOS app.
 *
 * Interaction strategy, in preference order:
 *  1. **AX-native** ([MacOsAxNative.performAction] / [MacOsAxNative.setStringAttribute]) — acts
 *     directly on the element, doesn't move the cursor or steal keyboard focus globally. Used
 *     whenever the element advertises the relevant AX action / settable attribute.
 *  2. **Synthetic HID** ([MacOsAxEventSynthesizer]) — real `CGEvent` mouse/keyboard/scroll,
 *     the fallback for elements that aren't AX-actionable.
 *
 * Element resolution reuses the shared [TrailblazeNodeSelectorResolver] over a freshly-captured
 * [TrailblazeNode] tree, so selectors match against exact `AX*` attributes (see
 * `DriverNodeMatch.MacOsAx`). Requires the **Accessibility** permission.
 */
class MacOsAxActionExecutor(private val pid: Int) {

  /** Captures the current AX tree for the target app. */
  fun captureTree(): TrailblazeNode = MacOsAxTreeWalker.capture(pid)

  /** Dispatches [action]. Returns true on success. Tree is (re)captured lazily where needed. */
  fun execute(action: MacOsAxAction): Boolean = when (action) {
    is MacOsAxAction.Tap -> tapAt(action.x, action.y)
    is MacOsAxAction.TapOnElement -> tapOnElement(action)
    is MacOsAxAction.Scroll -> scroll(action)
    is MacOsAxAction.InputText -> inputText(action.text)
    is MacOsAxAction.EraseText -> eraseText(action.characters)
    is MacOsAxAction.PressKey -> {
      MacOsAxEventSynthesizer.pressKeyCode(action.keyCode); true
    }
    // Poll until the timeout so an assertion following a navigation/tap waits for the new content
    // to render (a page load, a screen transition) instead of checking once, too early, and failing.
    is MacOsAxAction.AssertVisible -> pollUntil(action.timeoutMs) { resolveNode(action.nodeSelector) != null }
    is MacOsAxAction.AssertNotVisible -> pollUntil(action.timeoutMs) { resolveNode(action.nodeSelector) == null }
    is MacOsAxAction.LaunchApp -> shell(listOf("open", "-b", action.bundleId))
    is MacOsAxAction.ActivateApp ->
      shell(listOf("osascript", "-e", "tell application id \"${action.bundleId}\" to activate"))
    is MacOsAxAction.QuitApp ->
      shell(listOf("osascript", "-e", "tell application id \"${action.bundleId}\" to quit"))
    is MacOsAxAction.WaitForSettle -> {
      Thread.sleep(action.timeoutMs.coerceAtMost(1_000L)); true
    }
  }

  /** Re-evaluates [condition] against fresh captures until it's true or [timeoutMs] elapses. */
  private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      if (condition()) return true
      if (System.currentTimeMillis() >= deadline) return false
      Thread.sleep(300)
    }
  }

  /** Resolves [selector] against a fresh capture; returns the matched node or null. */
  fun resolveNode(selector: TrailblazeNodeSelector): TrailblazeNode? {
    val tree = captureTree()
    return when (val r = TrailblazeNodeSelectorResolver.resolve(tree, selector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> r.node
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> r.nodes.firstOrNull()
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
    }
  }

  private fun tapOnElement(action: MacOsAxAction.TapOnElement): Boolean {
    val node = resolveNode(action.nodeSelector)
    val center = node?.centerPoint()
    return when {
      center != null -> tapAt(center.first, center.second)
      action.fallbackX != null && action.fallbackY != null -> tapAt(action.fallbackX, action.fallbackY)
      else -> false
    }
  }

  /**
   * Taps at global screen point ([x], [y]). Prefers the AX-native `AXPress` on whatever live
   * element sits at that point; falls back to a synthetic click for non-AX-actionable elements.
   */
  fun tapAt(x: Int, y: Int): Boolean {
    val pressed = withElementAtPosition(x, y) { el ->
      if ("AXPress" in MacOsAxNative.actionNames(el)) MacOsAxNative.performAction(el, "AXPress") else false
    }
    if (pressed) return true
    MacOsAxEventSynthesizer.click(x, y)
    return true
  }

  /**
   * Inputs [text]. Prefers appending to the focused element's `AXValue` (AX-native, no HID);
   * falls back to synthetic keyboard events.
   */
  fun inputText(text: String): Boolean {
    val handled = withFocusedElement { el ->
      val existing = focusedStringValue(el) ?: ""
      MacOsAxNative.setStringAttribute(el, "AXValue", existing + text)
    }
    if (handled) return true
    MacOsAxEventSynthesizer.typeText(text)
    return true
  }

  /** Erases [characters] from the focused element's `AXValue` (AX-native), else synthetic delete. */
  fun eraseText(characters: Int): Boolean {
    val handled = withFocusedElement { el ->
      val existing = focusedStringValue(el) ?: return@withFocusedElement false
      val trimmed = existing.dropLast(characters.coerceAtMost(existing.length))
      MacOsAxNative.setStringAttribute(el, "AXValue", trimmed)
    }
    if (handled) return true
    MacOsAxEventSynthesizer.pressKeyCode(MacOsAxEventSynthesizer.KEY_CODE_DELETE, characters)
    return true
  }

  /** Reads the focused element's current `AXValue` as a string, or null if absent/non-string. */
  private fun focusedStringValue(el: Pointer): String? {
    val ref = MacOsAxNative.copyAttributeValue(el, "AXValue") ?: return null
    return try {
      (MacOsAxNative.decodeValue(ref) as? MacOsAxAttributeValue.Str)?.value
    } finally {
      MacOsAxNative.release(ref)
    }
  }

  private fun scroll(action: MacOsAxAction.Scroll): Boolean {
    // Scroll at the target window's center so the wheel event lands over app content.
    val tree = captureTree()
    val center = tree.bounds?.let { it.centerX to it.centerY } ?: (0 to 0)
    val amt = action.amountPx
    val (dx, dy) = when (action.direction) {
      MacOsAxAction.Direction.UP -> 0 to amt
      MacOsAxAction.Direction.DOWN -> 0 to -amt
      MacOsAxAction.Direction.LEFT -> amt to 0
      MacOsAxAction.Direction.RIGHT -> -amt to 0
    }
    MacOsAxEventSynthesizer.scroll(center.first, center.second, dx, dy)
    return true
  }

  // --- live-element helpers (own + release the transient system-wide handles) ---

  private inline fun withElementAtPosition(x: Int, y: Int, block: (Pointer) -> Boolean): Boolean {
    val systemWide = MacOsAxNative.createSystemWide()
    return try {
      val el = MacOsAxNative.copyElementAtPosition(systemWide, x.toFloat(), y.toFloat()) ?: return false
      try {
        block(el)
      } finally {
        MacOsAxNative.release(el)
      }
    } finally {
      MacOsAxNative.release(systemWide)
    }
  }

  private inline fun withFocusedElement(block: (Pointer) -> Boolean): Boolean {
    val systemWide = MacOsAxNative.createSystemWide()
    return try {
      val el = MacOsAxNative.copyAttributeValue(systemWide, "AXFocusedUIElement") ?: return false
      try {
        block(el)
      } finally {
        MacOsAxNative.release(el)
      }
    } finally {
      MacOsAxNative.release(systemWide)
    }
  }

  private fun shell(args: List<String>, timeoutSeconds: Long = 10): Boolean = try {
    val proc = ProcessBuilder(args).redirectErrorStream(true).start()
    if (proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) proc.exitValue() == 0
    else { proc.destroyForcibly(); false }
  } catch (e: Exception) {
    System.err.println("[MacOsAxActionExecutor] shell ${args.firstOrNull()} failed: ${e.message}")
    false
  }
}

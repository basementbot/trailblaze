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

  /**
   * True when this executor targets the whole desktop (`--device desktop/all`) rather than one
   * app. [MacOsAxConnectedDevice.WHOLE_SCREEN_INSTANCE_ID] carries no process, so its pid is 0.
   */
  /** True for a desktop-scoped device (every app, or the frontmost app) — i.e. not one named app. */
  private val desktopScoped: Boolean = pid <= MacOsAxTreeWalker.PID_ALL_APPS

  /**
   * Captures the AX tree the selectors resolve against: every on-screen app for the whole-desktop
   * device, otherwise just the target app.
   *
   * Without the whole-screen branch, `desktop/all` captured `pid=0` — a single-app walk of a
   * process that doesn't exist — so every selector resolved to nothing and acting on the desktop
   * device failed with "Element not found" even though snapshotting it worked fine. (Taps were
   * never the problem: [tapAt] goes through the system-wide element-at-position, so it's
   * desktop-wide already.)
   */
  fun captureTree(): TrailblazeNode =
    MacOsAxTreeWalker.captureFor(pid)

  /** Dispatches [action]. Returns true on success. Tree is (re)captured lazily where needed. */
  fun execute(action: MacOsAxAction): Boolean = when (action) {
    is MacOsAxAction.Tap -> tapAt(action.x, action.y)
    is MacOsAxAction.TapOnElement -> tapOnElement(action)
    is MacOsAxAction.Scroll -> scroll(action)
    is MacOsAxAction.InputText -> inputText(action.text)
    is MacOsAxAction.EraseText -> eraseText(action.characters)
    is MacOsAxAction.PressKey -> {
      activateTargetApp()
      MacOsAxEventSynthesizer.pressKeyCode(action.keyCode)
      true
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

  /**
   * Centre of the frontmost on-screen window, in points — where a scroll or a context click should
   * land. `CGWindowListCopyWindowInfo` returns windows front-to-back, so the first one is it.
   */
  private fun frontmostWindowCentre(): Pair<Int, Int>? =
    MacOsAxNative.onScreenWindows().firstOrNull()?.let {
      (it.left + it.right) / 2 to (it.top + it.bottom) / 2
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
  /**
   * Types [text] into whatever has keyboard focus, as a person would.
   *
   * There is deliberately no `AXValue`-write fast path any more. Writing the value directly looked
   * attractive — no HID events, no focus stealing — and it failed three different ways in three
   * different apps:
   *
   *  - Calculator ACCEPTED the write and ignored it, so the setter's success was a lie and the text
   *    never appeared.
   *  - Safari's address bar swallowed it the same way.
   *  - Chrome (Gmail's recipient field) HONORED it *and* fired its own insertion, so the address
   *    came out as `someone@example.comsomeone@example.com` — an email addressed to nobody, one
   *    keystroke from being sent.
   *
   * Every guard I added made a different app worse: verify by exact echo and Chrome's reformatted
   * value looks like a failure (so it gets typed twice); verify by "did it change" and Chrome still
   * doubles, because both the write and the app's own insertion take. A web field is not a value to
   * be assigned, it's a control that reacts to input, and the only way to be right in all three
   * apps is the one a person uses: bring the app forward and type.
   */
  fun inputText(text: String): Boolean {
    activateTargetApp()
    MacOsAxEventSynthesizer.typeText(text)
    return true
  }

  /**
   * Brings the target app to the front and waits for the activation to land.
   *
   * `CGEventPost` posts to the HID event stream, which macOS delivers to whatever app is
   * **frontmost** — not to [pid]. So a synthetic keystroke issued while another app has focus
   * doesn't just fail to reach the target, it lands in that other app (the terminal that started
   * the run, an editor, a chat window). Observed live: `macos_inputText text="12*12"` against
   * Calculator left the display on `0` because the keys went to the foreground terminal instead.
   *
   * AX-native interaction ([MacOsAxNative.performAction], `AXValue` writes) addresses the element
   * directly and works on background windows, which is why only the synthetic-HID paths need this.
   * The short sleep is required: activation is asynchronous, and events posted in the same
   * millisecond still reach the outgoing frontmost app.
   */
  private fun activateTargetApp() {
    // The whole-desktop device has no single target app to bring forward, so keystrokes go to
    // whatever the desktop currently has focused — which is the right semantic for it, and is how
    // a person at the keyboard experiences the machine. A preceding tap is what moves focus: a
    // synthetic click raises the window it lands on, so "click the field, then type" works across
    // apps without this needing to guess an owner.
    if (desktopScoped) return
    val app = MacOsAxNative.createApplication(pid)
    try {
      MacOsAxNative.setBooleanAttribute(app, "AXFrontmost", true)
    } finally {
      MacOsAxNative.release(app)
    }
    Thread.sleep(APP_ACTIVATION_SETTLE_MS)
  }

  /** Erases [characters] from the focused element's `AXValue` (AX-native), else synthetic delete. */
  fun eraseText(characters: Int): Boolean {
    val handled = withFocusedElement { el ->
      val existing = focusedStringValue(el) ?: return@withFocusedElement false
      val trimmed = existing.dropLast(characters.coerceAtMost(existing.length))
      // Read back for the same reason as [inputText] — a swallowed write must not be mistaken
      // for a successful erase, or we skip the synthetic-delete fallback and erase nothing.
      MacOsAxNative.setStringAttribute(el, "AXValue", trimmed) && focusedStringValue(el) == trimmed
    }
    if (handled) return true
    activateTargetApp()
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
    // A scroll wheel event goes to whatever is under the POINTER, so it has to land over real app
    // content. The tree root's centre is not that: on the whole-desktop device the root is a
    // synthetic node with no bounds at all, so this fell back to (0, 0) — the top-left corner of
    // the screen, over the menu bar — and scrolled nothing. Aim at the frontmost window instead,
    // which is what a person would be looking at. Falls back to the tree root (a single-app
    // capture's root IS the app), then to the display centre.
    val center = frontmostWindowCentre()
      ?: captureTree().bounds?.let { it.centerX to it.centerY }
      ?: MacOsAxAppResolver.mainDisplaySize().let { it.width / 2 to it.height / 2 }
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

  companion object {
    /** Sentinel pid for the whole-desktop device — it targets no single process. */
    private const val WHOLE_SCREEN_PID = 0

    /**
     * How long to wait after requesting `AXFrontmost` before posting synthetic HID events.
     * Activation is handled asynchronously by the window server; posting immediately races it
     * and the keystrokes still go to the app that was frontmost a moment ago.
     */
    private const val APP_ACTIVATION_SETTLE_MS = 250L
  }
}

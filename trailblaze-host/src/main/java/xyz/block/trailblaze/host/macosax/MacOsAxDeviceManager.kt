package xyz.block.trailblaze.host.macosax

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.host.screenstate.MacOsAxScreenState
import xyz.block.trailblaze.util.Console

/**
 * Manages interaction with one macOS app through the Apple Accessibility APIs.
 *
 * Desktop equivalent of [xyz.block.trailblaze.host.axe.AxeDeviceManager] (iOS): takes
 * [MacOsAxAction]s and dispatches them via [MacOsAxActionExecutor], resolves selectors against a
 * freshly-captured [TrailblazeNode] tree (with a short polling window so actions can wait for the
 * UI to settle), and hands out [MacOsAxScreenState]s. Targets an app by [pid].
 *
 * @param templateContext per-session context so selectors carrying `{{target.appId}}`-style
 *   placeholders expand at resolve time (null for target-agnostic ad-hoc paths).
 */
class MacOsAxDeviceManager(
  private val pid: Int,
  private val deviceWidth: Int,
  private val deviceHeight: Int,
  private val templateContext: xyz.block.trailblaze.api.TargetTemplateContext? = null,
) {

  private val executor = MacOsAxActionExecutor(pid)

  companion object {
    private const val POLL_INTERVAL_MS = 150L
    private const val SETTLE_DELAY_MS = 300L
  }

  data class ExecutionResult(val resolvedX: Int? = null, val resolvedY: Int? = null)

  // --- Screen state ---

  fun getScreenState(): ScreenState = MacOsAxScreenState(
    pid = pid,
    deviceWidth = deviceWidth,
    deviceHeight = deviceHeight,
  )

  /** Fresh tree capture without waiting — used for selector-resolution loops. */
  /**
   * Delegates to [MacOsAxActionExecutor.captureTree] rather than walking the tree itself, so the
   * app-vs-whole-desktop decision lives in exactly one place. When this duplicated
   * `MacOsAxTreeWalker.capture(pid)`, teaching the executor about `desktop/all` wasn't enough:
   * selectors resolve against *this* capture, so the whole-desktop device kept walking `pid=0` and
   * every selector missed while snapshots of the same device looked perfect.
   */
  fun captureTree(): TrailblazeNode? = try {
    executor.captureTree()
  } catch (e: Exception) {
    Console.log("[MacOsAxDeviceManager] AX capture failed: ${e.message}")
    null
  }

  /** Fixed-delay settle. */
  fun waitForReady(timeoutMs: Long = SETTLE_DELAY_MS) {
    Thread.sleep(timeoutMs)
  }

  // --- Action dispatch ---

  fun execute(action: MacOsAxAction): ExecutionResult {
    Console.log("[MacOsAxDeviceManager] Executing: ${action.description}")
    return when (action) {
      is MacOsAxAction.TapOnElement -> executeTapOnElement(action)
      is MacOsAxAction.AssertVisible -> executeAssertVisible(action)
      is MacOsAxAction.AssertNotVisible -> executeAssertNotVisible(action)
      is MacOsAxAction.LaunchApp -> {
        if (!executor.execute(action)) error("launch ${action.bundleId} failed")
        Thread.sleep(2_000L) // apps take a beat to render their AX tree
        ExecutionResult()
      }
      else -> {
        if (!executor.execute(action)) error("action failed: ${action.description}")
        ExecutionResult()
      }
    }
  }

  // --- Selector-driven actions (poll the freshly-captured tree until the timeout) ---

  private fun executeTapOnElement(action: MacOsAxAction.TapOnElement): ExecutionResult {
    val node = awaitSelector(action.nodeSelector, action.timeoutMs)
    if (node != null) {
      // Refuse to click something buried under another window. A tap resolves to global screen
      // coordinates and lands on whatever is topmost there, so clicking an occluded element doesn't
      // fail — it silently clicks the window on top of it, in a different app. Fail loudly instead;
      // the caller's own timeout/retry is the right place to handle "the thing I want is covered".
      if ((node.driverDetail as? DriverNodeDetail.MacOsAx)?.occluded == true) {
        error(
          "Element matched by ${action.nodeSelector.description()} is hidden behind another window — " +
            "clicking it would hit the window on top. Bring its window to the front first.",
        )
      }
      val center = node.centerPoint() ?: error("Element matched but has no bounds: ${action.nodeSelector.description()}")
      executor.tapAt(center.first, center.second)
      return ExecutionResult(center.first, center.second)
    }
    if (action.fallbackX != null && action.fallbackY != null) {
      Console.log("[MacOsAxDeviceManager] selector miss, using fallback (${action.fallbackX}, ${action.fallbackY})")
      executor.tapAt(action.fallbackX, action.fallbackY)
      return ExecutionResult(action.fallbackX, action.fallbackY)
    }
    error("Element not found for selector: ${action.nodeSelector.description()} after ${action.timeoutMs}ms")
  }

  private fun executeAssertVisible(action: MacOsAxAction.AssertVisible): ExecutionResult {
    val node = awaitSelector(action.nodeSelector, action.timeoutMs)
      ?: error("Assert visible failed: ${action.nodeSelector.description()} not found within ${action.timeoutMs}ms")
    // Present in the tree is not the same as visible to the user. An element completely buried
    // under another window is one nobody can see, so passing an assert on it would certify
    // something false — the failure mode an assertion exists to catch.
    if ((node.driverDetail as? DriverNodeDetail.MacOsAx)?.occluded == true) {
      error(
        "Assert visible failed: ${action.nodeSelector.description()} exists but is hidden behind " +
          "another window",
      )
    }
    val center = node.centerPoint()
    return ExecutionResult(center?.first, center?.second)
  }

  private fun executeAssertNotVisible(action: MacOsAxAction.AssertNotVisible): ExecutionResult {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val tree = captureTree()
      if (tree != null) {
        val result = TrailblazeNodeSelectorResolver.resolve(tree, action.nodeSelector, templateContext)
        if (result is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch) {
          return ExecutionResult(deviceWidth / 2, deviceHeight / 2)
        }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    error("Assert not visible failed: ${action.nodeSelector.description()} still visible after ${action.timeoutMs}ms")
  }

  /**
   * Chooses which node to act on when a selector matches more than one.
   *
   * A whole-desktop capture routinely surfaces the same label from several windows at once — two
   * Finder windows both showing "Downloads", the same button in a background copy of an app. The
   * resolver's blind `first()` could pick one the user can't see, and the tap would land on the
   * window covering it. Prefer the first match that isn't occluded; fall back to the resolver's
   * first when every match is covered (the tap gate in [executeTapOnElement] then refuses it),
   * so single-app behavior — where nothing is ever occluded — is unchanged.
   *
   * Mirrors the Android accessibility driver's `pickPreferredMatch`, which does the same thing with
   * `isVisibleToUser` for content behind a dialog.
   */
  private fun pickPreferredMatch(nodes: List<TrailblazeNode>): TrailblazeNode =
    nodes.firstOrNull { (it.driverDetail as? DriverNodeDetail.MacOsAx)?.occluded == false }
      ?: nodes.first()

  /** Polls a fresh capture until [selector] resolves (first match) or [timeoutMs] elapses. */
  private fun awaitSelector(
    selector: xyz.block.trailblaze.api.TrailblazeNodeSelector,
    timeoutMs: Long,
  ): TrailblazeNode? {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeoutMs) {
      val tree = captureTree()
      if (tree != null) {
        when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector, templateContext)) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> return result.node
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            Console.log(
              "[MacOsAxDeviceManager] selector '${selector.description()}' matched " +
                "${result.nodes.size} elements — picking the first visible one; refine to disambiguate",
            )
            return pickPreferredMatch(result.nodes)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> Unit
        }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    return null
  }
}

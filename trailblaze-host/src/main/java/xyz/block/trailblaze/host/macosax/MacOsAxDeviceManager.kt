package xyz.block.trailblaze.host.macosax

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
  fun captureTree(): TrailblazeNode? = try {
    MacOsAxTreeWalker.capture(pid)
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
                "${result.nodes.size} elements — picking the first; refine to disambiguate",
            )
            return result.nodes.first()
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> Unit
        }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    return null
  }
}

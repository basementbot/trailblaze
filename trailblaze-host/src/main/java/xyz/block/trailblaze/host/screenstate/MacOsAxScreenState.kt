package xyz.block.trailblaze.host.screenstate

import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.host.macosax.MacOsAxAppResolver
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.toViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.macosax.MacOsAxScreenshotCli
import xyz.block.trailblaze.host.macosax.MacOsAxTreeWalker

/**
 * [ScreenState] for a macOS desktop app driven by the Apple Accessibility APIs.
 *
 * Parallel to [AxeScreenState] (iOS AXe) but with no subprocess/JSON boundary: the tree is
 * captured directly via [MacOsAxTreeWalker] over the app's live `AXUIElement` hierarchy, into
 * [TrailblazeNode]s carrying full-fidelity [xyz.block.trailblaze.api.DriverNodeDetail.MacOsAx]
 * detail. Everything is lazy so flows that only need the tree never pay for the screenshot.
 *
 * @param pid owning process id of the target app (root = `AXUIElementCreateApplication(pid)`).
 *   Ignored when [wholeScreen] is true.
 * @param wholeScreen when true, capture the ENTIRE desktop — every app with an on-screen window
 *   stitched under one synthetic root ([MacOsAxTreeWalker.captureScreen]) — instead of a single
 *   app. This is the macOS equivalent of the whole-screen hierarchy the iOS/Android/Web drivers
 *   expose.
 * @param deviceWidth / deviceHeight the target window/display size in AX points (global,
 *   top-left origin), used for offscreen filtering and set-of-mark scaling.
 */
class MacOsAxScreenState(
  private val pid: Int,
  override val deviceWidth: Int,
  override val deviceHeight: Int,
  private val wholeScreen: Boolean = false,
) : ScreenState {

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.DESKTOP
  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()

  /** Raw TrailblazeNode tree straight from the AX walk — no refs yet. */
  private val parsedTree: TrailblazeNode? by lazy {
    try {
      MacOsAxTreeWalker.captureFor(pid)
    } catch (e: Exception) {
      System.err.println("[MacOsAxScreenState] AX tree capture failed (pid=$pid): ${e.message}")
      null
    }
  }

  /**
   * Compact element list built once over [parsedTree]. Feeds both the text representation and
   * the ref mapping stamped onto [trailblazeNodeTree]. Baseline rendering (no bounds/offscreen/
   * all-elements); callers needing those re-render via [CompactScreenElements.buildForMacOsAx].
   */
  private val compactElements: CompactScreenElements? by lazy {
    val tree = parsedTree ?: return@lazy null
    CompactScreenElements.buildForMacOsAx(
      tree = tree,
      screenHeight = deviceHeight,
      screenWidth = deviceWidth,
    )
  }

  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    val tree = parsedTree ?: return@lazy null
    compactElements?.applyRefsToTree(tree) ?: tree
  }

  override val viewHierarchy: ViewHierarchyTreeNode by lazy {
    trailblazeNodeTree?.toViewHierarchyTreeNode()
      ?: error("MacOsAxScreenState: AX tree capture did not produce a usable view hierarchy")
  }

  override val viewHierarchyTextRepresentation: String? by lazy {
    compactElements?.text?.let { it + scopeFooter() }
  }

  /**
   * Tells the reader what this snapshot did NOT show them, and how to get it.
   *
   * The default scope is the frontmost app, because capturing every on-screen app costs ~5 seconds
   * and that's too slow to drive a machine with. But a narrow default is only honest if it says so:
   * without this footer an agent (or a person) reading a snapshot has no way to distinguish "Finder
   * isn't running" from "Finder is running and I simply didn't look at it", and would conclude the
   * former and give up. So name the app in scope, name the ones that aren't, and give the exact
   * incantation for each way out — including the slow one, marked slow.
   */
  private fun scopeFooter(): String {
    if (pid != MacOsAxTreeWalker.PID_FRONTMOST_APP) return ""
    val apps = runCatching { MacOsAxAppResolver.onScreenApps() }.getOrDefault(emptyList())
    if (apps.isEmpty()) return ""
    val front = apps.first()
    val others = apps.drop(1)

    return buildString {
      append("\n\n--- showing the ACTIVE app only: ${describe(front)} (fast: this is the default)")
      if (others.isEmpty()) {
        append("\nNo other apps have windows on screen.")
      } else {
        append("\nAlso open, NOT shown above: ")
        append(others.joinToString(", ") { describe(it) })
        append("\n  • switch to one:      macos_activateApp bundleId=<id>   (the scope follows the active app)")
        append("\n  • or inspect it directly:  --device desktop/<bundleId>")
        append("\n  • or capture every app at once:  --device desktop/all   (SLOW — several seconds, scales with what's open)")
      }
    }
  }

  private fun describe(app: MacOsAxAppResolver.RunningApp): String =
    if (app.bundleId != null) "${app.name} (${app.bundleId})" else app.name

  override val annotationElements: List<AnnotationElement>? by lazy {
    compactElements?.buildAnnotationElements()
  }

  override val screenshotBytes: ByteArray? by lazy { MacOsAxScreenshotCli.captureMainDisplay() }
}

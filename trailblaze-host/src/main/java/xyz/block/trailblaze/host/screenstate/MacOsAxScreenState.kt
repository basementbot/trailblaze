package xyz.block.trailblaze.host.screenstate

import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.api.ScreenState
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
      if (wholeScreen) MacOsAxTreeWalker.captureScreen() else MacOsAxTreeWalker.capture(pid)
    } catch (e: Exception) {
      System.err.println("[MacOsAxScreenState] AX tree capture failed (wholeScreen=$wholeScreen, pid=$pid): ${e.message}")
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

  override val viewHierarchyTextRepresentation: String? by lazy { compactElements?.text }

  override val annotationElements: List<AnnotationElement>? by lazy {
    compactElements?.buildAnnotationElements()
  }

  override val screenshotBytes: ByteArray? by lazy { MacOsAxScreenshotCli.captureMainDisplay() }
}

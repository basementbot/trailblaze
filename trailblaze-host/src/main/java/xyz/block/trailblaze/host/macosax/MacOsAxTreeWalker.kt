package xyz.block.trailblaze.host.macosax

import com.sun.jna.Pointer
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.MacOsAxAttributeValue
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Walks a live macOS Accessibility tree (rooted at an app's `AXUIElement`) into a
 * [TrailblazeNode] tree whose nodes carry full-fidelity [DriverNodeDetail.MacOsAx] detail.
 *
 * Parallel to [xyz.block.trailblaze.host.axe.AxeJsonMapper], but there's no subprocess/JSON
 * boundary — it reads the tree directly through [MacOsAxNative]. Per element it captures
 * **every** attribute name verbatim (exact `AX*` keys), decodes each value through
 * [MacOsAxAttributeValue], and records the element's actions and parameterized-attribute names.
 *
 * `AXChildren` is special-cased: it drives [TrailblazeNode.children] structurally and is never
 * stored in the attribute map (matching every other driver). Bounds are derived from `AXFrame`
 * (preferred) or `AXPosition` + `AXSize`.
 *
 * Depth is capped ([MAX_DEPTH]) purely as a defensive guard against pathological/cyclic trees;
 * real AX hierarchies are far shallower.
 */
object MacOsAxTreeWalker {

  private const val MAX_DEPTH = 200
  private const val CHILDREN_ATTRIBUTE = "AXChildren"

  /**
   * Roles whose subtrees we capture the node for but do NOT recurse into. The menu bar is the big
   * one: a macOS app's `AXMenuBar` fans out into hundreds of lazily-populated `AXMenuItem`s (Apple
   * menu → Recent Items, every app menu, …), and because we read *every* attribute of *every* node
   * via a separate cross-process AX call, walking it costs thousands of slow IPC round-trips —
   * prohibitive when a driver re-captures the tree on every poll. Menus are a separate interaction
   * concern (open one explicitly, then capture) rather than part of the always-on window tree.
   */
  private val ROLES_TO_NOT_RECURSE = setOf("AXMenuBar", "AXMenuBarItem", "AXMenu")

  /**
   * Captures the full AX tree for the process [pid]. Owns and releases the root application
   * element; borrowed child references are released with their containing arrays as the walk
   * unwinds (see [MacOsAxNative]'s ownership contract).
   */
  fun capture(pid: Int): TrailblazeNode {
    val app = MacOsAxNative.createApplication(pid)
    return try {
      // Opt into web-content accessibility so a captured browser tree includes the page's
      // AXWebArea (text, links) and not just the app chrome. No-op for non-browser apps.
      MacOsAxNative.enableEnhancedWebAccessibility(app)
      walk(app, NodeIdCounter(), depth = 0, ancestors = emptyList())
    } finally {
      MacOsAxNative.release(app)
    }
  }

  /**
   * Captures the **entire screen** as one tree: every application that owns an on-screen window
   * (via [MacOsAxNative.onScreenAppPids]) is walked and stitched under a synthetic desktop root.
   *
   * AX has no global "whole screen" element — you assemble it from per-app trees. Because every
   * app already reports `AXFrame`/`AXPosition` in the **same global screen coordinate space**
   * (top-left origin), the merged tree's bounds are all directly comparable — hit-testing and
   * taps work across apps with no per-app offset. This makes the macOS driver's capture match the
   * whole-screen model of the iOS / Android / Web drivers rather than being scoped to one app.
   *
   * A single shared [NodeIdCounter] spans all apps + the root so node ids stay unique across the
   * merged tree (required by hit-testing, ref generation, and index paths).
   */
  fun captureScreen(): TrailblazeNode {
    val counter = NodeIdCounter()
    val appTrees = MacOsAxNative.onScreenAppPids().mapNotNull { app ->
      val element = MacOsAxNative.createApplication(app.pid)
      try {
        // Opt browsers into exposing their web-content AX tree (see capture()).
        MacOsAxNative.enableEnhancedWebAccessibility(element)
        // Depth 1: the apps sit under the synthetic desktop root at depth 0.
        walk(element, counter, depth = 1, ancestors = emptyList())
      } catch (e: Exception) {
        System.err.println("[MacOsAxScreenWalker] skipping pid=${app.pid} (${app.ownerName}): ${e.message}")
        null
      } finally {
        MacOsAxNative.release(element)
      }
    }
    return TrailblazeNode(
      nodeId = counter.next(),
      bounds = null,
      children = appTrees,
      driverDetail = DriverNodeDetail.MacOsAx(
        pid = 0,
        // Synthetic assembly root (not a real AX element) — a stable container for the per-app
        // trees. Keyed with exact-style AX vocabulary so the renderer/selectors treat it uniformly.
        attributes = linkedMapOf(
          "AXRole" to MacOsAxAttributeValue.Str("MacOsScreen"),
          "AXTitle" to MacOsAxAttributeValue.Str("Screen"),
        ),
        actions = emptyList(),
        parameterizedAttributeNames = emptyList(),
      ),
    )
  }

  private class NodeIdCounter {
    private var next = 0L
    fun next(): Long = next++
  }

  /**
   * @param ancestors the chain of live `AXUIElement`s from the root down to (but not including)
   *   [element], used for cycle detection. Some apps expose a self-referential `AXChildren` (e.g.
   *   an app element that lists itself as a child), which would otherwise recurse until [MAX_DEPTH].
   */
  private fun walk(
    element: Pointer,
    counter: NodeIdCounter,
    depth: Int,
    ancestors: List<Pointer>,
  ): TrailblazeNode {
    val nodeId = counter.next()

    // Every attribute name → decoded value, except AXChildren (drives structure below).
    val attributes = LinkedHashMap<String, MacOsAxAttributeValue>()
    val attributeNames = MacOsAxNative.attributeNames(element)
    for (name in attributeNames) {
      if (name == CHILDREN_ATTRIBUTE) continue
      val ref = MacOsAxNative.copyAttributeValue(element, name) ?: continue
      try {
        attributes[name] = MacOsAxNative.decodeValue(ref)
      } finally {
        MacOsAxNative.release(ref)
      }
    }

    val detail = DriverNodeDetail.MacOsAx(
      pid = MacOsAxNative.pidOf(element) ?: -1,
      attributes = attributes,
      actions = MacOsAxNative.actionNames(element),
      parameterizedAttributeNames = MacOsAxNative.parameterizedAttributeNames(element),
    )

    // Capture the node itself, but don't recurse into prohibitively-large/slow subtrees (menus).
    val role = (attributes["AXRole"] as? MacOsAxAttributeValue.Str)?.value
    val children = if (depth >= MAX_DEPTH || role in ROLES_TO_NOT_RECURSE) {
      emptyList()
    } else {
      captureChildren(element, counter, depth, ancestors + element)
    }

    return TrailblazeNode(
      nodeId = nodeId,
      bounds = deriveBounds(attributes),
      children = children,
      driverDetail = detail,
    )
  }

  private fun captureChildren(
    element: Pointer,
    counter: NodeIdCounter,
    depth: Int,
    ancestors: List<Pointer>,
  ): List<TrailblazeNode> {
    val childrenArray = MacOsAxNative.copyAttributeValue(element, CHILDREN_ATTRIBUTE) ?: return emptyList()
    return try {
      val count = MacOsAxNative.arrayCount(childrenArray)
      (0 until count).mapNotNull { i ->
        // Borrowed — owned by childrenArray, released with it below. Recurse fully before release.
        val child = MacOsAxNative.arrayValueAt(childrenArray, i)
        // Cycle guard: a child identical to any ancestor on the current path would recurse forever.
        if (ancestors.any { MacOsAxNative.equal(it, child) }) {
          null
        } else {
          walk(child, counter, depth + 1, ancestors)
        }
      }
    } finally {
      MacOsAxNative.release(childrenArray)
    }
  }

  /**
   * Bounds from `AXFrame` (a `CGRect`) when present, else composed from `AXPosition` (`CGPoint`)
   * + `AXSize` (`CGSize`). Returns null when neither is available/decodable.
   */
  private fun deriveBounds(attributes: Map<String, MacOsAxAttributeValue>): TrailblazeNode.Bounds? {
    (attributes["AXFrame"] as? MacOsAxAttributeValue.Rect)?.let { rect ->
      return boundsOf(rect.origin.x, rect.origin.y, rect.size.width, rect.size.height)
    }
    val position = attributes["AXPosition"] as? MacOsAxAttributeValue.Point ?: return null
    val size = attributes["AXSize"] as? MacOsAxAttributeValue.Size ?: return null
    return boundsOf(position.x, position.y, size.width, size.height)
  }

  /**
   * The captured screenshot is the main display ([MacOsAxScreenshotCli.captureMainDisplay]), and
   * element frames are global screen points in that same space — so bounds are only meaningful (and
   * only overlay-able on the screenshot) when they fall on that display. Cached because the shell-out
   * is not free and the display size doesn't change within a capture.
   */
  private val mainDisplaySize: MacOsAxAppResolver.DisplaySize? by lazy {
    runCatching { MacOsAxAppResolver.mainDisplaySize() }.getOrNull()
  }

  private fun boundsOf(x: Double, y: Double, width: Double, height: Double): TrailblazeNode.Bounds? {
    // Reject frames that can't be drawn on the main-display screenshot. Browsers (Safari/Chrome)
    // report web-content frames in the full scrollable-document space — scrolled-out elements get
    // huge/negative coordinates, and unlaid/hidden elements get bogus ones (e.g. left=-149605).
    // Left unsanitized those dominate the tree (≈75% of nodes) and wreck the viewer's coordinate
    // transform. Keep bounds only when the frame intersects the display and isn't absurdly larger
    // than it; drop to null otherwise (the node stays in the tree for selector matching, it just
    // carries no overlay box). No display info → keep the raw frame.
    val d = mainDisplaySize
    if (d != null) {
      val right = x + width
      val bottom = y + height
      val intersectsDisplay = right > 0 && bottom > 0 && x < d.width && y < d.height
      val plausibleSize = width <= d.width * 2 && height <= d.height * 2
      if (!intersectsDisplay || !plausibleSize) return null
    }
    return TrailblazeNode.Bounds(
      left = x.toInt(),
      top = y.toInt(),
      right = (x + width).toInt(),
      bottom = (y + height).toInt(),
    )
  }
}

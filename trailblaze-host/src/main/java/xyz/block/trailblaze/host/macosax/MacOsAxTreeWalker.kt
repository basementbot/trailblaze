package xyz.block.trailblaze.host.macosax

import com.sun.jna.Pointer
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.host.macosax.MacOsAxOcclusion.Rect
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
   * Hard ceiling on how many elements one capture will walk.
   *
   * Without it, a single hostile page takes the whole daemon down. Chrome's "View Page Source"
   * renders every line of HTML as its own accessibility element; capturing that tab exhausted a 4GB
   * heap and killed the JVM with an OutOfMemoryError — not a slow capture, a dead daemon, taking
   * any run in flight with it. A DOM is unbounded and the tree is built in memory, so the walk has
   * to have a limit somewhere, and a truncated tree beats no daemon.
   *
   * 10,000 is twice what a busy whole-desktop capture actually needs (~5,000 elements across nine
   * apps), and low enough to matter: a session RETAINS one of these trees per logged step, so the
   * ceiling bounds the session's memory, not just one capture's. Truncation is logged rather than
   * silent — a capture that quietly stopped early would present as "the element isn't on screen".
   */
  private const val MAX_NODES_PER_CAPTURE = 10_000

  /**
   * Roles whose subtrees we capture the node for but do NOT recurse into.
   *
   * The menu **bar** is the cost: an app's `AXMenuBar` fans out into hundreds of lazily-populated
   * items (Apple menu → Recent Items, every app menu, every submenu), and walking it on every
   * capture would cost more than the rest of the tree combined — prohibitive when the driver
   * re-captures on every selector poll. It's reached on demand instead, via [MacOsAxMenu].
   *
   * `AXMenu` is excluded for the same reason, and this was worth learning the hard way. Context
   * menus opened by `macos_rightClick` weren't in the tree, so I let the walk descend into AXMenu —
   * and a whole-desktop capture went from 7s to 46s, then to **170s** once a few menus had been
   * opened. Gating on "the menu has on-screen bounds" didn't save it: apps keep menus alive, laid
   * out and enormous, long after they're closed. There is no cheap way to tell an open menu from a
   * dormant one during a walk.
   *
   * So menus stay out of the capture entirely, and both kinds are reached on demand instead:
   * [MacOsAxMenu.clickPath] for the menu bar, [MacOsAxMenu.clickOpenMenuItem] for whatever menu is
   * currently open. That costs a handful of AX calls when you ask, and nothing at all when you
   * don't.
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
  /**
   * The pid sentinels the whole driver uses to express capture SCOPE, so scope travels through the
   * plumbing that already exists rather than as a parallel flag. (It used to be a separate
   * `wholeScreen` boolean, and the two fell out of sync at two call sites, producing an empty tree
   * and silently unmatchable selectors. One channel, no drift.)
   */
  const val PID_ALL_APPS = 0
  const val PID_FRONTMOST_APP = -1

  /** Captures at whatever scope [pid] names: every app, the frontmost app, or one specific app. */
  fun captureFor(pid: Int): TrailblazeNode = when (pid) {
    PID_ALL_APPS -> captureScreen()
    PID_FRONTMOST_APP -> captureFrontmostApp()
    else -> capture(pid)
  }

  /**
   * Captures **only the app the user is currently working in**, under the same desktop root as a
   * full capture — the default, because a full one is too slow to drive with.
   *
   * Walking every on-screen app costs ~5 seconds; walking just the frontmost one costs well under
   * one, and the frontmost app is where all the action is: you cannot type into, click in, or read
   * a window you haven't brought forward. The other apps are still *named* (see
   * [MacOsAxScreenState]'s footer) so nothing is hidden — you switch with `macos_activateApp` and
   * the scope follows you, or you ask for the whole desktop explicitly and pay for it.
   *
   * Occlusion is still computed against EVERY on-screen window, not just this app's: what covers
   * the frontmost app is usually something belonging to another app, and an element buried under
   * another app's window is exactly as unclickable as one buried under its own.
   */
  fun captureFrontmostApp(): TrailblazeNode {
    val windows = MacOsAxNative.onScreenWindows()
    val frontPid = windows.firstOrNull()?.pid
      ?: return emptyDesktopRoot(NodeIdCounter())
    val counter = NodeIdCounter()
    val element = MacOsAxNative.createApplication(frontPid)
    val appTree = try {
      MacOsAxNative.enableEnhancedWebAccessibility(element)
      walk(element, counter, depth = 1, ancestors = emptyList())
    } catch (e: Exception) {
      System.err.println("[MacOsAxTreeWalker] frontmost app pid=$frontPid failed: ${e.message}")
      null
    } finally {
      MacOsAxNative.release(element)
    }
    return desktopRoot(counter, listOfNotNull(appTree).map { markOccluded(it, windows) })
  }

  fun captureScreen(): TrailblazeNode {
    val counter = NodeIdCounter()
    // Walked in parallel, one task per app. Every AX read is a blocking cross-process round trip —
    // the walk spends its time *waiting* on other processes, not computing — so walking nine apps
    // one after another serialized nine independent waits for no reason. A parallel stream turns
    // the wall-clock cost into roughly the slowest single app (a browser with a big page) instead
    // of the sum of all of them. The AX API is safe to call off the main thread, and the only
    // shared state is the atomic id counter.
    //
    // Order is preserved: CGWindowList hands back the apps front-to-back, and that ordering IS the
    // z-order the occlusion pass depends on, so the collector must not reshuffle them.
    val appTrees = MacOsAxNative.onScreenAppPids().parallelStream().map { app ->
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
    }.toList().filterNotNull()
    // Read once, not once per app: it's the same front-to-back window list for the whole capture.
    val onScreenWindows = MacOsAxNative.onScreenWindows()
    return desktopRoot(counter, appTrees.map { markOccluded(it, onScreenWindows) })
  }

  /**
   * The synthetic desktop root every capture hangs under — not a real AX element, just a stable
   * container so a one-app capture and a whole-desktop capture have the same shape and selectors,
   * refs and the renderer don't have to care which one they were handed.
   */
  private fun desktopRoot(counter: NodeIdCounter, apps: List<TrailblazeNode>): TrailblazeNode =
    TrailblazeNode(
      nodeId = counter.next(),
      bounds = null,
      children = apps,
      driverDetail = DriverNodeDetail.MacOsAx(
        pid = 0,
        attributes = linkedMapOf(
          "AXRole" to MacOsAxAttributeValue.Str("MacOsScreen"),
          "AXTitle" to MacOsAxAttributeValue.Str("Screen"),
        ),
        actions = emptyList(),
        parameterizedAttributeNames = emptyList(),
      ),
    )

  private fun emptyDesktopRoot(counter: NodeIdCounter): TrailblazeNode = desktopRoot(counter, emptyList())

  /**
   * Atomic because [captureScreen] walks the on-screen apps in parallel and they share one counter —
   * node ids must stay unique across the merged tree (hit-testing, ref generation and index paths
   * all key off them, so a collision is a silently wrong element, not a crash).
   */
  private class NodeIdCounter {
    private val next = java.util.concurrent.atomic.AtomicLong(0)
    fun next(): Long = next.getAndIncrement()

    /** How many nodes this capture has produced — the budget [MAX_NODES_PER_CAPTURE] bounds. */
    fun count(): Long = next.get()
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
    //
    // Read in ONE batched cross-process call. Reading them one at a time cost `1 + N` round trips
    // per element (~30), which across a whole-desktop capture is >150,000 trips and took ~25s —
    // paid again on every selector poll. Same attributes, same fidelity, an order of magnitude
    // fewer trips. Falls back to the per-attribute path if the batch call fails outright, so a
    // refusing element degrades to slow rather than to empty.
    val wanted = MacOsAxNative.attributeNames(element).filterNot { it == CHILDREN_ATTRIBUTE }
    val attributes: Map<String, MacOsAxAttributeValue> =
      MacOsAxNative.copyMultipleAttributeValues(element, wanted)
        ?: readAttributesIndividually(element, wanted)

    val detail = DriverNodeDetail.MacOsAx(
      pid = MacOsAxNative.pidOf(element) ?: -1,
      attributes = attributes,
      actions = MacOsAxNative.actionNames(element),
      parameterizedAttributeNames = MacOsAxNative.parameterizedAttributeNames(element),
    )

    // Capture the node itself, but don't recurse into prohibitively-large/slow subtrees (menus).
    val role = (attributes["AXRole"] as? MacOsAxAttributeValue.Str)?.value
    val bounds = deriveBounds(attributes)
    val children = when {
      depth >= MAX_DEPTH -> emptyList()
      role in ROLES_TO_NOT_RECURSE -> emptyList()
      // Stop descending once the capture has spent its node budget. Checked here rather than at the
      // top of walk() so the node itself is still emitted — the tree stays well-formed, it just
      // stops getting deeper.
      counter.count() >= MAX_NODES_PER_CAPTURE -> {
        warnTruncatedOnce()
        emptyList()
      }
      else -> captureChildren(element, counter, depth, ancestors + element)
    }

    return TrailblazeNode(
      nodeId = nodeId,
      bounds = bounds,
      children = children,
      driverDetail = detail,
    )
  }

  private val truncationWarned = java.util.concurrent.atomic.AtomicBoolean(false)

  /** Says so, once per capture-storm, rather than letting a truncated tree look like a missing UI. */
  private fun warnTruncatedOnce() {
    if (truncationWarned.compareAndSet(false, true)) {
      System.err.println(
        "[MacOsAxTreeWalker] capture hit the $MAX_NODES_PER_CAPTURE-element ceiling and stopped " +
          "descending. Some app on screen is exposing an enormous accessibility tree (Chrome's " +
          "View Page Source does this). Elements below the cut are not in the tree.",
      )
    }
  }

  /** One `AXUIElementCopyAttributeValue` per attribute — the fallback when the batch read fails. */
  private fun readAttributesIndividually(
    element: Pointer,
    names: List<String>,
  ): Map<String, MacOsAxAttributeValue> {
    val attributes = LinkedHashMap<String, MacOsAxAttributeValue>()
    for (name in names) {
      val ref = MacOsAxNative.copyAttributeValue(element, name) ?: continue
      try {
        attributes[name] = MacOsAxNative.decodeValue(ref)
      } finally {
        MacOsAxNative.release(ref)
      }
    }
    return attributes
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

  /**
   * Marks every element of one app's subtree that is buried behind a window stacked above it.
   *
   * Occlusion is decided per **window**, because that's what actually hides things on a desktop:
   * find the AXWindow's place in the front-to-back [windows] list, take every window ahead of it as
   * an occluder, and flag the window's descendants that those occluders fully cover. Elements with
   * no bounds are left alone — there's no geometry to judge them by.
   *
   * The AX API exposes no stacking order of its own, so an AXWindow is located in the CG list by
   * owning pid plus the best-overlapping rect: two windows of the same app can share a size and
   * position is what separates them. A window we can't place is treated as un-occluded — the
   * conservative direction, since a wrongly-hidden element is invisible to the agent and
   * unexplainable to the user, while a wrongly-shown one merely costs tokens.
   */
  private fun markOccluded(appNode: TrailblazeNode, windows: List<MacOsAxNative.OnScreenWindow>): TrailblazeNode {
    val children = appNode.children.map { windowNode ->
      val bounds = windowNode.bounds
      val pid = (windowNode.driverDetail as? DriverNodeDetail.MacOsAx)?.pid
      if (bounds == null || pid == null) return@map windowNode

      val index = indexOfWindow(pid, bounds, windows) ?: return@map windowNode
      val occluders = windows.take(index).map { Rect(it.left, it.top, it.right, it.bottom) }
      if (occluders.isEmpty()) return@map windowNode

      applyOcclusion(windowNode, occluders)
    }
    return appNode.copy(children = children)
  }

  /** Recursively flags [node] and its descendants that [occluders] completely cover. */
  private fun applyOcclusion(node: TrailblazeNode, occluders: List<Rect>): TrailblazeNode {
    val detail = node.driverDetail as? DriverNodeDetail.MacOsAx
    val bounds = node.bounds
    val hidden = detail != null && bounds != null &&
      MacOsAxOcclusion.isFullyCovered(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), occluders)
    return node.copy(
      driverDetail = if (hidden) detail!!.copy(occluded = true) else node.driverDetail,
      children = node.children.map { applyOcclusion(it, occluders) },
    )
  }

  /** The index of the window owned by [pid] whose rect best overlaps [bounds], or null if none do. */
  private fun indexOfWindow(
    pid: Int,
    bounds: TrailblazeNode.Bounds,
    windows: List<MacOsAxNative.OnScreenWindow>,
  ): Int? {
    var bestIndex: Int? = null
    var bestOverlap = 0L
    windows.forEachIndexed { index, window ->
      if (window.pid != pid) return@forEachIndexed
      val overlapX = minOf(bounds.right, window.right) - maxOf(bounds.left, window.left)
      val overlapY = minOf(bounds.bottom, window.bottom) - maxOf(bounds.top, window.top)
      if (overlapX <= 0 || overlapY <= 0) return@forEachIndexed
      val overlap = overlapX.toLong() * overlapY
      if (overlap > bestOverlap) {
        bestOverlap = overlap
        bestIndex = index
      }
    }
    return bestIndex
  }
}

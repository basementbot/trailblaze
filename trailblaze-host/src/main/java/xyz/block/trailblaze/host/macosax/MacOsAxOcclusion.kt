package xyz.block.trailblaze.host.macosax

import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Decides which elements of a whole-desktop capture a person could actually see and click.
 *
 * On a phone there is one app on screen, so "in the tree" and "visible" are nearly the same thing.
 * On a desktop they are not: a single capture of a normal machine ran to ~4,900 elements across 9
 * apps, and most of them sat in windows buried behind other windows. Handing that to an agent is
 * both enormously wasteful and actively misleading — [xyz.block.trailblaze.api.SnapshotDetail.OCCLUDED]
 * already states the contract ("the LLM can't actually click an occluded element (the topmost
 * overlay intercepts the click), so listing them in the prompt is misleading"); this is the macOS
 * implementation of it, alongside Playwright's `document.elementFromPoint`.
 *
 * **Why geometry and not a hit-test.** macOS does expose a real hit-test
 * (`AXUIElementCopyElementAtPosition` on the system-wide element honors z-order), but it's one IPC
 * round-trip per query — thousands of them per capture. The window list gives us z-order and window
 * rects in a single call, and window-level stacking is what actually hides things on a desktop, so
 * occlusion is computed geometrically per window and costs nothing.
 *
 * Coordinates are AX points with a top-left origin — the same space as `AXFrame` and
 * [TrailblazeNode.Bounds], so element and window rects are directly comparable.
 */
object MacOsAxOcclusion {

  /** An axis-aligned rectangle in global screen points, top-left origin. */
  data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun intersects(other: Rect): Boolean =
      left < other.right && right > other.left && top < other.bottom && bottom > other.top
  }

  fun Rect.toBounds(): TrailblazeNode.Bounds = TrailblazeNode.Bounds(left, top, right, bottom)

  fun TrailblazeNode.Bounds.toRect(): Rect = Rect(left, top, right, bottom)

  /**
   * True when [rect] is entirely hidden beneath [occluders] — i.e. no part of it is left showing.
   *
   * Deliberately *fully* covered, not "mostly": a partially-covered element is still visible and
   * still clickable on its exposed part, and a person would say they can see it. [visiblePoint]
   * is what keeps that honest by aiming the click at a part that isn't buried.
   *
   * Works by subtraction — clip [rect] against the first occluder that touches it, then require
   * every surviving fragment to be covered by the rest. Terminates because each recursion drops an
   * occluder and the fragments strictly shrink. At desktop scale (a dozen windows) this is free.
   */
  fun isFullyCovered(rect: Rect, occluders: List<Rect>): Boolean {
    if (rect.isEmpty) return true
    val hitIndex = occluders.indexOfFirst { it.intersects(rect) }
    if (hitIndex < 0) return false
    val occluder = occluders[hitIndex]
    val remaining = occluders.filterIndexed { i, _ -> i != hitIndex }
    return subtract(rect, occluder).all { isFullyCovered(it, remaining) }
  }

  /**
   * A point inside [rect] that no occluder covers — where a click on this element should land.
   *
   * The centre is the natural target and is used whenever it's clear. When it isn't, clicking the
   * centre anyway would land on the window *on top*, so the driver would silently act on the wrong
   * app: exactly the failure the whole visibility pass exists to prevent. Falls back to the centre
   * of the largest exposed fragment, and returns null only when nothing is exposed at all (the
   * caller should refuse to click rather than guess).
   */
  fun visiblePoint(rect: Rect, occluders: List<Rect>): Pair<Int, Int>? {
    if (rect.isEmpty) return null
    val centre = (rect.left + rect.right) / 2 to (rect.top + rect.bottom) / 2
    if (occluders.none { it.contains(centre.first, centre.second) }) return centre

    val exposed = exposedFragments(rect, occluders)
      .filterNot { it.isEmpty }
      .maxByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) }
      ?: return null
    return (exposed.left + exposed.right) / 2 to (exposed.top + exposed.bottom) / 2
  }

  private fun Rect.contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

  /** The parts of [rect] left showing once every occluder is subtracted. */
  private fun exposedFragments(rect: Rect, occluders: List<Rect>): List<Rect> {
    var fragments = listOf(rect)
    for (occluder in occluders) {
      fragments = fragments.flatMap { subtract(it, occluder) }
      if (fragments.isEmpty()) break
    }
    return fragments
  }

  /**
   * [rect] minus [occluder], as up to four rectangles (the bands above, below, left and right of
   * the overlap). Returns [rect] untouched when they don't overlap.
   */
  private fun subtract(rect: Rect, occluder: Rect): List<Rect> {
    if (!rect.intersects(occluder)) return listOf(rect)
    val pieces = mutableListOf<Rect>()
    if (occluder.top > rect.top) pieces += Rect(rect.left, rect.top, rect.right, occluder.top)
    if (occluder.bottom < rect.bottom) pieces += Rect(rect.left, occluder.bottom, rect.right, rect.bottom)
    val bandTop = maxOf(rect.top, occluder.top)
    val bandBottom = minOf(rect.bottom, occluder.bottom)
    if (occluder.left > rect.left) pieces += Rect(rect.left, bandTop, occluder.left, bandBottom)
    if (occluder.right < rect.right) pieces += Rect(occluder.right, bandTop, rect.right, bandBottom)
    return pieces.filterNot { it.isEmpty }
  }
}

package xyz.block.trailblaze.host.macosax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.host.macosax.MacOsAxOcclusion.Rect

/**
 * The occlusion geometry is the whole basis for "what can the user actually see and click" on the
 * desktop driver, so it's pure and tested directly rather than only through a live capture.
 */
class MacOsAxOcclusionTest {

  private val button = Rect(100, 100, 200, 140)

  @Test
  fun `nothing on top means visible`() {
    assertFalse(MacOsAxOcclusion.isFullyCovered(button, emptyList()))
    assertFalse(MacOsAxOcclusion.isFullyCovered(button, listOf(Rect(0, 0, 50, 50))))
  }

  @Test
  fun `a window covering the element hides it`() {
    assertTrue(MacOsAxOcclusion.isFullyCovered(button, listOf(Rect(0, 0, 500, 500))))
  }

  @Test
  fun `exactly flush cover still counts as covered`() {
    assertTrue(MacOsAxOcclusion.isFullyCovered(button, listOf(button)))
  }

  @Test
  fun `a partly covering window leaves the element visible`() {
    // Covers the right half only — a person can still see (and click) the left half.
    assertFalse(MacOsAxOcclusion.isFullyCovered(button, listOf(Rect(150, 90, 300, 200))))
  }

  @Test
  fun `two windows that each cover half together hide the element`() {
    // Neither occluder covers it alone; the union does. This is the case a naive
    // "is it inside any one occluder" check gets wrong.
    val left = Rect(50, 50, 150, 300)
    val right = Rect(150, 50, 400, 300)
    assertFalse(MacOsAxOcclusion.isFullyCovered(button, listOf(left)))
    assertFalse(MacOsAxOcclusion.isFullyCovered(button, listOf(right)))
    assertTrue(MacOsAxOcclusion.isFullyCovered(button, listOf(left, right)))
  }

  @Test
  fun `touching edges do not occlude`() {
    // Occluder's right edge is exactly the element's left edge — adjacent, not overlapping.
    assertFalse(MacOsAxOcclusion.isFullyCovered(button, listOf(Rect(0, 100, 100, 140))))
  }

  @Test
  fun `click point is the centre when the centre is clear`() {
    assertEquals(150 to 120, MacOsAxOcclusion.visiblePoint(button, emptyList()))
  }

  @Test
  fun `click point moves off the centre when the centre is buried`() {
    // A window covers the right half, including the element's centre (150,120). Clicking the centre
    // would land on that window instead — the exact silent wrong-app click this exists to prevent.
    val occluder = Rect(140, 90, 300, 200)
    val point = MacOsAxOcclusion.visiblePoint(button, listOf(occluder))
    assertNotNull(point)
    val (x, y) = point!!
    assertTrue("click point $point must sit on the exposed left strip", x in 100 until 140)
    assertTrue("click point $point must sit inside the element", y in 100 until 140)
    assertFalse(
      "click point must not be under the occluder",
      x >= occluder.left && x < occluder.right && y >= occluder.top && y < occluder.bottom,
    )
  }

  @Test
  fun `fully covered element has no click point`() {
    assertNull(MacOsAxOcclusion.visiblePoint(button, listOf(Rect(0, 0, 500, 500))))
  }
}

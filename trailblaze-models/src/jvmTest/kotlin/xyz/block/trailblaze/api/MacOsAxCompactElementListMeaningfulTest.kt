package xyz.block.trailblaze.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the default listing to "what the agent can actually act on", and pins [SnapshotDetail.ALL_ELEMENTS]
 * to being the escape hatch from it.
 *
 * This regressed silently once already: the emit gate read `hasIdentifiableProperties || role != null`,
 * and since every AX element reports an `AXRole`, it could never drop anything — the trim was a no-op
 * and ALL_ELEMENTS had nothing to bypass. A test that only asserts "the button is listed" would have
 * stayed green through that, so these assert the *scaffolding is absent* too.
 */
class MacOsAxCompactElementListMeaningfulTest {

  private var nextId = 0L

  private fun node(
    role: String,
    title: String? = null,
    identifier: String? = null,
    actions: List<String> = emptyList(),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode {
    val attributes = linkedMapOf<String, MacOsAxAttributeValue>(
      "AXRole" to MacOsAxAttributeValue.Str(role),
    )
    title?.let { attributes["AXTitle"] = MacOsAxAttributeValue.Str(it) }
    identifier?.let { attributes["AXIdentifier"] = MacOsAxAttributeValue.Str(it) }
    return TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = children,
      driverDetail = DriverNodeDetail.MacOsAx(
        pid = 1,
        attributes = attributes,
        actions = actions,
      ),
    )
  }

  /** A window whose button is buried under two layers of anonymous layout scaffolding. */
  private fun tree() = node(
    role = "AXWindow",
    title = "Calculator",
    children = listOf(
      node(
        role = "AXGroup", // anonymous hosting view — pure scaffolding
        children = listOf(
          node(role = "AXSplitGroup"), // ditto
          node(role = "AXButton", identifier = "Nine", actions = listOf("AXPress")),
          node(role = "AXStaticText", title = "42"),
        ),
      ),
    ),
  )

  private fun render(vararg details: SnapshotDetail): String =
    MacOsAxCompactElementList.build(tree(), details.toSet(), screenHeight = 1080, screenWidth = 1920).text

  @Test
  fun `default listing keeps what the agent can act on`() {
    val text = render()
    assertTrue("the button must be listed", text.contains("AXButton"))
    assertTrue("readable content must be listed", text.contains("42"))
    assertTrue("the window's title names the context", text.contains("Calculator"))
  }

  @Test
  fun `default listing drops anonymous layout scaffolding`() {
    val text = render()
    assertFalse("bare AXGroup is noise, not something to click", text.contains("AXGroup"))
    assertFalse("bare AXSplitGroup is noise too", text.contains("AXSplitGroup"))
  }

  @Test
  fun `ALL_ELEMENTS is a real escape hatch and shows the scaffolding`() {
    val text = render(SnapshotDetail.ALL_ELEMENTS)
    assertTrue("ALL_ELEMENTS must surface the containers the default view trims", text.contains("AXGroup"))
    assertTrue(text.contains("AXSplitGroup"))
    assertTrue("and must still show the real elements", text.contains("AXButton"))
  }

  @Test
  fun `a container with an identifier is kept — selectors target it`() {
    val tree = node(
      role = "AXWindow",
      title = "Calculator",
      children = listOf(node(role = "AXGroup", identifier = "CalculatorKeypadView")),
    )
    val text = MacOsAxCompactElementList.build(tree, emptySet(), 1080, 1920).text
    assertTrue(text.contains("CalculatorKeypadView"))
  }

  @Test
  fun `occluded elements are dropped by default and restored by OCCLUDED`() {
    val buried = TrailblazeNode(
      nodeId = 99,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = emptyList(),
      driverDetail = DriverNodeDetail.MacOsAx(
        pid = 1,
        attributes = linkedMapOf(
          "AXRole" to MacOsAxAttributeValue.Str("AXButton"),
          "AXIdentifier" to MacOsAxAttributeValue.Str("Nine"),
        ),
        actions = listOf("AXPress"),
        occluded = true,
      ),
    )
    val root = node(role = "AXWindow", title = "Calculator").let {
      it.copy(children = listOf(buried))
    }

    val default = MacOsAxCompactElementList.build(root, emptySet(), 1080, 1920).text
    assertFalse("a buried button must not be offered to the agent", default.contains("Nine"))
    assertTrue("but it must be accounted for", default.contains("hidden behind other windows"))

    val withOccluded = MacOsAxCompactElementList.build(root, setOf(SnapshotDetail.OCCLUDED), 1080, 1920).text
    assertTrue(withOccluded.contains("Nine"))
    assertTrue(withOccluded.contains("(occluded)"))
  }
}

package xyz.block.trailblaze.api

/**
 * Compact element-list renderer for macOS desktop trees backed by [DriverNodeDetail.MacOsAx]
 * (Apple Accessibility / `AXUIElement` path). Reads out of the driver's full-fidelity
 * [DriverNodeDetail.MacOsAx.attributes] map using exact native `AX*` keys — no normalized
 * cross-platform vocabulary.
 *
 * Emission shape:
 *   `[ref] AXRole "title: value" [id=foo] [subrole=…] [actions=…] [disabled] {bounds}`
 *
 * Title vs value:
 *   macOS controls often carry both `AXTitle` (a static label, e.g. "Zoom") and `AXValue`
 *   (the live data, e.g. the text in a field). We emit `"title: value"` when they differ and
 *   fall back to whichever single one exists.
 */
object MacOsAxCompactElementList {

  private const val MAX_LABEL_LENGTH = 120

  data class CompactElements(
    val text: String,
    val elementNodeIds: List<Long>,
    val elementBounds: List<TrailblazeNode.Bounds> = emptyList(),
    val refMapping: Map<String, Long> = emptyMap(),
  )

  fun build(
    root: TrailblazeNode,
    details: Set<SnapshotDetail> = emptySet(),
    screenHeight: Int = 0,
    screenWidth: Int = 0,
  ): CompactElements {
    val includeBounds = SnapshotDetail.BOUNDS in details
    val includeOffscreen = SnapshotDetail.OFFSCREEN in details
    val includeAllElements = SnapshotDetail.ALL_ELEMENTS in details
    val lines = mutableListOf<String>()
    val elementNodeIds = mutableListOf<Long>()
    val elementBounds = mutableListOf<TrailblazeNode.Bounds>()
    val refMapping = mutableMapOf<String, Long>()
    val refTracker = ElementRef.RefTracker()
    var offscreenCount = 0
    walk(
      node = root,
      depth = 0,
      lines = lines,
      elementNodeIds = elementNodeIds,
      elementBounds = elementBounds,
      refMapping = refMapping,
      refTracker = refTracker,
      includeBounds = includeBounds,
      includeOffscreen = includeOffscreen,
      includeAllElements = includeAllElements,
      screenHeight = screenHeight,
      screenWidth = screenWidth,
      onOffscreen = { offscreenCount++ },
    )
    val text = buildString {
      if (lines.isEmpty()) append("(no elements found)")
      else append(lines.joinToString("\n"))
      if (!includeOffscreen && offscreenCount > 0) {
        append("\n($offscreenCount offscreen elements hidden — use --offscreen to show)")
      }
    }
    return CompactElements(
      text = text,
      elementNodeIds = elementNodeIds,
      elementBounds = elementBounds,
      refMapping = refMapping,
    )
  }

  private fun walk(
    node: TrailblazeNode,
    depth: Int,
    lines: MutableList<String>,
    elementNodeIds: MutableList<Long>,
    elementBounds: MutableList<TrailblazeNode.Bounds>,
    refMapping: MutableMap<String, Long>,
    refTracker: ElementRef.RefTracker,
    includeBounds: Boolean,
    includeOffscreen: Boolean,
    includeAllElements: Boolean,
    screenHeight: Int,
    screenWidth: Int,
    onOffscreen: () -> Unit,
  ) {
    val detail = node.driverDetail as? DriverNodeDetail.MacOsAx
    if (detail == null) {
      node.children.forEach {
        walk(it, depth, lines, elementNodeIds, elementBounds, refMapping, refTracker,
          includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, onOffscreen)
      }
      return
    }

    val offscreen = CompactElementListUtils.isOffscreen(node, screenHeight, screenWidth)
    if (offscreen && !includeOffscreen) {
      if (detail.hasIdentifiableProperties) onOffscreen()
      return
    }

    val composite = composeDisplayText(detail)
    val role = detail.role?.takeIf { it.isNotBlank() }
    val descriptor = when {
      composite != null && role != null -> "$role \"$composite\""
      composite != null -> "\"$composite\""
      role != null -> role
      else -> null
    }

    val shouldEmit = descriptor != null && (
      detail.hasIdentifiableProperties || role != null || includeAllElements
    )

    if (shouldEmit) {
      val indent = "  ".repeat(depth)
      val annotations = buildAnnotations(detail, composite)
      val boundsStr = if (includeBounds) CompactElementListUtils.boundsAnnotation(node) else ""
      val offscreenStr = if (includeOffscreen && offscreen) " (offscreen)" else ""
      val center = node.bounds?.let { it.centerX to it.centerY } ?: (0 to 0)
      val ref = refTracker.ref(composite, role, center.first, center.second)
      lines.add("$indent[$ref] $descriptor$annotations$boundsStr$offscreenStr")
      elementNodeIds.add(node.nodeId)
      refMapping[ref] = node.nodeId
      node.bounds?.let { elementBounds.add(it) }
      node.children.forEach {
        walk(it, depth + 1, lines, elementNodeIds, elementBounds, refMapping, refTracker,
          includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, onOffscreen)
      }
    } else {
      // Structural / empty container — skip, recurse at the same depth.
      node.children.forEach {
        walk(it, depth, lines, elementNodeIds, elementBounds, refMapping, refTracker,
          includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, onOffscreen)
      }
    }
  }

  /**
   * Compose `AXTitle` + `AXValue` into a single display string, mirroring the AXe renderer's
   * label/value handling but reading from the native attribute map.
   */
  internal fun composeDisplayText(detail: DriverNodeDetail.MacOsAx): String? {
    val title = detail.stringAttribute("AXTitle")
    val value = detail.stringAttribute("AXValue")
    val primary = when {
      title != null && value != null && title != value -> "$title: $value"
      title != null -> title
      value != null -> value
      else -> detail.stringAttribute("AXDescription")
    }
    return primary?.truncate(MAX_LABEL_LENGTH)
  }

  private fun buildAnnotations(detail: DriverNodeDetail.MacOsAx, visibleText: String?): String {
    val parts = mutableListOf<String>()
    detail.stringAttribute("AXIdentifier")?.let { parts += "[id=$it]" }
    detail.stringAttribute("AXSubrole")?.let { parts += "[subrole=$it]" }
    if (detail.actions.isNotEmpty()) {
      parts += "[actions=${detail.actions.joinToString(",")}]"
    }
    detail.stringAttribute("AXHelp")
      ?.takeIf { it != visibleText }
      ?.let { parts += "[help=$it]" }
    // AXEnabled comes back as a Bool; surface a [disabled] flag when explicitly false.
    (detail.attributes["AXEnabled"] as? MacOsAxAttributeValue.Bool)
      ?.takeIf { !it.value }
      ?.let { parts += "[disabled]" }
    return if (parts.isEmpty()) "" else " ${parts.joinToString(" ")}"
  }

  private fun String.truncate(maxLength: Int): String =
    if (length <= maxLength) this else substring(0, maxLength - 1) + "…"
}

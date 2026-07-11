package xyz.block.trailblaze.api

import xyz.block.trailblaze.util.escapeForSelector

// ---------------------------------------------------------------------------
// macOS AX strategies — selector-generation strategies for DriverNodeDetail.MacOsAx nodes,
// driven off Apple's native macOS AX vocabulary (exact AX* attribute keys and native action
// names) rather than any normalized cross-platform shape. Mirrors [iosAxeStrategies].
// ---------------------------------------------------------------------------

internal fun macOsAxStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.MacOsAx,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "AXIdentifier" to {
    detail.stringAttribute("AXIdentifier")?.let { id ->
      selectorWith(DriverNodeMatch.MacOsAx(identifier = id))
    }
  },
  "AXTitle" to {
    detail.stringAttribute("AXTitle")?.let { title ->
      selectorWith(DriverNodeMatch.MacOsAx(titleRegex = escapeForSelector(title)))
    }
  },
  "AXTitle + AXRole" to {
    val title = detail.stringAttribute("AXTitle")
    val role = detail.role
    if (title != null && role != null) {
      selectorWith(
        DriverNodeMatch.MacOsAx(
          titleRegex = escapeForSelector(title),
          roleRegex = escapeForSelector(role),
        ),
      )
    } else {
      null
    }
  },
  "AXValue" to {
    if (detail.stringAttribute("AXTitle") == null) {
      detail.stringAttribute("AXValue")?.let { value ->
        selectorWith(DriverNodeMatch.MacOsAx(valueRegex = escapeForSelector(value)))
      }
    } else {
      null
    }
  },
  "AXDescription" to {
    if (detail.stringAttribute("AXTitle") == null && detail.stringAttribute("AXValue") == null) {
      detail.stringAttribute("AXDescription")?.let { description ->
        selectorWith(DriverNodeMatch.MacOsAx(descriptionRegex = escapeForSelector(description)))
      }
    } else {
      null
    }
  },
  "AXSubrole + AXRole" to {
    val subrole = detail.stringAttribute("AXSubrole")
    val role = detail.role
    if (subrole != null && role != null) {
      selectorWith(
        DriverNodeMatch.MacOsAx(
          subroleRegex = escapeForSelector(subrole),
          roleRegex = escapeForSelector(role),
        ),
      )
    } else {
      null
    }
  },
  "AXIdentifier + AXTitle" to {
    val id = detail.stringAttribute("AXIdentifier")
    val title = detail.stringAttribute("AXTitle")
    if (id != null && title != null) {
      selectorWith(
        DriverNodeMatch.MacOsAx(
          identifier = id,
          titleRegex = escapeForSelector(title),
        ),
      )
    } else {
      null
    }
  },
  "AXRole" to {
    detail.role?.let { role ->
      selectorWith(DriverNodeMatch.MacOsAx(roleRegex = escapeForSelector(role)))
    }
  },
  // Trailing hierarchy/spatial/index — shared across all generators.
  childOfUniqueParentStrategy(root, target, detail, parentMap),
  containsUniqueChildStrategy(root, target, detail),
  spatialStrategy(root, target, parentMap),
  indexFallbackStrategy(root, target, detail),
)

// ---------------------------------------------------------------------------
// macOS AX structural strategies — identity + role/subrole, no content.
// ---------------------------------------------------------------------------

internal fun namedStructuralMacOsAxStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.MacOsAx,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Structural: AXIdentifier" to {
    detail.stringAttribute("AXIdentifier")?.let { id ->
      selectorWith(DriverNodeMatch.MacOsAx(identifier = id))
    }
  },
  "Structural: AXRole + AXSubrole" to {
    val role = detail.role
    val subrole = detail.stringAttribute("AXSubrole")
    if (role != null && subrole != null) {
      selectorWith(
        DriverNodeMatch.MacOsAx(
          roleRegex = escapeForSelector(role),
          subroleRegex = escapeForSelector(subrole),
        ),
      )
    } else {
      null
    }
  },
  "Structural: AXRole" to {
    detail.role?.let { role ->
      selectorWith(DriverNodeMatch.MacOsAx(roleRegex = escapeForSelector(role)))
    }
  },
  // Trailing hierarchy/spatial/index — shared across all generators.
  structuralChildOfParentStrategy(root, target, detail, parentMap),
  structuralChildOfLabeledParentStrategy(root, target, detail, parentMap),
  structuralContainsChildStrategy(root, target),
  structuralSpatialStrategy(root, target, parentMap),
  structuralContentAnchoredSpatialStrategy(root, target, parentMap),
  structuralScopedIndexStrategy(root, target, detail, parentMap),
  structuralIndexFallbackStrategy(root, target, detail, name = "Structural: AXRole + index"),
)

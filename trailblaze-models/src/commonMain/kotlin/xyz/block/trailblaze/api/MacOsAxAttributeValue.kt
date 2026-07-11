package xyz.block.trailblaze.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single decoded macOS Accessibility (AX) attribute value, as returned by
 * `AXUIElementCopyAttributeValue` and decoded from its underlying CoreFoundation /
 * `AXValueRef` type.
 *
 * This is a fidelity-first discriminated union: the goal is to preserve **100%** of what
 * macOS reports for an element, using **exact native vocabulary**, with zero normalization
 * into a cross-platform shape (see `DriverNodeDetail.MacOsAx` and the driver plan in
 * `docs/devlog/2026-07-10-macos-ax-driver.md`, decision #0). Concretely:
 *
 * - Core Graphics struct values decode into variants whose field names mirror the real
 *   struct fields exactly — [Point] matches `CGPoint { x; y }`, [Size] matches
 *   `CGSize { width; height }` (not `w`/`h`), [Rect] matches
 *   `CGRect { origin: CGPoint; size: CGSize }` (nested, not flattened), and [Range] matches
 *   `CFRange { location; length }`.
 * - Nested `AXUIElement`-typed attributes (`AXParent`, `AXMinimizeButton`, `AXProxy`,
 *   `AXFocusedUIElement`, …) are summarized by identity via [ElementRef] rather than being
 *   recursively expanded — expanding them would introduce `AXParent`-induced cycles and
 *   unbounded duplication. (`AXChildren` is handled separately: it drives
 *   [TrailblazeNode.children] structurally and is never stored as an attribute value.)
 * - [Unknown] is a deliberate fidelity-preserving fallback: when the decoder doesn't yet
 *   recognize a CoreFoundation type, it records that *something* was present (via its
 *   `CFTypeID`) rather than silently dropping the attribute.
 */
@Serializable
sealed interface MacOsAxAttributeValue {

  /** A `CFStringRef` value. */
  @Serializable
  @SerialName("str")
  data class Str(val value: String) : MacOsAxAttributeValue

  /**
   * A `CFNumberRef` value. Stored as [Double]; macOS `CFNumber`s span integer and floating
   * point representations, and a double round-trips both without a lossy type tag here.
   */
  @Serializable
  @SerialName("num")
  data class Num(val value: Double) : MacOsAxAttributeValue

  /** A `CFBooleanRef` value. */
  @Serializable
  @SerialName("bool")
  data class Bool(val value: Boolean) : MacOsAxAttributeValue

  /**
   * A `CFArrayRef` value whose elements are themselves decoded attribute values.
   * (`AXChildren` is excluded from the attribute map entirely and does not appear here.)
   */
  @Serializable
  @SerialName("arr")
  data class Arr(val values: List<MacOsAxAttributeValue>) : MacOsAxAttributeValue

  /** An `AXValueRef` wrapping a `CGPoint` (decoded via `AXValueGetValue`). */
  @Serializable
  @SerialName("point")
  data class Point(val x: Double, val y: Double) : MacOsAxAttributeValue

  /** An `AXValueRef` wrapping a `CGSize`. Field names match `CGSize` exactly. */
  @Serializable
  @SerialName("size")
  data class Size(val width: Double, val height: Double) : MacOsAxAttributeValue

  /**
   * An `AXValueRef` wrapping a `CGRect`. Nested `origin`/`size` mirror the real
   * `CGRect { origin: CGPoint; size: CGSize }` layout rather than flattening into four
   * scalars.
   */
  @Serializable
  @SerialName("rect")
  data class Rect(val origin: Point, val size: Size) : MacOsAxAttributeValue

  /** An `AXValueRef` wrapping a `CFRange`. Field names match `CFRange` exactly. */
  @Serializable
  @SerialName("range")
  data class Range(val location: Long, val length: Long) : MacOsAxAttributeValue

  /**
   * A nested `AXUIElement` reference (e.g. `AXParent`, `AXMinimizeButton`, `AXProxy`),
   * summarized by identity rather than recursively expanded. Any of the identity fields may
   * be null when the referenced element doesn't expose that attribute.
   */
  @Serializable
  @SerialName("elementRef")
  data class ElementRef(
    val role: String? = null,
    val identifier: String? = null,
    val title: String? = null,
  ) : MacOsAxAttributeValue

  /**
   * Fidelity-preserving fallback for a CoreFoundation value the decoder doesn't yet
   * recognize. Records the raw `CFTypeID` so the presence (and type) of the attribute is
   * never silently lost.
   */
  @Serializable
  @SerialName("unknown")
  data class Unknown(val cfTypeId: Long) : MacOsAxAttributeValue
}

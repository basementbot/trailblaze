package xyz.block.trailblaze.host.macosax

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import xyz.block.trailblaze.api.MacOsAxAttributeValue

/**
 * JNA layer over Apple's Accessibility (`AXUIElement`) and CoreFoundation APIs.
 *
 * Promoted from the Phase 0 spike (`MacOsAxNativeSpikeTest`, since deleted) — the spike proved
 * `NativeLibrary.getInstance("ApplicationServices")` resolves the `AX*` symbols directly, that
 * `AXUIElementCreateApplication` + `AXUIElementCopyAttributeValue` round-trip, and that
 * `CFString` marshaling works. This is the real thing: it adds the fuller function set, the
 * `AXValueGetValue` struct decode (`CGPoint`/`CGSize`/`CGRect`/`CFRange`), and — unlike the
 * throwaway spike — strict `CFRelease` discipline for every reference this code takes ownership
 * of (every `Copy`/`Create` call returns a +1 reference we must release).
 *
 * Ownership contract used throughout:
 * - [copyAttributeValue] returns a reference the **caller owns** and must pass to [release].
 * - [decodeValue] does **not** release its argument (the caller owns it).
 * - Values obtained via `CFArrayGetValueAtIndex` are **borrowed** (owned by the containing
 *   array) and must NOT be released individually — only the array itself is released.
 */
object MacOsAxNative {

  // AXValueType enum (ApplicationServices/AXValue.h)
  private const val K_AX_VALUE_CG_POINT = 1
  private const val K_AX_VALUE_CG_SIZE = 2
  private const val K_AX_VALUE_CG_RECT = 3
  private const val K_AX_VALUE_CF_RANGE = 4

  /**
   * `kAXValueAXErrorType` — the placeholder `AXUIElementCopyMultipleAttributeValues` puts in a slot
   * whose attribute could not be read. In-band, not omitted, so it has to be filtered explicitly.
   */
  private const val K_AX_VALUE_AX_ERROR = 5

  // CFNumberType (CoreFoundation/CFNumber.h) — read every CFNumber as a double for fidelity.
  private const val K_CF_NUMBER_DOUBLE_TYPE = 13

  private const val ENCODING_UTF8 = 0x08000100L

  private val cf: NativeLibrary by lazy { NativeLibrary.getInstance("CoreFoundation") }

  // AX* symbols live in HIServices, re-exported through the ApplicationServices umbrella. The
  // spike confirmed the umbrella resolves them directly; keep the sub-framework path as a
  // defensive fallback in case a future macOS reorganizes the re-exports.
  private val ax: NativeLibrary by lazy {
    runCatching { NativeLibrary.getInstance("ApplicationServices") }
      .recoverCatching {
        NativeLibrary.getInstance(
          "/System/Library/Frameworks/ApplicationServices.framework" +
            "/Versions/A/Frameworks/HIServices.framework/Versions/A/HIServices",
        )
      }
      .getOrThrow()
  }

  // --- CoreFoundation functions ---
  private val cfStringCreateWithCString by lazy { cf.getFunction("CFStringCreateWithCString") }
  private val cfStringGetCString by lazy { cf.getFunction("CFStringGetCString") }
  private val cfStringGetLength by lazy { cf.getFunction("CFStringGetLength") }
  private val cfGetTypeID by lazy { cf.getFunction("CFGetTypeID") }
  private val cfArrayGetCount by lazy { cf.getFunction("CFArrayGetCount") }
  private val cfArrayGetValueAtIndex by lazy { cf.getFunction("CFArrayGetValueAtIndex") }
  private val cfRelease by lazy { cf.getFunction("CFRelease") }
  private val cfNumberGetValue by lazy { cf.getFunction("CFNumberGetValue") }
  private val cfBooleanGetValue by lazy { cf.getFunction("CFBooleanGetValue") }
  private val cfEqual by lazy { cf.getFunction("CFEqual") }
  private val cfDictionaryGetValue by lazy { cf.getFunction("CFDictionaryGetValue") }
  private val cfArrayCreate by lazy { cf.getFunction("CFArrayCreate") }
  private val kCFTypeArrayCallBacks by lazy { cf.getGlobalVariableAddress("kCFTypeArrayCallBacks") }
  private val nullTypeId by lazy { cf.getFunction("CFNullGetTypeID").invokeLong(arrayOf<Any>()) }

  // --- CoreGraphics window-list (for whole-screen capture: which apps own on-screen windows) ---
  private val cg: NativeLibrary by lazy {
    runCatching { NativeLibrary.getInstance("CoreGraphics") }
      .recoverCatching { NativeLibrary.getInstance("ApplicationServices") }
      .getOrThrow()
  }
  private val cgWindowListCopyWindowInfo by lazy { cg.getFunction("CGWindowListCopyWindowInfo") }
  // CFStringRef dictionary keys exported by CoreGraphics. getGlobalVariableAddress returns a
  // pointer to the exported variable; getPointer(0) dereferences it to the CFStringRef value.
  private val kCGWindowOwnerPID by lazy { cg.getGlobalVariableAddress("kCGWindowOwnerPID").getPointer(0) }
  private val kCGWindowOwnerName by lazy { cg.getGlobalVariableAddress("kCGWindowOwnerName").getPointer(0) }
  private val kCGWindowLayer by lazy { cg.getGlobalVariableAddress("kCGWindowLayer").getPointer(0) }
  private val kCGWindowBounds by lazy { cg.getGlobalVariableAddress("kCGWindowBounds").getPointer(0) }

  // CGWindowListOption bits: OnScreenOnly (1<<0) | ExcludeDesktopElements (1<<4) = 17.
  private const val K_CG_WINDOW_LIST_ON_SCREEN_EXCL_DESKTOP = 17

  // --- AX functions ---
  private val axIsProcessTrusted by lazy { ax.getFunction("AXIsProcessTrusted") }
  private val axUIElementCreateApplication by lazy { ax.getFunction("AXUIElementCreateApplication") }
  private val axUIElementCopyAttributeValue by lazy { ax.getFunction("AXUIElementCopyAttributeValue") }
  private val axUIElementCopyAttributeNames by lazy { ax.getFunction("AXUIElementCopyAttributeNames") }
  private val axUIElementCopyMultipleAttributeValues by lazy {
    ax.getFunction("AXUIElementCopyMultipleAttributeValues")
  }
  private val axUIElementCopyActionNames by lazy { ax.getFunction("AXUIElementCopyActionNames") }
  private val axUIElementCopyParameterizedAttributeNames by lazy {
    ax.getFunction("AXUIElementCopyParameterizedAttributeNames")
  }
  private val axUIElementGetPid by lazy { ax.getFunction("AXUIElementGetPid") }
  private val axValueGetType by lazy { ax.getFunction("AXValueGetType") }
  private val axValueGetValue by lazy { ax.getFunction("AXValueGetValue") }
  private val axValueCreate by lazy { ax.getFunction("AXValueCreate") }
  private val axUIElementPerformAction by lazy { ax.getFunction("AXUIElementPerformAction") }
  private val axUIElementSetAttributeValue by lazy { ax.getFunction("AXUIElementSetAttributeValue") }
  private val axUIElementCopyElementAtPosition by lazy { ax.getFunction("AXUIElementCopyElementAtPosition") }
  private val axUIElementCreateSystemWide by lazy { ax.getFunction("AXUIElementCreateSystemWide") }

  // --- CFTypeID identities (each *GetTypeID() is a stable per-run constant) ---
  private val stringTypeId by lazy { cf.getFunction("CFStringGetTypeID").invokeLong(arrayOf<Any>()) }
  private val numberTypeId by lazy { cf.getFunction("CFNumberGetTypeID").invokeLong(arrayOf<Any>()) }
  private val boolTypeId by lazy { cf.getFunction("CFBooleanGetTypeID").invokeLong(arrayOf<Any>()) }
  private val arrayTypeId by lazy { cf.getFunction("CFArrayGetTypeID").invokeLong(arrayOf<Any>()) }
  private val axValueTypeId by lazy { axValueGetType.let { ax.getFunction("AXValueGetTypeID").invokeLong(arrayOf<Any>()) } }
  private val axUiElementTypeId by lazy { ax.getFunction("AXUIElementGetTypeID").invokeLong(arrayOf<Any>()) }

  /** `AXIsProcessTrusted()` — whether this process may use the Accessibility APIs. */
  fun isProcessTrusted(): Boolean = (axIsProcessTrusted.invokeInt(arrayOf<Any>()) and 0xFF) != 0

  /** Creates the AX root element for [pid]. Caller owns the result — pass it to [release]. */
  fun createApplication(pid: Int): Pointer =
    axUIElementCreateApplication.invokePointer(arrayOf(pid))

  /** `CFRelease` — safe to call with null. */
  fun release(ref: Pointer?) {
    if (ref != null && ref != Pointer.NULL) cfRelease.invokeVoid(arrayOf(ref))
  }

  /**
   * `CFEqual` — value equality. For two `AXUIElement`s this is identity (same underlying UI
   * element), so it detects when a child in `AXChildren` points back at an ancestor (a cycle).
   */
  fun equal(a: Pointer, b: Pointer): Boolean = cfEqual.invokeInt(arrayOf(a, b)) != 0

  /** One on-screen application: its process id and owner name (e.g. `Calculator`). */
  data class OnScreenApp(val pid: Int, val ownerName: String)

  /**
   * One on-screen window: its owning process and its global bounds in AX points (top-left origin,
   * the same coordinate space as `AXFrame`/`AXPosition` and [TrailblazeNode.Bounds]).
   */
  data class OnScreenWindow(
    val pid: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  )

  /**
   * Every normal on-screen window, **front-to-back** — `CGWindowListCopyWindowInfo` returns the
   * list in z-order, and layer 0 keeps it to real app windows (no Dock, menu-bar extras, or
   * desktop icons). Minimized and hidden windows are absent: the on-screen-only option already
   * excludes them.
   *
   * This is the only z-order signal available to the driver — AX itself exposes no global tree and
   * no stacking information — so it's what occlusion is computed from ([MacOsAxOcclusion]).
   */
  fun onScreenWindows(): List<OnScreenWindow> {
    val array = cgWindowListCopyWindowInfo.invokePointer(
      arrayOf(K_CG_WINDOW_LIST_ON_SCREEN_EXCL_DESKTOP, 0),
    )
    if (array == null || array == Pointer.NULL) return emptyList()
    return try {
      val count = arrayCount(array)
      val windows = mutableListOf<OnScreenWindow>()
      for (i in 0 until count) {
        val dict = arrayValueAt(array, i) // CFDictionaryRef — borrowed (owned by the array)
        val layer = dictInt(dict, kCGWindowLayer) ?: continue
        if (layer != 0) continue
        val pid = dictInt(dict, kCGWindowOwnerPID) ?: continue
        val boundsDict = cfDictionaryGetValue.invokePointer(arrayOf(dict, kCGWindowBounds))
        if (boundsDict == null || boundsDict == Pointer.NULL) continue
        val x = dictInt(boundsDict, cfString("X")) ?: continue
        val y = dictInt(boundsDict, cfString("Y")) ?: continue
        val w = dictInt(boundsDict, cfString("Width")) ?: continue
        val h = dictInt(boundsDict, cfString("Height")) ?: continue
        if (w <= 0 || h <= 0) continue
        windows += OnScreenWindow(pid = pid, left = x, top = y, right = x + w, bottom = y + h)
      }
      windows
    } finally {
      release(array)
    }
  }

  /**
   * The distinct applications that own a normal on-screen window right now, via
   * `CGWindowListCopyWindowInfo` — the global "what's actually on screen" list (all apps, with
   * owner pid + window layer). Filtered to layer 0 (normal app windows; excludes the Dock,
   * menu-bar overlays, desktop icons). This is how a whole-screen AX capture discovers which apps
   * to walk (AX itself has no global tree — see [MacOsAxScreenWalker]). Ordered by first
   * appearance (front-to-back-ish per CGWindowList's z-order).
   */
  fun onScreenAppPids(): List<OnScreenApp> {
    val array = cgWindowListCopyWindowInfo.invokePointer(
      arrayOf(K_CG_WINDOW_LIST_ON_SCREEN_EXCL_DESKTOP, 0),
    )
    if (array == null || array == Pointer.NULL) return emptyList()
    return try {
      val count = arrayCount(array)
      val seen = LinkedHashMap<Int, String>()
      for (i in 0 until count) {
        val dict = arrayValueAt(array, i) // CFDictionaryRef — borrowed (owned by the array)
        val layer = dictInt(dict, kCGWindowLayer) ?: continue
        if (layer != 0) continue
        val pid = dictInt(dict, kCGWindowOwnerPID) ?: continue
        val name = dictString(dict, kCGWindowOwnerName) ?: ""
        seen.putIfAbsent(pid, name)
      }
      seen.entries.map { OnScreenApp(it.key, it.value) }
    } finally {
      release(array)
    }
  }

  private fun dictInt(dict: Pointer, key: Pointer): Int? {
    val value = cfDictionaryGetValue.invokePointer(arrayOf(dict, key))
    if (value == null || value == Pointer.NULL) return null
    if (cfGetTypeID.invokeLong(arrayOf(value)) != numberTypeId) return null
    return cfNumberToDouble(value).toInt()
  }

  private fun dictString(dict: Pointer, key: Pointer): String? {
    val value = cfDictionaryGetValue.invokePointer(arrayOf(dict, key))
    if (value == null || value == Pointer.NULL) return null
    if (cfGetTypeID.invokeLong(arrayOf(value)) != stringTypeId) return null
    return cfStringToKotlin(value)
  }

  /**
   * Cheap check of how many windows app [pid] currently exposes (`AXWindows` count) — used to
   * wait for a freshly-launched/activated app to actually create its window before driving it,
   * without paying for a full tree walk.
   */
  fun windowCount(pid: Int): Int {
    val app = createApplication(pid)
    return try {
      val windows = copyAttributeValue(app, "AXWindows") ?: return 0
      try {
        arrayCount(windows).toInt()
      } finally {
        release(windows)
      }
    } finally {
      release(app)
    }
  }

  /** The owning process id of [element] via `AXUIElementGetPid`, or null on error. */
  fun pidOf(element: Pointer): Int? {
    val out = IntByReference()
    val err = axUIElementGetPid.invokeInt(arrayOf(element, out))
    return if (err == 0) out.value else null
  }

  /** All attribute names (`AXUIElementCopyAttributeNames`). */
  fun attributeNames(element: Pointer): List<String> = copyStringArray(axUIElementCopyAttributeNames, element)

  /** All action names (`AXUIElementCopyActionNames`). */
  fun actionNames(element: Pointer): List<String> = copyStringArray(axUIElementCopyActionNames, element)

  /** All parameterized-attribute names (`AXUIElementCopyParameterizedAttributeNames`). */
  fun parameterizedAttributeNames(element: Pointer): List<String> =
    copyStringArray(axUIElementCopyParameterizedAttributeNames, element)

  /**
   * `AXUIElementCopyAttributeValue`. Returns a reference the **caller owns** (must [release]),
   * or null when the attribute is absent / errored / null.
   */
  fun copyAttributeValue(element: Pointer, name: String): Pointer? {
    val nameRef = cfString(name)
    return try {
      val out = PointerByReference()
      val err = axUIElementCopyAttributeValue.invokeInt(arrayOf(element, nameRef, out))
      if (err == 0 && out.value != null && out.value != Pointer.NULL) out.value else null
    } finally {
      release(nameRef)
    }
  }

  /**
   * Reads every attribute in [names] off [element] in **one** cross-process call
   * (`AXUIElementCopyMultipleAttributeValues`), returning name → decoded value.
   *
   * Why this exists: the walk previously read attributes one at a time, so each element cost
   * `1 + N` round trips (names, then a separate `AXUIElementCopyAttributeValue` per attribute) —
   * ~30 for a typical element. Across a whole-desktop capture of ~5,400 elements that's well over
   * 150,000 cross-process calls and it dominated everything: ~25 seconds per capture, paid again
   * on every selector poll, which is what forced 90-second timeouts into trails and made a
   * post-tool snapshot expensive enough to blow the call-handler timeout. Batching is the same
   * data with an order of magnitude fewer trips — no fidelity is given up, every attribute is
   * still captured verbatim.
   *
   * Errors come back **in-band**: with options=0 the call succeeds even when individual attributes
   * fail, and each failed slot holds an `AXValueRef` of type [K_AX_VALUE_AX_ERROR] rather than
   * being omitted. Those must be dropped, or an element that merely refuses one attribute would
   * carry a garbage entry for it. Slots may also legitimately hold `kCFNull`.
   *
   * Returns null if the batch call fails outright, so the caller can fall back to reading
   * attributes individually rather than silently capturing an element with no attributes.
   */
  fun copyMultipleAttributeValues(element: Pointer, names: List<String>): Map<String, MacOsAxAttributeValue>? {
    if (names.isEmpty()) return emptyMap()
    val nameRefs = names.map { cfString(it) }
    val namesArray = createCfArray(nameRefs) ?: run {
      nameRefs.forEach { release(it) }
      return null
    }
    return try {
      val out = PointerByReference()
      val err = axUIElementCopyMultipleAttributeValues.invokeInt(arrayOf(element, namesArray, 0, out))
      val values = out.value
      if (err != 0 || values == null || values == Pointer.NULL) return null
      try {
        val count = arrayCount(values).toInt()
        if (count != names.size) return null
        val decoded = LinkedHashMap<String, MacOsAxAttributeValue>(count)
        for (i in names.indices) {
          val value = arrayValueAt(values, i.toLong())
          if (value == Pointer.NULL) continue
          if (isNull(value) || isAxError(value)) continue
          decoded[names[i]] = decodeValue(value)
        }
        decoded
      } finally {
        release(values)
      }
    } finally {
      release(namesArray)
      nameRefs.forEach { release(it) }
    }
  }

  /** True for the `kAXValueAXErrorType` placeholder a batch read leaves in a failed slot. */
  private fun isAxError(ref: Pointer): Boolean =
    cfGetTypeID.invokeLong(arrayOf(ref)) == axValueTypeId &&
      axValueGetType.invokeInt(arrayOf(ref)) == K_AX_VALUE_AX_ERROR

  private fun isNull(ref: Pointer): Boolean = cfGetTypeID.invokeLong(arrayOf(ref)) == nullTypeId

  /** Builds a CFArray over [items] (retaining CF callbacks). Caller owns the result — [release] it. */
  private fun createCfArray(items: List<Pointer>): Pointer? {
    val buffer = Memory((items.size.toLong().coerceAtLeast(1)) * Native.POINTER_SIZE)
    items.forEachIndexed { i, p -> buffer.setPointer((i.toLong() * Native.POINTER_SIZE), p) }
    val array = cfArrayCreate.invokePointer(
      arrayOf(Pointer.NULL, buffer, items.size.toLong(), kCFTypeArrayCallBacks),
    )
    return array?.takeIf { it != Pointer.NULL }
  }

  /**
   * Decodes a CoreFoundation / `AXValueRef` value into the serializable [MacOsAxAttributeValue]
   * union. Does NOT release [ref] — the caller owns it. Nested `AXUIElement` references are
   * summarized (not recursively expanded) to avoid cycles; array elements are decoded but their
   * borrowed pointers are never released here.
   */
  fun decodeValue(ref: Pointer): MacOsAxAttributeValue {
    return when (cfGetTypeID.invokeLong(arrayOf(ref))) {
      stringTypeId -> MacOsAxAttributeValue.Str(cfStringToKotlin(ref))
      numberTypeId -> MacOsAxAttributeValue.Num(cfNumberToDouble(ref))
      boolTypeId -> MacOsAxAttributeValue.Bool(cfBooleanGetValue.invokeInt(arrayOf(ref)) != 0)
      arrayTypeId -> MacOsAxAttributeValue.Arr(decodeArray(ref))
      axValueTypeId -> decodeAxValue(ref)
      axUiElementTypeId -> decodeElementRef(ref)
      else -> MacOsAxAttributeValue.Unknown(cfGetTypeID.invokeLong(arrayOf(ref)))
    }
  }

  // --- Actions (mutating) ---

  /** The shared system-wide AX element. Caller owns the result — pass it to [release]. */
  fun createSystemWide(): Pointer = axUIElementCreateSystemWide.invokePointer(arrayOf<Any>())

  /**
   * Hit-tests the live AX hierarchy at global screen point ([x], [y]) and returns the frontmost
   * element there (`AXUIElementCopyElementAtPosition`), or null. Caller owns the result — pass it
   * to [release]. [systemWide] should come from [createSystemWide].
   */
  fun copyElementAtPosition(systemWide: Pointer, x: Float, y: Float): Pointer? {
    val out = PointerByReference()
    val err = axUIElementCopyElementAtPosition.invokeInt(arrayOf(systemWide, x, y, out))
    return if (err == 0 && out.value != null && out.value != Pointer.NULL) out.value else null
  }

  /** Performs a native AX action (e.g. `"AXPress"`) on [element]. Returns true on success (err 0). */
  fun performAction(element: Pointer, action: String): Boolean {
    val actionRef = cfString(action)
    return try {
      axUIElementPerformAction.invokeInt(arrayOf(element, actionRef)) == 0
    } finally {
      release(actionRef)
    }
  }

  /** Sets a string-valued attribute (e.g. `AXValue` on a text field). Returns true on success. */
  fun setStringAttribute(element: Pointer, name: String, value: String): Boolean {
    val nameRef = cfString(name)
    val valueRef = cfString(value)
    return try {
      axUIElementSetAttributeValue.invokeInt(arrayOf(element, nameRef, valueRef)) == 0
    } finally {
      release(nameRef)
      release(valueRef)
    }
  }

  /** Sets a boolean-valued attribute (e.g. `AXFocused = true`). Returns true on success. */
  /**
   * Moves and resizes [window] — the mechanism a trail uses to normalize an app's geometry before
   * it acts, so a recording taken on one window layout replays faithfully on another.
   *
   * `AXPosition` and `AXSize` are *settable* on a standard window, but only through an `AXValue`
   * box: you cannot hand the API two raw numbers, you hand it a CGPoint or a CGSize wrapped in an
   * AXValueRef ([axValueCreate]). Both are two 64-bit doubles, which is why the buffer is 16 bytes.
   *
   * Position is set BEFORE size deliberately: some apps clamp a resize against the screen the
   * window is currently on, so resizing first and moving second can silently give you a different
   * size than the one you asked for.
   */
  fun setWindowBounds(window: Pointer, x: Int, y: Int, width: Int, height: Int): List<Int>? {
    // Size first, then position — and then CHECK, because the setter's return code is not evidence.
    //
    // `AXUIElementSetAttributeValue` reports success when the element accepts the message, not when
    // the app honors it (the same lie `AXValue` writes tell on Safari's address bar). Measured: the
    // size took and the position silently did not, while the call returned 0 for both, so the tool
    // cheerfully reported a window it had not moved. Resizing can also shift a window's origin, so
    // position must be applied last or it gets undone by the resize that follows it.
    setStructAttribute(window, "AXSize", K_AX_VALUE_CG_SIZE, width.toDouble(), height.toDouble())

    // Re-apply the origin until it sticks. Some apps re-lay out ASYNCHRONOUSLY after a resize and
    // move themselves afterwards, so a position set in the same breath is overwritten a moment
    // later — Terminal cascades its window down-and-right on every resize, and each attempt to place
    // it just chased it further across the screen. Setting the origin, letting the app settle, and
    // checking is the only way to actually land it.
    var frame: List<Int>? = null
    repeat(POSITION_ATTEMPTS) {
      setStructAttribute(window, "AXPosition", K_AX_VALUE_CG_POINT, x.toDouble(), y.toDouble())
      Thread.sleep(POSITION_SETTLE_MS)
      frame = readWindowFrame(window)
      val current = frame ?: return@repeat
      if (closeEnough(current[0], x) && closeEnough(current[1], y)) return current
    }
    // Return the ACTUAL frame either way — the setter's return code is not evidence, and "it
    // refused" and "it did what it could" are different answers the caller must tell apart.
    return frame
  }

  private const val POSITION_ATTEMPTS = 4
  private const val POSITION_SETTLE_MS = 150L

  /**
   * True when the window ended up where it was asked to be. Judged on the ORIGIN only, deliberately.
   *
   * The size is allowed to differ, because apps clamp it and clamping is not failure: Calculator has
   * a minimum size and answered a request for 240x400 with 230x408. That's still perfectly
   * deterministic — it will clamp to the same 230x408 on every machine, every run — so a trail
   * normalized this way still replays faithfully. The ORIGIN is what a coordinate click actually
   * depends on, and an origin that didn't take means every later click lands somewhere else.
   */
  fun windowMovedTo(frame: List<Int>, x: Int, y: Int): Boolean =
    closeEnough(frame[0], x) && closeEnough(frame[1], y)

  /** x, y, width, height of [window] in points, or null if it reports no frame. */
  private fun readWindowFrame(window: Pointer): List<Int>? {
    val position = copyAttributeValue(window, "AXPosition") ?: return null
    val size = copyAttributeValue(window, "AXSize") ?: run { release(position); return null }
    return try {
      val point = decodeValue(position) as? MacOsAxAttributeValue.Point ?: return null
      val dimensions = decodeValue(size) as? MacOsAxAttributeValue.Size ?: return null
      listOf(point.x.toInt(), point.y.toInt(), dimensions.width.toInt(), dimensions.height.toInt())
    } finally {
      release(position)
      release(size)
    }
  }

  /**
   * Windows land a pixel or two off what was asked for — a window rounds to its content grid, a
   * terminal snaps to whole character cells. Demanding exactness would fail a window that did
   * exactly what it was told; the tolerance is far tighter than anything that could move a click
   * onto the wrong control.
   */
  private fun closeEnough(actual: Int, requested: Int): Boolean =
    kotlin.math.abs(actual - requested) <= WINDOW_BOUNDS_TOLERANCE_PX

  private const val WINDOW_BOUNDS_TOLERANCE_PX = 4

  private fun setStructAttribute(
    element: Pointer,
    name: String,
    axValueType: Int,
    first: Double,
    second: Double,
  ): Boolean {
    val buffer = Memory(16)
    buffer.setDouble(0, first)
    buffer.setDouble(8, second)
    val valueRef = axValueCreate.invokePointer(arrayOf(axValueType, buffer))
    if (valueRef == null || valueRef == Pointer.NULL) return false
    val nameRef = cfString(name)
    return try {
      axUIElementSetAttributeValue.invokeInt(arrayOf(element, nameRef, valueRef)) == 0
    } finally {
      release(nameRef)
      release(valueRef)
    }
  }

  /**
   * The app's main window — what "the window" means for a geometry change. Prefers the focused one
   * (the window the user is actually in), falling back to the first. Caller owns the result.
   */
  fun mainWindow(appElement: Pointer): Pointer? {
    copyAttributeValue(appElement, "AXFocusedWindow")?.let { return it }
    val windows = copyAttributeValue(appElement, "AXWindows") ?: return null
    return try {
      if (arrayCount(windows) == 0L) null else copyAttributeValue(appElement, "AXMainWindow")
    } finally {
      release(windows)
    }
  }

  fun setBooleanAttribute(element: Pointer, name: String, value: Boolean): Boolean {
    val nameRef = cfString(name)
    val boolRef = if (value) cfBooleanTrue() else cfBooleanFalse()
    return try {
      axUIElementSetAttributeValue.invokeInt(arrayOf(element, nameRef, boolRef)) == 0
    } finally {
      release(nameRef)
      // kCFBooleanTrue/False are shared singletons — must NOT be released.
    }
  }

  /**
   * Asks a browser (or any WebKit/Chromium/AppKit host) [appElement] to build its full
   * accessibility tree, including **web page content**. Browsers only expose the web AX tree when
   * they detect an assistive technology, so without this a captured Safari/Chrome tree contains
   * only the app chrome (toolbar, tabs) and no `AXWebArea` — page text and links are invisible.
   *
   * Sets both opt-in attributes so it works across engines:
   *  - `AXManualAccessibility` — Chromium (Chrome, Electron, CEF).
   *  - `AXEnhancedUserInterface` — AppKit / WebKit (Safari).
   *
   * Best-effort: an app that doesn't support an attribute simply returns an error we ignore. The
   * tree builds asynchronously after this returns, so the first capture may still be sparse — the
   * next one (e.g. an assertion's poll) sees the populated web content.
   */
  fun enableEnhancedWebAccessibility(appElement: Pointer) {
    runCatching { setBooleanAttribute(appElement, "AXManualAccessibility", true) }
    runCatching { setBooleanAttribute(appElement, "AXEnhancedUserInterface", true) }
  }

  private fun cfBooleanTrue(): Pointer = cf.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)
  private fun cfBooleanFalse(): Pointer = cf.getGlobalVariableAddress("kCFBooleanFalse").getPointer(0)

  /** Count of a `CFArrayRef`. */
  fun arrayCount(array: Pointer): Long = cfArrayGetCount.invokeLong(arrayOf(array))

  /** Borrowed element at [index] of a `CFArrayRef` — do NOT release the result. */
  fun arrayValueAt(array: Pointer, index: Long): Pointer =
    cfArrayGetValueAtIndex.invokePointer(arrayOf(array, index))

  // --- internals ---

  private fun decodeArray(array: Pointer): List<MacOsAxAttributeValue> {
    val count = arrayCount(array)
    return (0 until count).map { i -> decodeValue(arrayValueAt(array, i)) }
  }

  private fun decodeAxValue(ref: Pointer): MacOsAxAttributeValue {
    return when (axValueGetType.invokeInt(arrayOf(ref))) {
      K_AX_VALUE_CG_POINT -> readStruct(ref, K_AX_VALUE_CG_POINT, 16) { m ->
        MacOsAxAttributeValue.Point(m.getDouble(0), m.getDouble(8))
      }
      K_AX_VALUE_CG_SIZE -> readStruct(ref, K_AX_VALUE_CG_SIZE, 16) { m ->
        MacOsAxAttributeValue.Size(m.getDouble(0), m.getDouble(8))
      }
      K_AX_VALUE_CG_RECT -> readStruct(ref, K_AX_VALUE_CG_RECT, 32) { m ->
        MacOsAxAttributeValue.Rect(
          origin = MacOsAxAttributeValue.Point(m.getDouble(0), m.getDouble(8)),
          size = MacOsAxAttributeValue.Size(m.getDouble(16), m.getDouble(24)),
        )
      }
      K_AX_VALUE_CF_RANGE -> readStruct(ref, K_AX_VALUE_CF_RANGE, 16) { m ->
        MacOsAxAttributeValue.Range(m.getLong(0), m.getLong(8))
      }
      else -> MacOsAxAttributeValue.Unknown(axValueTypeId)
    }
  }

  private inline fun readStruct(
    ref: Pointer,
    axValueType: Int,
    byteSize: Long,
    build: (Memory) -> MacOsAxAttributeValue,
  ): MacOsAxAttributeValue {
    val mem = Memory(byteSize)
    val ok = axValueGetValue.invokeInt(arrayOf(ref, axValueType, mem)) != 0
    return if (ok) build(mem) else MacOsAxAttributeValue.Unknown(axValueTypeId)
  }

  /** Summarizes a nested `AXUIElement` by identity — reads only 3 scalar attrs, no recursion. */
  private fun decodeElementRef(element: Pointer): MacOsAxAttributeValue.ElementRef =
    MacOsAxAttributeValue.ElementRef(
      role = stringAttributeOf(element, "AXRole"),
      identifier = stringAttributeOf(element, "AXIdentifier"),
      title = stringAttributeOf(element, "AXTitle"),
    )

  /** Reads a single string attribute (owned copy → Kotlin string → released). */
  private fun stringAttributeOf(element: Pointer, name: String): String? {
    val ref = copyAttributeValue(element, name) ?: return null
    return try {
      if (cfGetTypeID.invokeLong(arrayOf(ref)) == stringTypeId) cfStringToKotlin(ref).ifBlank { null } else null
    } finally {
      release(ref)
    }
  }

  private fun cfNumberToDouble(ref: Pointer): Double {
    val mem = Memory(8)
    cfNumberGetValue.invokeInt(arrayOf(ref, K_CF_NUMBER_DOUBLE_TYPE, mem))
    return mem.getDouble(0)
  }

  /** Runs a `Copy*Names`-shaped function (element, out) and marshals the resulting CFArray<CFString>. */
  private fun copyStringArray(function: com.sun.jna.Function, element: Pointer): List<String> {
    val out = PointerByReference()
    val err = function.invokeInt(arrayOf(element, out))
    val array = out.value
    if (err != 0 || array == null || array == Pointer.NULL) return emptyList()
    return try {
      val count = arrayCount(array)
      (0 until count).map { i -> cfStringToKotlin(arrayValueAt(array, i)) }
    } finally {
      release(array)
    }
  }

  private fun cfString(value: String): Pointer =
    cfStringCreateWithCString.invokePointer(arrayOf(Pointer.NULL, value, ENCODING_UTF8))

  private fun cfStringToKotlin(ref: Pointer): String {
    val len = cfStringGetLength.invokeLong(arrayOf(ref))
    val bufSize = len * 4 + 1 // worst-case UTF-8 is 4 bytes/char, +1 for NUL
    val buf = Memory(bufSize)
    val ok = cfStringGetCString.invokeInt(arrayOf(ref, buf, bufSize, ENCODING_UTF8)) != 0
    return if (ok) buf.getString(0) else ""
  }
}

package xyz.block.trailblaze.host.macosax

import com.sun.jna.Memory
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

  // CGWindowListOption bits: OnScreenOnly (1<<0) | ExcludeDesktopElements (1<<4) = 17.
  private const val K_CG_WINDOW_LIST_ON_SCREEN_EXCL_DESKTOP = 17

  // --- AX functions ---
  private val axIsProcessTrusted by lazy { ax.getFunction("AXIsProcessTrusted") }
  private val axUIElementCreateApplication by lazy { ax.getFunction("AXUIElementCreateApplication") }
  private val axUIElementCopyAttributeValue by lazy { ax.getFunction("AXUIElementCopyAttributeValue") }
  private val axUIElementCopyAttributeNames by lazy { ax.getFunction("AXUIElementCopyAttributeNames") }
  private val axUIElementCopyActionNames by lazy { ax.getFunction("AXUIElementCopyActionNames") }
  private val axUIElementCopyParameterizedAttributeNames by lazy {
    ax.getFunction("AXUIElementCopyParameterizedAttributeNames")
  }
  private val axUIElementGetPid by lazy { ax.getFunction("AXUIElementGetPid") }
  private val axValueGetType by lazy { ax.getFunction("AXValueGetType") }
  private val axValueGetValue by lazy { ax.getFunction("AXValueGetValue") }
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

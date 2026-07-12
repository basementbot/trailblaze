package xyz.block.trailblaze.host.macosax

import com.sun.jna.Pointer

/**
 * Drives an app's **menu bar** — File, Edit, View, Format, Window — on demand.
 *
 * This is a large hole in what the driver could otherwise reach. A great deal of macOS exists only
 * in a menu: "Export as PDF…", "Show Hidden Files", "Merge All Windows", anything without a
 * keyboard shortcut. The tree walk deliberately does NOT descend into menus
 * ([MacOsAxTreeWalker.ROLES_TO_NOT_RECURSE]) because an app's menu bar fans out into hundreds of
 * lazily-populated items and walking it on every capture would cost more than the rest of the tree
 * combined — so menus are unreachable by selector, by design.
 *
 * The answer is to visit them only when asked. Menu items ARE ordinary AX elements: they carry
 * titles and advertise `AXPress`. Walking one path down from the menu bar costs a handful of AX
 * calls rather than thousands, and the submenu only populates once its parent is pressed — which is
 * why the intermediate presses are real presses with a settle, not a shortcut to the leaf.
 */
object MacOsAxMenu {

  /** How long to let a submenu populate after pressing its parent. */
  private const val SUBMENU_SETTLE_MS = 250L

  /**
   * Presses the menu item named by [path] — e.g. `["File", "New Window"]` — in the app owning
   * [pid]. Returns null on success, or a human-readable reason for the caller to surface.
   *
   * The failure message names what it DID find at the level that failed. A menu path is guesswork
   * from the outside ("is it 'New Window' or 'New window'?"), and "no such item" without the list
   * of real ones leaves the caller no way forward but to guess again.
   */
  fun clickPath(pid: Int, path: List<String>): String? {
    if (path.isEmpty()) return "Menu path is empty — give at least a top-level menu, e.g. [\"File\"]."
    // Every `AXChildren` array we open is held open until the walk is done, and released together
    // at the end. The child element pointers are BORROWED — owned by the array they came out of
    // (see the ownership contract on MacOsAxNative) — so releasing an array while still holding a
    // pointer into it leaves that pointer dangling. Doing exactly that crashed the whole daemon on
    // the first real menu click: not an exception, a use-after-free that took the JVM with it.
    val arena = mutableListOf<Pointer>()
    fun keep(ref: Pointer): Pointer = ref.also { arena += it }

    try {
      val app = keep(MacOsAxNative.createApplication(pid))
      val menuBar = MacOsAxNative.copyAttributeValue(app, "AXMenuBar")?.let(::keep)
        ?: return "This app exposes no menu bar to Accessibility."

      var container: Pointer = menuBar
      path.forEachIndexed { index, name ->
        val items = childrenOf(container, ::keep)
        val match = items.firstOrNull { titleOf(it).equals(name, ignoreCase = true) }
          ?: return "No menu item '$name'${pathSoFar(path, index)}. Available: " +
            items.mapNotNull { titleOf(it).takeIf(String::isNotBlank) }.joinToString(", ")

        if (!MacOsAxNative.performAction(match, "AXPress")) {
          return "Menu item '$name' would not respond to AXPress."
        }
        if (index == path.lastIndex) return null

        // Opening a menu populates its contents asynchronously, and they arrive under an AXMenu
        // child rather than directly under the bar item.
        Thread.sleep(SUBMENU_SETTLE_MS)
        container = childrenOf(match, ::keep).firstOrNull { roleOf(it) == "AXMenu" }
          ?: return "Menu '$name' opened but exposes no submenu."
      }
      return null
    } finally {
      arena.forEach { MacOsAxNative.release(it) }
    }
  }

  /**
   * Clicks [title] in whatever menu is currently OPEN in the app owning [pid] — the item a
   * right-click just put on screen. Returns null on success, or a reason naming the items it found.
   *
   * Open menus are not in the snapshot and cannot be, so this is the only way to act on one. See
   * [MacOsAxTreeWalker.ROLES_TO_NOT_RECURSE]: letting the tree walk descend into menus took a
   * whole-desktop capture from 7s to 170s, because apps keep dormant menus laid out and enormous
   * and there's no cheap way to tell those from the one the user is looking at. On demand, the same
   * lookup costs a handful of AX calls.
   *
   * "Open" is judged by on-screen bounds, which is unreliable when *walking* every app (dormant
   * menus have bounds too) but is fine here: we look only at the app that owns the click, and only
   * for a menu holding the item asked for.
   */
  fun clickOpenMenuItem(pid: Int, title: String, nearX: Int, nearY: Int): String? {
    val arena = mutableListOf<Pointer>()
    fun keep(ref: Pointer): Pointer = ref.also { arena += it }
    try {
      val menu = openMenuNear(pid, nearX, nearY, ::keep)
        ?: return "No open menu found near ($nearX, $nearY). Right-click first, and pass the same " +
          "point you right-clicked at."

      val items = childrenOf(menu, ::keep)
      val match = items.firstOrNull { titleOf(it).equals(title, ignoreCase = true) }
        ?: return "No item '$title' in the open menu. Available: " +
          items.mapNotNull { titleOf(it).takeIf(String::isNotBlank) }.joinToString(", ")

      return if (MacOsAxNative.performAction(match, "AXPress")) {
        null
      } else {
        "Menu item '$title' would not respond to AXPress."
      }
    } finally {
      arena.forEach { MacOsAxNative.release(it) }
    }
  }

  /**
   * Finds the open menu by **hit-testing the screen** just below-right of where the click landed,
   * then walking up to the enclosing `AXMenu`.
   *
   * A context menu is not a child of the application element — looking for it there finds nothing,
   * which is what "No menu is open in this app" meant on the first attempt. But it is unmistakably
   * *on screen*, and macOS's own hit-test (`AXUIElementCopyElementAtPosition`, the same call the
   * driver uses for taps) honours z-order and hands back whatever is topmost at a point. A menu
   * opens with its first item under the cursor, so probing a few points around the click reaches an
   * `AXMenuItem`, whose `AXParent` is the menu.
   *
   * Probes rather than a single point because a menu that would run off the bottom or right of the
   * screen flips to open the other way.
   */
  private fun openMenuNear(pid: Int, x: Int, y: Int, keep: (Pointer) -> Pointer): Pointer? {
    val systemWide = keep(MacOsAxNative.createSystemWide())
    val probes = listOf(
      x + 12 to y + 12, // menu opens down-right of the cursor (the common case)
      x + 12 to y - 12, // flipped up, near the bottom of the screen
      x - 12 to y + 12, // flipped left, near the right edge
      x - 12 to y - 12,
    )
    for ((px, py) in probes) {
      val hit = MacOsAxNative.copyElementAtPosition(systemWide, px.toFloat(), py.toFloat())
        ?.let(keep) ?: continue
      ascendToMenu(hit, keep)?.let { return it }
    }
    return null
  }

  /** Walks `AXParent` up from [element] until an `AXMenu` is found (a menu is shallow). */
  private fun ascendToMenu(element: Pointer, keep: (Pointer) -> Pointer): Pointer? {
    var current: Pointer? = element
    var hops = 0
    while (current != null && hops < MAX_MENU_ASCENT) {
      if (roleOf(current) == "AXMenu") return current
      current = MacOsAxNative.copyAttributeValue(current, "AXParent")?.let(keep)
      hops++
    }
    return null
  }

  /** A menu item sits a couple of levels under its menu; anything deeper isn't one. */
  private const val MAX_MENU_ASCENT = 5

  /** Top-level menu titles for [pid] — what a caller can choose from. */
  fun topLevelMenus(pid: Int): List<String> {
    val arena = mutableListOf<Pointer>()
    fun keep(ref: Pointer): Pointer = ref.also { arena += it }
    try {
      val app = keep(MacOsAxNative.createApplication(pid))
      val menuBar = MacOsAxNative.copyAttributeValue(app, "AXMenuBar")?.let(::keep) ?: return emptyList()
      return childrenOf(menuBar, ::keep).map { titleOf(it) }.filter { it.isNotBlank() }
    } finally {
      arena.forEach { MacOsAxNative.release(it) }
    }
  }

  private fun pathSoFar(path: List<String>, index: Int): String =
    if (index == 0) " in the menu bar" else " under ${path.take(index).joinToString(" > ")}"

  /**
   * The element's `AXChildren`. The returned pointers are **borrowed** from the array, so the array
   * itself is handed to [keep] and stays alive until the caller is finished with them — releasing
   * it here would invalidate every pointer this returns.
   */
  private fun childrenOf(element: Pointer, keep: (Pointer) -> Pointer): List<Pointer> {
    val array = MacOsAxNative.copyAttributeValue(element, "AXChildren")?.let(keep) ?: return emptyList()
    return (0 until MacOsAxNative.arrayCount(array)).map { MacOsAxNative.arrayValueAt(array, it) }
  }

  private fun titleOf(element: Pointer): String = stringAttr(element, "AXTitle")

  private fun roleOf(element: Pointer): String = stringAttr(element, "AXRole")

  private fun stringAttr(element: Pointer, name: String): String {
    val ref = MacOsAxNative.copyAttributeValue(element, name) ?: return ""
    return try {
      (MacOsAxNative.decodeValue(ref) as? xyz.block.trailblaze.api.MacOsAxAttributeValue.Str)?.value ?: ""
    } finally {
      MacOsAxNative.release(ref)
    }
  }
}

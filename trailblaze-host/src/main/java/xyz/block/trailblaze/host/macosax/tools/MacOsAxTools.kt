package xyz.block.trailblaze.host.macosax.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import maestro.KeyCode
import xyz.block.trailblaze.host.macosax.MacOsAxEventSynthesizer
import xyz.block.trailblaze.host.macosax.MacOsAxMenu
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import maestro.orchestra.Command
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.TapOnPointCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.host.macosax.MacOsAxAppResolver
import xyz.block.trailblaze.host.macosax.MacOsAxNative
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * `macos_*` tool family — the desktop macOS Accessibility (AX) driver's own tool namespace,
 * parallel to the Compose desktop driver's `compose_*` tools. These are declared by the
 * `macos_core` toolset (drivers: `macos-ax`) and, unlike the generic cross-platform tools,
 * are named + scoped for the macOS driver so trails read as macOS-native.
 *
 * The primitive tools ([MacOsLaunchAppTrailblazeTool], [MacOsInputTextTrailblazeTool],
 * [MacOsPressKeyTrailblazeTool]) lower to Maestro commands the macOS converter handles;
 * the selector tools call the agent's native node-selector dispatch; and
 * [MacOsOpenUrlTrailblazeTool] orchestrates the driver primitives directly (it lives host-side,
 * so it can drive [MacOsAxAppResolver] / [MacOsAxEventSynthesizer] without a Maestro round-trip).
 */

@Serializable
@TrailblazeToolClass("macos_launchApp")
@LLMDescription("Launch or focus a macOS app by its bundle id (e.g. com.apple.Safari).")
data class MacOsLaunchAppTrailblazeTool(
  @param:LLMDescription("The target app's bundle id, e.g. com.apple.Safari.")
  val bundleId: String,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> =
    listOf(LaunchAppCommand(appId = bundleId))
}

@Serializable
@TrailblazeToolClass("macos_inputText")
@LLMDescription("Type text into the currently focused field. Tap/focus the field first.")
data class MacOsInputTextTrailblazeTool(
  @param:LLMDescription("The text to type.")
  val text: String,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> =
    listOf(InputTextCommand(memory.interpolateVariables(text)))
}

/** Special keys the macOS driver can synthesize. Mirrors the subset with a real desktop analog. */
@Serializable
enum class MacOsKey {
  ENTER,
  TAB,
  ESCAPE,
  HOME,
  BACKSPACE,
  ;

  fun toMaestro(): KeyCode = when (this) {
    ENTER -> KeyCode.ENTER
    TAB -> KeyCode.TAB
    ESCAPE -> KeyCode.ESCAPE
    HOME -> KeyCode.HOME
    BACKSPACE -> KeyCode.BACKSPACE
  }
}

@Serializable
@TrailblazeToolClass("macos_pressKey")
@LLMDescription("Press a special key: ENTER (submit), TAB (next field), ESCAPE, HOME, or BACKSPACE.")
data class MacOsPressKeyTrailblazeTool(
  @param:LLMDescription("Which key to press.")
  val key: MacOsKey,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> =
    listOf(PressKeyCommand(code = key.toMaestro()))
}

@Serializable
@TrailblazeToolClass("macos_tapOnElement")
@LLMDescription("Tap (AXPress) an on-screen element matched by a macOsAx node selector.")
data class MacOsTapOnElementTrailblazeTool(
  val nodeSelector: TrailblazeNodeSelector,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val agent = toolExecutionContext.maestroTrailblazeAgent
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "macos_tapOnElement requires the macOS AX agent, which was not available.",
      )
    return agent.executeNodeSelectorTap(
      nodeSelector = nodeSelector,
      longPress = false,
      traceId = toolExecutionContext.traceId,
    ) ?: TrailblazeToolResult.Error.ExceptionThrown(
      "No element matched selector ${nodeSelector.description()}.",
    )
  }
}

@Serializable
@TrailblazeToolClass("macos_tapPoint")
@LLMDescription(
  "Click at an exact screen coordinate, in points from the top-left of the main display. " +
    "This is the fallback for controls that Accessibility cannot see: apps that custom-draw " +
    "their buttons (wxWidgets, Electron canvases, game UIs) and macOS's own system permission " +
    "dialogs expose no clickable element to select, so `macos_tapOnElement` has nothing to match. " +
    "Prefer macos_tapOnElement whenever the element IS in the tree — a selector survives the " +
    "window moving or the layout changing, and a coordinate does not. Read the coordinate off a " +
    "screenshot, and remember AX points are half of Retina screenshot pixels.",
)
data class MacOsTapPointTrailblazeTool(
  @param:LLMDescription("X coordinate in points from the left edge of the main display.")
  val x: Int,
  @param:LLMDescription("Y coordinate in points from the top edge of the main display.")
  val y: Int,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> =
    listOf(TapOnPointCommand(x = x, y = y))
}

/** Modifier keys that can be held for a [MacOsPressKeyComboTrailblazeTool] chord. */
@Serializable
enum class MacOsModifier {
  COMMAND,
  SHIFT,
  OPTION,
  CONTROL,
  ;

  fun toFlag(): Long = when (this) {
    COMMAND -> MacOsAxEventSynthesizer.FLAG_COMMAND
    SHIFT -> MacOsAxEventSynthesizer.FLAG_SHIFT
    OPTION -> MacOsAxEventSynthesizer.FLAG_OPTION
    CONTROL -> MacOsAxEventSynthesizer.FLAG_CONTROL
  }
}

@Serializable
@TrailblazeToolClass("macos_pressKeyCombo")
@LLMDescription(
  "Press a keyboard shortcut — a key plus held modifiers — on whatever app is frontmost. " +
    "This is how you reach the enormous amount of macOS that has no clickable control at all: " +
    "close a window (COMMAND+W), new tab (COMMAND+T), switch app (COMMAND+TAB), quit " +
    "(COMMAND+Q), save (COMMAND+S), select all (COMMAND+A). " +
    "`key` is a key NAME, not a character: a letter A-Z, a digit, or one of TAB, SPACE, RETURN, " +
    "ESCAPE, DELETE, LEFT, RIGHT, UP, DOWN, HOME, END, PAGEUP, PAGEDOWN, COMMA, PERIOD, MINUS, " +
    "EQUAL, LEFTBRACKET, RIGHTBRACKET, QUOTE, SEMICOLON, SLASH, BACKSLASH, GRAVE. " +
    "Shortcuts go to the FRONTMOST app, so bring the app you mean to the front first " +
    "(macos_activateApp) — otherwise COMMAND+W closes someone else's window."
)
data class MacOsPressKeyComboTrailblazeTool(
  @param:LLMDescription("Key name, e.g. W, T, TAB, LEFT, RETURN. Not a character — 'W', not 'w'.")
  val key: String,
  @param:LLMDescription("Modifiers to hold, e.g. [COMMAND] for COMMAND+W, or [COMMAND, SHIFT] for COMMAND+SHIFT+T.")
  val modifiers: List<MacOsModifier> = emptyList(),
  @param:LLMDescription("How many times to press the chord. Defaults to 1. COMMAND+TAB twice moves two apps along.")
  val repeats: Int = 1,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val keyCode = MacOsAxEventSynthesizer.KEY_CODES[key.trim().uppercase()]
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "Unknown key '$key'. Use a key NAME — a letter, a digit, or one of: " +
          MacOsAxEventSynthesizer.KEY_CODES.keys.sorted().joinToString(", "),
      )
    if (repeats < 1) {
      return TrailblazeToolResult.Error.ExceptionThrown("repeats must be at least 1, got $repeats.")
    }
    val flags = modifiers.fold(0L) { acc, m -> acc or m.toFlag() }
    val modifierKeyCodes = modifiers.map { MacOsAxEventSynthesizer.modifierKeyCodeFor(it.toFlag()) }
    MacOsAxEventSynthesizer.pressChord(keyCode, modifierKeyCodes, flags, repeats)
    // The frontmost app / the window server needs a beat to act on the chord before the next tool
    // reads the screen — a ⌘W that has closed nothing yet reads as a no-op.
    delay(CHORD_SETTLE_MS)
    val chord = (modifiers.map { it.name } + key.uppercase()).joinToString("+")
    return TrailblazeToolResult.Success(message = "Pressed $chord${if (repeats > 1) " x$repeats" else ""}")
  }

  companion object {
    private const val CHORD_SETTLE_MS = 400L
  }
}

@Serializable
@TrailblazeToolClass("macos_activateApp")
@LLMDescription(
  "Bring an already-running app to the front by bundle id — the reliable way to switch apps. " +
    "Prefer this over COMMAND+TAB: the app switcher moves through apps in most-recently-used " +
    "order, so which app you land on depends on history the trail can't see, whereas this names " +
    "the app you actually want. Use it before typing or pressing a shortcut, since both go to " +
    "whatever is frontmost."
)
data class MacOsActivateAppTrailblazeTool(
  @param:LLMDescription("Bundle id of the app to bring to the front, e.g. com.apple.Safari.")
  val bundleId: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val activated = MacOsAxAppResolver.activate(bundleId)
    if (!activated) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "Could not activate '$bundleId' — is it running? Use macos_launchApp to start it.",
      )
    }
    // Activation is asynchronous: the window server raises the app on its own schedule, and a tool
    // that reads the screen (or types) in the same millisecond still sees the outgoing app.
    delay(ACTIVATION_SETTLE_MS)
    return TrailblazeToolResult.Success(message = "Activated $bundleId")
  }

  companion object {
    private const val ACTIVATION_SETTLE_MS = 600L
  }
}

@Serializable
@TrailblazeToolClass("macos_assertVisible")
@LLMDescription("Assert that an element matched by a macOsAx node selector is visible on screen.")
data class MacOsAssertVisibleTrailblazeTool(
  val nodeSelector: TrailblazeNodeSelector,
  val timeoutMs: Long? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val agent = toolExecutionContext.maestroTrailblazeAgent
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "macos_assertVisible requires the macOS AX agent, which was not available.",
      )
    return agent.executeNodeSelectorAssertVisible(
      nodeSelector = nodeSelector,
      timeoutMs = timeoutMs,
      traceId = toolExecutionContext.traceId,
    ) ?: TrailblazeToolResult.Error.ExceptionThrown(
      "Element not visible for selector ${nodeSelector.description()}.",
    )
  }
}

/** Scroll directions, in the sense the CONTENT moves (DOWN reveals what is below the fold). */
@Serializable
enum class MacOsScrollDirection { UP, DOWN, LEFT, RIGHT }

@Serializable
@TrailblazeToolClass("macos_scroll")
@LLMDescription(
  "Scroll the frontmost window. Without this you can only ever act on what happens to be on " +
    "screen already — anything below the fold is unreachable. DOWN reveals content further down " +
    "the page. Scroll, then take a new snapshot: elements that were off-screen appear (and their " +
    "coordinates change), because the tree reflects what is laid out NOW."
)
data class MacOsScrollTrailblazeTool(
  @param:LLMDescription("UP, DOWN, LEFT or RIGHT. DOWN reveals content below the fold.")
  val direction: MacOsScrollDirection,
  @param:LLMDescription("How far to scroll, in points. Defaults to 300 (roughly a few lines).")
  val amountPx: Int = 300,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // A wheel event goes to whatever sits under the POINTER, so it has to land over real content:
    // the frontmost window's centre. CGWindowList returns windows front-to-back, so that's the
    // first one.
    val window = MacOsAxNative.onScreenWindows().firstOrNull()
      ?: return TrailblazeToolResult.Error.ExceptionThrown("No on-screen window to scroll.")
    val x = (window.left + window.right) / 2
    val y = (window.top + window.bottom) / 2
    val (dx, dy) = when (direction) {
      MacOsScrollDirection.UP -> 0 to amountPx
      MacOsScrollDirection.DOWN -> 0 to -amountPx
      MacOsScrollDirection.LEFT -> amountPx to 0
      MacOsScrollDirection.RIGHT -> -amountPx to 0
    }
    MacOsAxEventSynthesizer.scroll(x, y, dx, dy)
    delay(SCROLL_SETTLE_MS)
    return TrailblazeToolResult.Success(message = "Scrolled $direction by ${amountPx}px")
  }

  companion object {
    /** Let the app repaint before anything reads the screen, or the snapshot shows the old offset. */
    private const val SCROLL_SETTLE_MS = 400L
  }
}

@Serializable
@TrailblazeToolClass("macos_menuItem")
@LLMDescription(
  "Click an item in the frontmost app's MENU BAR, by path — e.g. path: [\"File\", \"New Window\"] " +
    "or [\"Format\", \"Font\", \"Bold\"]. A great deal of macOS lives only in a menu (Export as " +
    "PDF, Show Hidden Files, Merge All Windows, anything with no keyboard shortcut) and menus are " +
    "NOT in the snapshot — an app's menu bar fans out into hundreds of lazily-populated items and " +
    "walking it on every capture would cost more than the rest of the tree combined, so it is " +
    "visited only when you ask. Titles must match what the menu actually says; if one doesn't, the " +
    "error lists the real ones."
)
data class MacOsMenuItemTrailblazeTool(
  @param:LLMDescription("Menu path from the menu bar down, e.g. [\"File\", \"New Window\"].")
  val path: List<String>,
  @param:LLMDescription("Bundle id of the app whose menu bar to use. Defaults to whichever app is frontmost.")
  val bundleId: String? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // A menu bar belongs to an APP, and the one on screen belongs to the frontmost app — so an
    // explicit bundleId is activated first, or we would drive the wrong app's File menu.
    val pid = if (bundleId != null) {
      MacOsAxAppResolver.activate(bundleId)
      delay(APP_SETTLE_MS)
      MacOsAxAppResolver.ensureRunning(bundleId)
        ?: return TrailblazeToolResult.Error.ExceptionThrown("'$bundleId' is not running.")
    } else {
      MacOsAxNative.onScreenWindows().firstOrNull()?.pid
        ?: return TrailblazeToolResult.Error.ExceptionThrown("No frontmost app to take a menu from.")
    }
    val failure = MacOsAxMenu.clickPath(pid, path)
    delay(MENU_SETTLE_MS)
    return if (failure == null) {
      TrailblazeToolResult.Success(message = "Clicked menu ${path.joinToString(" > ")}")
    } else {
      TrailblazeToolResult.Error.ExceptionThrown(failure)
    }
  }

  companion object {
    private const val APP_SETTLE_MS = 500L
    private const val MENU_SETTLE_MS = 400L
  }
}

@Serializable
@TrailblazeToolClass("macos_contextMenuSelect")
@LLMDescription(
  "Right-click something and choose an item from the context menu that opens — 'Open Link in New " +
    "Tab', 'Copy', 'Open With', 'Inspect Element'. Context menus hold a large amount of desktop " +
    "functionality and cannot be reached any other way. " +
    "This is deliberately ONE operation rather than a right-click followed by a click: an open " +
    "menu is a modal state, it does not appear in the snapshot, and taking a snapshot while one is " +
    "open can hang the capture — so the menu is opened and acted on in a single step, and dismissed " +
    "if the item isn't there. If the title doesn't match, the error lists the items that are."
)
data class MacOsContextMenuSelectTrailblazeTool(
  @param:LLMDescription("Exact item title as shown in the menu, e.g. 'Open Link in New Tab'.")
  val item: String,
  @param:LLMDescription("Element to right-click. Preferred over x/y — a selector survives the window moving.")
  val nodeSelector: TrailblazeNodeSelector? = null,
  @param:LLMDescription("X point to right-click, if no selector.")
  val x: Int? = null,
  @param:LLMDescription("Y point to right-click, if no selector.")
  val y: Int? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val point = MacOsPointResolver.resolve(toolExecutionContext, nodeSelector, x, y)
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "macos_contextMenuSelect needs either a nodeSelector that matches, or an x/y point.",
      )
    val pid = MacOsAxNative.onScreenWindows().firstOrNull()?.pid
      ?: return TrailblazeToolResult.Error.ExceptionThrown("No frontmost app to right-click in.")

    MacOsAxEventSynthesizer.rightClick(point.first, point.second)
    delay(MENU_OPEN_MS)

    val failure = MacOsAxMenu.clickOpenMenuItem(
      pid = pid,
      title = item,
      nearX = point.first,
      nearY = point.second,
    )
    return if (failure == null) {
      delay(MENU_ACTION_MS)
      TrailblazeToolResult.Success(message = "Right-clicked (${point.first}, ${point.second}) and chose '$item'")
    } else {
      // Never leave the desktop sitting in an open menu: it's modal, it's invisible to the snapshot,
      // and the next capture has to deal with it. Escape puts things back the way we found them.
      MacOsAxEventSynthesizer.pressKeyCode(MacOsAxEventSynthesizer.KEY_CODE_ESCAPE)
      delay(MENU_ACTION_MS)
      TrailblazeToolResult.Error.ExceptionThrown(failure)
    }
  }

  companion object {
    /** Context menus animate open; looking for the items too early finds nothing. */
    private const val MENU_OPEN_MS = 600L
    private const val MENU_ACTION_MS = 500L
  }
}

@Serializable
@TrailblazeToolClass("macos_doubleClick")
@LLMDescription(
  "Double-click — open a file in Finder, select a word in a text field. A single click does not " +
    "open anything, and two macos_tapOnElement calls are not a double-click: macOS decides that " +
    "from the click's timing and its click-count field, so it has to be sent as one gesture."
)
data class MacOsDoubleClickTrailblazeTool(
  @param:LLMDescription("Element to double-click. Preferred over x/y.")
  val nodeSelector: TrailblazeNodeSelector? = null,
  @param:LLMDescription("X point, if no selector.")
  val x: Int? = null,
  @param:LLMDescription("Y point, if no selector.")
  val y: Int? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val point = MacOsPointResolver.resolve(toolExecutionContext, nodeSelector, x, y)
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "macos_doubleClick needs either a nodeSelector that matches, or an x/y point.",
      )
    MacOsAxEventSynthesizer.doubleClick(point.first, point.second)
    delay(OPEN_SETTLE_MS)
    return TrailblazeToolResult.Success(message = "Double-clicked at (${point.first}, ${point.second})")
  }

  companion object {
    private const val OPEN_SETTLE_MS = 600L
  }
}

@Serializable
@TrailblazeToolClass("macos_eraseText")
@LLMDescription("Delete characters from the focused field, as if pressing Backspace that many times.")
data class MacOsEraseTextTrailblazeTool(
  @param:LLMDescription("How many characters to delete.")
  val characters: Int,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (characters < 1) {
      return TrailblazeToolResult.Error.ExceptionThrown("characters must be at least 1, got $characters.")
    }
    MacOsAxEventSynthesizer.pressKeyCode(MacOsAxEventSynthesizer.KEY_CODE_DELETE, characters)
    return TrailblazeToolResult.Success(message = "Erased $characters character(s)")
  }
}

@Serializable
@TrailblazeToolClass("macos_assertNotVisible")
@LLMDescription(
  "Assert an element is NOT on screen — that a dialog closed, a spinner finished, an item was " +
    "deleted. Polls until the timeout, so it waits for the thing to GO AWAY rather than checking " +
    "once and passing because it hadn't appeared yet."
)
data class MacOsAssertNotVisibleTrailblazeTool(
  val nodeSelector: TrailblazeNodeSelector,
  val timeoutMs: Long? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val agent = toolExecutionContext.maestroTrailblazeAgent
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "macos_assertNotVisible requires the macOS AX agent, which was not available.",
      )
    return agent.executeNodeSelectorAssertNotVisible(
      nodeSelector = nodeSelector,
      timeoutMs = timeoutMs,
      traceId = toolExecutionContext.traceId,
    ) ?: TrailblazeToolResult.Error.ExceptionThrown(
      "Element still visible for selector ${nodeSelector.description()}.",
    )
  }
}

@Serializable
@TrailblazeToolClass("macos_setWindowBounds")
@LLMDescription(
  "Move and resize an app's window to an exact position and size, in points. " +
    "This is what makes a recorded trail survive a different machine. Any step that clicks a " +
    "coordinate — unavoidable for apps that custom-draw their controls and expose no element to " +
    "select — assumes the window is where it was when the trail was recorded. Normalize the window " +
    "FIRST and that assumption becomes true instead of hopeful. Selector-based steps don't need " +
    "this, but a trail that mixes the two does."
)
data class MacOsSetWindowBoundsTrailblazeTool(
  @param:LLMDescription("X of the window's top-left corner, in points from the left of the main display.")
  val x: Int,
  @param:LLMDescription("Y of the window's top-left corner, in points from the top of the main display.")
  val y: Int,
  @param:LLMDescription("Window width in points.")
  val width: Int,
  @param:LLMDescription("Window height in points.")
  val height: Int,
  @param:LLMDescription("Bundle id of the app whose window to move. Defaults to the frontmost app.")
  val bundleId: String? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (width <= 0 || height <= 0) {
      return TrailblazeToolResult.Error.ExceptionThrown("width and height must be positive.")
    }
    val pid = if (bundleId != null) {
      MacOsAxAppResolver.runningPid(bundleId)
        ?: return TrailblazeToolResult.Error.ExceptionThrown(
          "'$bundleId' is not running — launch it first with macos_launchApp.",
        )
    } else {
      MacOsAxNative.onScreenWindows().firstOrNull()?.pid
        ?: return TrailblazeToolResult.Error.ExceptionThrown("No frontmost app whose window could be moved.")
    }

    var actualFrame: List<Int>? = null
    val app = MacOsAxNative.createApplication(pid)
    try {
      val window = MacOsAxNative.mainWindow(app)
        ?: return TrailblazeToolResult.Error.ExceptionThrown("That app exposes no window to Accessibility.")
      val frame = try {
        MacOsAxNative.setWindowBounds(window, x, y, width, height)
      } finally {
        MacOsAxNative.release(window)
      } ?: return TrailblazeToolResult.Error.ExceptionThrown("That window reports no frame to set.")

      if (!MacOsAxNative.windowMovedTo(frame, x, y)) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          "The window would not move to ($x, $y) — it is at (${frame[0]}, ${frame[1]}). macOS will " +
            "not move a full-screen window (leave full screen first), and some panels are pinned. " +
            "Coordinate clicks after this would land in the wrong place, so this is a hard failure.",
        )
      }
      actualFrame = frame
    } finally {
      MacOsAxNative.release(app)
    }
    // The window server applies the change asynchronously; a snapshot taken immediately can still
    // report the old frame, and a coordinate click right after would land in the old place.
    delay(WINDOW_SETTLE_MS)

    val (_, _, actualWidth, actualHeight) = actualFrame!!
    val clamped = actualWidth != width || actualHeight != height
    val note = if (clamped) {
      " — the app clamped its size (it has a minimum or a fixed aspect), so it is ${actualWidth}x" +
        "$actualHeight rather than ${width}x$height. That is still deterministic: it will clamp the " +
        "same way on every run. Record ${actualWidth}x$actualHeight in your trail to make it obvious."
    } else {
      ""
    }
    return TrailblazeToolResult.Success(
      message = "Window at ($x, $y), ${actualWidth}x$actualHeight$note",
    )
  }

  companion object {
    private const val WINDOW_SETTLE_MS = 400L
  }
}

@Serializable
@TrailblazeToolClass("macos_quitApp")
@LLMDescription("Quit an app by bundle id. Unsaved work may prompt a save dialog rather than quitting.")
data class MacOsQuitAppTrailblazeTool(
  @param:LLMDescription("Bundle id of the app to quit, e.g. com.apple.TextEdit.")
  val bundleId: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val quit = MacOsAxAppResolver.quit(bundleId)
    delay(QUIT_SETTLE_MS)
    return if (quit) {
      TrailblazeToolResult.Success(message = "Quit $bundleId")
    } else {
      TrailblazeToolResult.Error.ExceptionThrown("Could not quit '$bundleId'.")
    }
  }

  companion object {
    private const val QUIT_SETTLE_MS = 500L
  }
}

/**
 * Turns "a selector, or an x/y" into a screen point, for the tools that can take either.
 *
 * Resolves against the screen state the tool was handed — the same tree the caller read when it
 * chose the selector — rather than re-capturing, so the coordinates match the snapshot the caller
 * is actually looking at.
 */
internal object MacOsPointResolver {
  fun resolve(
    context: TrailblazeToolExecutionContext,
    nodeSelector: TrailblazeNodeSelector?,
    x: Int?,
    y: Int?,
  ): Pair<Int, Int>? {
    if (nodeSelector != null) {
      val tree = context.screenState?.trailblazeNodeTree ?: return null
      val node = when (val r = TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector)) {
        is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> r.node
        is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> r.nodes.firstOrNull()
        is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
      } ?: return null
      return node.centerPoint()
    }
    if (x != null && y != null) return x to y
    return null
  }
}

@Serializable
@TrailblazeToolClass("macos_openUrl")
@LLMDescription(
  "Open a URL in a macOS browser by driving it via Accessibility: launches/focuses the browser, " +
    "focuses the address bar (⌘L), types the URL, and presses Return.",
)
data class MacOsOpenUrlTrailblazeTool(
  @param:LLMDescription("The URL to open, e.g. https://handstandsam.com.")
  val url: String,
  @param:LLMDescription("Browser bundle id. Defaults to Safari (com.apple.Safari).")
  val browserBundleId: String = "com.apple.Safari",
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val targetUrl = toolExecutionContext.memory.interpolateVariables(url)

    // Navigate via the OS's URL opener bound to the chosen browser. `open -b <bundleId> <url>`
    // launches the browser if needed, brings it to the front, and loads the URL in one shot —
    // reliably, unlike synthetic keystrokes into a browser's hardened address bar (Safari ignores
    // both batched unicode events and programmatic AXValue writes). The *driving* the macOS AX
    // driver then demonstrates is reading and clicking the loaded page (macos_assertVisible /
    // macos_tapOnElement over the AXWebArea the capture path now opts into).
    val exit = runCatching {
      ProcessBuilder("open", "-b", browserBundleId, targetUrl)
        .redirectErrorStream(true)
        .start()
        .waitFor()
    }.getOrElse {
      return TrailblazeToolResult.Error.ExceptionThrown("Failed to open '$targetUrl': ${it.message}")
    }
    if (exit != 0) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "`open -b $browserBundleId $targetUrl` exited with code $exit.",
      )
    }

    // Foreground the browser and wait for a window.
    MacOsAxAppResolver.activate(browserBundleId)
    val pid = MacOsAxAppResolver.ensureRunning(browserBundleId)
    if (pid != null) {
      val deadline = System.currentTimeMillis() + WINDOW_WAIT_MS
      while (System.currentTimeMillis() < deadline && MacOsAxNative.windowCount(pid) == 0) {
        delay(150)
      }
      // Opt the browser into exposing web-content accessibility NOW (it builds asynchronously),
      // so the AXWebArea is populated by the time a following macos_assertVisible / macos_tapOnElement
      // reads the tree. Building the tree also needs the page to have loaded, so wait after.
      val app = MacOsAxNative.createApplication(pid)
      try {
        MacOsAxNative.enableEnhancedWebAccessibility(app)
      } finally {
        MacOsAxNative.release(app)
      }
    }
    delay(2_500)

    return TrailblazeToolResult.Success(message = "Opened '$targetUrl' in $browserBundleId")
  }

  companion object {
    private const val WINDOW_WAIT_MS = 8_000L
  }
}

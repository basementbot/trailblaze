package xyz.block.trailblaze.host.macosax.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import maestro.KeyCode
import maestro.orchestra.Command
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.PressKeyCommand
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

package xyz.block.trailblaze.host.macosax

import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * macOS-desktop agent that drives a running app natively through the Apple Accessibility APIs
 * (`AXUIElement`) instead of Maestro/XCUITest.
 *
 * Desktop parallel of [xyz.block.trailblaze.host.axe.IosAxeTrailblazeAgent]: it extends
 * [MaestroTrailblazeAgent] so the existing tool catalog and `runMaestroCommands` plumbing keep
 * working, but overrides the hot-path methods to route through [MacOsAxDeviceManager] /
 * [MacOsAxTrailRunner]. Maestro-shaped inputs are translated at the boundary by
 * [MaestroCommandToMacOsAxActionConverter]; rich [TrailblazeNodeSelector]s (with native
 * `DriverNodeMatch.MacOsAx` matches) are executed directly.
 */
class MacOsAxTrailblazeAgent(
  private val deviceManager: MacOsAxDeviceManager,
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
  nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  nodeSelectorMode = nodeSelectorMode,
) {

  /** Flagged so tools choose AX-friendly command paths (mirrors the iOS AXe / Android flags). */
  override val usesAccessibilityDriver: Boolean = true

  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val actions = MaestroCommandToMacOsAxActionConverter.convertAll(commands)
    if (actions.isEmpty() && commands.isNotEmpty()) {
      val skipped = commands.map { it::class.simpleName }.distinct().joinToString(", ")
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "All ${commands.size} Maestro command(s) are unsupported by the macOS AX driver. Skipped: $skipped",
      )
    }
    return MacOsAxTrailRunner.runActions(
      actions = actions,
      traceId = traceId,
      deviceManager = deviceManager,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
    )
  }

  override suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult = MacOsAxTrailRunner.runActions(
    // macOS AX has no distinct long-press primitive; AXPress covers activation either way.
    actions = listOf(MacOsAxAction.TapOnElement(nodeSelector)),
    traceId = traceId,
    deviceManager = deviceManager,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  override suspend fun executeNodeSelectorAssertVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long?,
    traceId: TraceId?,
  ): TrailblazeToolResult = MacOsAxTrailRunner.runActions(
    actions = listOf(MacOsAxAction.AssertVisible(nodeSelector, timeoutMs ?: DEFAULT_MACOS_AX_TIMEOUT_MS)),
    traceId = traceId,
    deviceManager = deviceManager,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  override suspend fun executeNodeSelectorAssertNotVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long?,
    traceId: TraceId?,
  ): TrailblazeToolResult = MacOsAxTrailRunner.runActions(
    actions = listOf(MacOsAxAction.AssertNotVisible(nodeSelector, timeoutMs ?: DEFAULT_MACOS_AX_TIMEOUT_MS)),
    traceId = traceId,
    deviceManager = deviceManager,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  /**
   * One-shot tool dispatch entry point for the MCP bridge, mirroring
   * [xyz.block.trailblaze.host.axe.IosAxeTrailblazeAgent.runTool]: handles the
   * [ExecutableTrailblazeTool] and [DelegatingTrailblazeTool] shapes; tools that internally call
   * `runMaestroCommands` land back on [executeMaestroCommands] and route through the AX pipeline.
   */
  suspend fun runTool(tool: TrailblazeTool, context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return when (tool) {
      is ExecutableTrailblazeTool -> tool.execute(context)
      is DelegatingTrailblazeTool -> {
        val expansions = tool.toExecutableTrailblazeTools(context)
        if (expansions.isEmpty()) return TrailblazeToolResult.Success()
        var last: TrailblazeToolResult = TrailblazeToolResult.Success()
        for (expansion in expansions) {
          last = expansion.execute(context)
          if (!last.isSuccess()) break
        }
        last
      }
      else -> throw TrailblazeException(
        message = "Tool ${tool::class.java.simpleName} is not a known TrailblazeTool shape " +
          "(ExecutableTrailblazeTool or DelegatingTrailblazeTool) — cannot execute on MACOS_AX.",
      )
    }
  }

  companion object {
    private const val DEFAULT_MACOS_AX_TIMEOUT_MS = 5_000L
  }
}

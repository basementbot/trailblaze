package xyz.block.trailblaze.host.macosax

import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Executes [MacOsAxAction]s sequentially through a [MacOsAxDeviceManager], parallel to
 * [xyz.block.trailblaze.host.axe.AxeTrailRunner] (iOS). Errors short-circuit the run and surface
 * as a [TrailblazeToolResult.Error].
 */
object MacOsAxTrailRunner {

  fun runActions(
    actions: List<MacOsAxAction>,
    traceId: TraceId?,
    deviceManager: MacOsAxDeviceManager,
    trailblazeLogger: TrailblazeLogger? = null,
    sessionProvider: TrailblazeSessionProvider? = null,
  ): TrailblazeToolResult {
    for (action in actions) {
      val startedAt = Clock.System.now().toEpochMilliseconds()
      try {
        deviceManager.execute(action)
        val elapsed = Clock.System.now().toEpochMilliseconds() - startedAt
        Console.log("[MacOsAxTrailRunner] ${action.description} — ${elapsed}ms (trace=$traceId)")
      } catch (e: Exception) {
        Console.log("[MacOsAxTrailRunner] ${action.description} FAILED: ${e.message}")
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed action: ${action.description}. Error: ${e.message}",
          stackTrace = e.stackTraceToString(),
        )
      }
    }
    return TrailblazeToolResult.Success()
  }
}

package xyz.block.trailblaze.host.macosax

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.NoOpLogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import java.util.concurrent.TimeUnit

/**
 * Phase 5 verification (see docs/devlog/2026-07-10-macos-ax-driver.md): exercise the real
 * **agent entry point** — [MacOsAxTrailblazeAgent] (the class the MCP bridge constructs per tool
 * call) — at runtime, not just at compile time. Confirms the agent's `executeNodeSelector*`
 * overrides route through `MacOsAxTrailRunner → MacOsAxDeviceManager → AXPress` correctly.
 *
 * Uses Calculator (non-intrusive AXPress). Skips (doesn't fail) when not AX-trusted / no app.
 */
class MacOsAxAgentTest {

  private val bundleId = "com.apple.calculator"

  @Test
  fun agentDrivesCalculatorViaNodeSelectorTap() {
    if (!MacOsAxNative.isProcessTrusted()) {
      Console.log("[Phase5] NOT Accessibility-trusted — skipping.")
      return
    }
    ProcessBuilder("open", "-a", "Calculator").start().waitFor(5, TimeUnit.SECONDS)
    Thread.sleep(1500)
    val pid = findPid("Calculator") ?: run { Console.log("[Phase5] no Calculator — skipping."); return }
    try {
      val deviceManager = MacOsAxDeviceManager(pid = pid, deviceWidth = 0, deviceHeight = 0)
      val deviceId = TrailblazeDeviceId(bundleId, TrailblazeDevicePlatform.DESKTOP)
      val agent = MacOsAxTrailblazeAgent(
        deviceManager = deviceManager,
        trailblazeLogger = TrailblazeLogger(logEmitter = NoOpLogEmitter, screenStateLogger = ScreenStateLogger { "" }),
        trailblazeDeviceInfoProvider = {
          TrailblazeDeviceInfo(
            trailblazeDeviceId = deviceId,
            trailblazeDriverType = TrailblazeDriverType.MACOS_AX,
            widthPixels = 0,
            heightPixels = 0,
          )
        },
        sessionProvider = { TrailblazeSession(sessionId = SessionId("macos-ax-test"), startTime = Clock.System.now()) },
      )

      // The real agent override: resolve a native AXIdentifier selector and tap it.
      val result = runBlocking {
        agent.executeNodeSelectorTap(
          nodeSelector = TrailblazeNodeSelector(macOsAx = DriverNodeMatch.MacOsAx(identifier = "Seven")),
          longPress = false,
          traceId = null,
        )
      }
      Console.log("[Phase5] agent.executeNodeSelectorTap result = $result")
      check(result is TrailblazeToolResult.Success) { "agent tap failed: $result" }

      val display = deviceManager.captureTree()?.aggregate()
        ?.mapNotNull { it.driverDetail as? DriverNodeDetail.MacOsAx }
        ?.filter { it.role == "AXStaticText" }
        ?.mapNotNull { it.stringAttribute("AXValue") }
        ?.map { v -> v.filter { it.isDigit() } }
        ?.firstOrNull { it.isNotEmpty() }
      Console.log("[Phase5] display after agent tapped 'Seven' = '$display'")
      check(display == "7") { "expected display 7 after agent tap, was '$display'" }
      Console.log("[Phase5] ✅ MacOsAxTrailblazeAgent entry point verified at runtime")
    } finally {
      ProcessBuilder("osascript", "-e", "tell application id \"$bundleId\" to quit")
        .start().waitFor(5, TimeUnit.SECONDS)
    }
  }

  private fun findPid(processName: String): Int? =
    runCatching {
      val proc = ProcessBuilder("pgrep", "-x", processName).start()
      val out = proc.inputStream.bufferedReader().readText().trim()
      proc.waitFor()
      out.lineSequence().firstOrNull()?.trim()?.toIntOrNull()
    }.getOrNull()
}

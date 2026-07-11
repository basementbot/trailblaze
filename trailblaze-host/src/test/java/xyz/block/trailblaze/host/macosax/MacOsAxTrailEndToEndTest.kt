package xyz.block.trailblaze.host.macosax

import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import java.util.concurrent.TimeUnit

/**
 * Phase 6 end-to-end verification (see docs/devlog/2026-07-10-macos-ax-driver.md): author a
 * real **trail** — an ordered list of [MacOsAxAction] steps built from
 * [TrailblazeNodeSelector]s over exact native `AX*` vocabulary — and run it through the full
 * driver stack ([MacOsAxTrailRunner] → [MacOsAxDeviceManager] → [MacOsAxActionExecutor] →
 * AX-native `AXPress`), then assert the outcome with a selector, exactly the way a real trail
 * would. Also exercises the [MacOsAxDeviceManager.getScreenState] path (tree + compact list +
 * screenshot) end-to-end.
 *
 * Uses macOS Calculator (`1 + 2 = 3`) so it's deterministic and non-intrusive (AXPress doesn't
 * move the cursor). Skips (doesn't fail) when not AX-trusted or Calculator can't launch.
 */
class MacOsAxTrailEndToEndTest {

  private val bundleId = "com.apple.calculator"

  private fun tap(id: String) = MacOsAxAction.TapOnElement(
    TrailblazeNodeSelector(macOsAx = DriverNodeMatch.MacOsAx(identifier = id)),
  )

  @Test
  fun runCalculatorTrailEndToEnd() {
    if (!MacOsAxNative.isProcessTrusted()) {
      Console.log("[Phase6] NOT Accessibility-trusted — skipping.")
      return
    }
    ProcessBuilder("open", "-a", "Calculator").start().waitFor(5, TimeUnit.SECONDS)
    Thread.sleep(1500)
    val pid = findPid("Calculator") ?: run {
      Console.log("[Phase6] Calculator didn't start — skipping."); return
    }
    try {
      val deviceManager = MacOsAxDeviceManager(pid = pid, deviceWidth = 0, deviceHeight = 0)

      // --- The authored trail: press 1 + 2 = , then assert the result shows 3. ---
      val trail: List<MacOsAxAction> = listOf(
        tap("One"),
        tap("Add"),
        tap("Two"),
        tap("Equals"),
        MacOsAxAction.AssertVisible(
          TrailblazeNodeSelector(
            macOsAx = DriverNodeMatch.MacOsAx(roleRegex = "AXStaticText", valueRegex = ".*3.*"),
          ),
        ),
      )

      val result = MacOsAxTrailRunner.runActions(trail, traceId = null, deviceManager = deviceManager)
      Console.log("[Phase6] trail result = $result")
      check(result is TrailblazeToolResult.Success) { "trail failed: $result" }
      Console.log("[Phase6] ✅ authored trail ran end-to-end (1+2= asserted as 3 via selector)")

      // --- ScreenState integration: tree + compact list + screenshot together. ---
      val screen = deviceManager.getScreenState()
      val treeText = screen.viewHierarchyTextRepresentation
      Console.log("[Phase6] ScreenState compact list has ${treeText?.lineSequence()?.count() ?: 0} lines")
      Console.log("[Phase6] ScreenState screenshot bytes = ${screen.screenshotBytes?.size ?: 0}")
      check(treeText != null && treeText.contains("AXButton")) { "expected AX tree text with buttons" }
      Console.log("[Phase6] ✅ ScreenState (tree + compact list + screenshot) integrated")
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

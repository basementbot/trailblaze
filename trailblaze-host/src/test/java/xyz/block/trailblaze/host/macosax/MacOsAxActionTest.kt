package xyz.block.trailblaze.host.macosax

import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.util.Console
import java.util.concurrent.TimeUnit

/**
 * Phase 4 verification (see docs/devlog/2026-07-10-macos-ax-driver.md): drive the macOS
 * **Calculator** end-to-end through the full pipeline — `TrailblazeNodeSelector` (matching exact
 * native `AXIdentifier`s) → [TrailblazeNodeSelectorResolver] → [MacOsAxActionExecutor] → AX-native
 * `AXPress`. This path does NOT move the cursor or steal keyboard focus, so it's safe to run
 * without hijacking the machine.
 *
 * Calculator keys its buttons by `AXIdentifier` ("One", "Add", "Equals", …), all advertising the
 * `AXPress` action, and shows the result on an `AXStaticText`'s `AXValue`. The test presses
 * `1 + 2 =`, reads the display, asserts `3`, then quits. Skips (doesn't fail) when not AX-trusted
 * or Calculator can't launch.
 */
class MacOsAxActionTest {

  private val bundleId = "com.apple.calculator"

  @Test
  fun calculatorArithmeticViaSelectorAndAxPress() {
    if (!MacOsAxNative.isProcessTrusted()) {
      Console.log("[Phase4] NOT Accessibility-trusted — skipping.")
      return
    }
    ProcessBuilder("open", "-a", "Calculator").start().waitFor(5, TimeUnit.SECONDS)
    Thread.sleep(1500)
    val pid = findPid("Calculator")
    if (pid == null) {
      Console.log("[Phase4] Calculator didn't start — skipping.")
      return
    }
    try {
      val executor = MacOsAxActionExecutor(pid)

      // Sanity: the keypad is exposed as AXButtons keyed by AXIdentifier.
      val ids = executor.captureTree().aggregate()
        .mapNotNull { it.driverDetail as? DriverNodeDetail.MacOsAx }
        .filter { it.role == "AXButton" }
        .mapNotNull { it.stringAttribute("AXIdentifier") }
      Console.log("[Phase4] keypad button identifiers: ${ids.sorted()}")
      check(listOf("One", "Two", "Add", "Equals").all { it in ids }) {
        "Calculator keypad not exposed as expected (have $ids)"
      }

      // Press 1 + 2 = via selectors on exact native AXIdentifier — the real resolve→AXPress path.
      pressById(executor, "One")
      pressById(executor, "Add")
      pressById(executor, "Two")
      pressById(executor, "Equals")
      Thread.sleep(400)

      val result = readDisplay(executor.captureTree())
      Console.log("[Phase4] Calculator display after 1+2= : '$result'")
      check(result != null && result.contains("3")) { "expected result to contain 3, was '$result'" }
      Console.log("[Phase4] ✅ selector → resolve → AXPress arithmetic verified")
    } finally {
      ProcessBuilder("osascript", "-e", "tell application id \"$bundleId\" to quit")
        .start().waitFor(5, TimeUnit.SECONDS)
    }
  }

  private fun pressById(executor: MacOsAxActionExecutor, identifier: String) {
    val selector = TrailblazeNodeSelector(macOsAx = DriverNodeMatch.MacOsAx(identifier = identifier))
    Console.log("[Phase4] tap AXIdentifier=$identifier")
    check(executor.execute(MacOsAxAction.TapOnElement(selector))) { "tap '$identifier' failed" }
    Thread.sleep(250)
  }

  /**
   * The Calculator *result* display: the AXStaticText whose AXValue is a pure number. This
   * deliberately excludes the expression line (e.g. "1+2"), which contains operators — stripping
   * those would falsely read "1+2" as "12".
   */
  private fun readDisplay(tree: TrailblazeNode): String? {
    val pureNumber = Regex("""-?[\d,]+(\.\d+)?""")
    return tree.aggregate()
      .mapNotNull { it.driverDetail as? DriverNodeDetail.MacOsAx }
      .filter { it.role == "AXStaticText" }
      .mapNotNull { it.stringAttribute("AXValue") }
      // Strip Unicode direction marks (U+200E/U+200F) and whitespace Calculator adds.
      .map { it.filter { c -> !c.isWhitespace() && c != '‎' && c != '‏' } }
      .firstOrNull { it.isNotEmpty() && pureNumber.matches(it) }
  }

  private fun findPid(processName: String): Int? =
    runCatching {
      val proc = ProcessBuilder("pgrep", "-x", processName).start()
      val out = proc.inputStream.bufferedReader().readText().trim()
      proc.waitFor()
      out.lineSequence().firstOrNull()?.trim()?.toIntOrNull()
    }.getOrNull()
}

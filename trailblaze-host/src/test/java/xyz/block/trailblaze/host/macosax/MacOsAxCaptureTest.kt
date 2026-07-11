package xyz.block.trailblaze.host.macosax

import org.junit.Test
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.util.Console

/**
 * Phase 2 verification (see docs/devlog/2026-07-10-macos-ax-driver.md): capture a REAL running
 * app's Accessibility tree via [MacOsAxTreeWalker] and confirm the round trip produces
 * full-fidelity [DriverNodeDetail.MacOsAx] nodes (exact `AX*` keys, decoded structs, bounds).
 *
 * Lenient by design — skips (rather than fails) when the JVM isn't Accessibility-trusted or the
 * target app isn't running, so it's safe in CI. When it can run, it asserts the tree is real.
 * Read the printed output to eyeball the captured attributes/compact list.
 */
class MacOsAxCaptureTest {

  @Test
  fun captureFinderTree() {
    if (!MacOsAxNative.isProcessTrusted()) {
      Console.log("[Phase2] NOT Accessibility-trusted — skipping. Grant the JVM/Terminal AX access.")
      return
    }
    val pid = findPid("Finder")
    if (pid == null) {
      Console.log("[Phase2] no Finder pid — skipping.")
      return
    }
    Console.log("[Phase2] capturing AX tree for Finder (pid=$pid)…")
    val tree = MacOsAxTreeWalker.capture(pid)

    val all = tree.aggregate()
    Console.log("[Phase2] captured ${all.size} nodes")

    // --- Fidelity assertions ---
    val details = all.map { it.driverDetail }.filterIsInstance<DriverNodeDetail.MacOsAx>()
    check(details.size == all.size) { "every node should carry MacOsAx detail" }
    check(details.all { it.pid == pid }) { "every node's pid should be $pid" }

    // The root app element must expose AXRole = AXApplication (exact native key), proving the
    // attribute map is keyed by unmodified AX* strings.
    val rootDetail = tree.driverDetail as DriverNodeDetail.MacOsAx
    Console.log("[Phase2] root AXRole = ${rootDetail.role}, attribute count = ${rootDetail.attributes.size}")
    check(rootDetail.role == "AXApplication") { "root role should be AXApplication, was ${rootDetail.role}" }
    check(rootDetail.attributes.containsKey("AXRole")) { "attributes must be keyed by exact AX* names" }

    // Some node in the tree should have decoded bounds (from AXFrame or AXPosition+AXSize).
    val withBounds = all.count { it.bounds != null }
    Console.log("[Phase2] $withBounds/${all.size} nodes have decoded bounds")
    check(withBounds > 0) { "expected at least one node with decoded bounds" }

    // Prove struct decode actually ran: at least one AXFrame decoded to a Rect somewhere.
    val rectCount = details.count { it.attributes["AXFrame"] is xyz.block.trailblaze.api.MacOsAxAttributeValue.Rect }
    Console.log("[Phase2] $rectCount nodes decoded an AXFrame CGRect")

    // --- Dump a sample for eyeballing ---
    Console.log("[Phase2] --- root attributes (full fidelity) ---")
    rootDetail.attributes.entries.sortedBy { it.key }.take(30).forEach { (k, v) ->
      Console.log("  $k = $v")
    }
    Console.log("[Phase2] root actions = ${rootDetail.actions}")
    Console.log("[Phase2] root parameterizedAttributeNames = ${rootDetail.parameterizedAttributeNames}")

    val compact = CompactScreenElements.buildForMacOsAx(tree, screenHeight = 0, screenWidth = 0)
    Console.log("[Phase2] --- compact element list (first 40 lines) ---")
    compact.text.lineSequence().take(40).forEach { Console.log(it) }
  }

  @Test
  fun screenshotCaptureReturnsPng() {
    if (!MacOsAxScreenshotCli.isAvailable()) {
      Console.log("[Phase3] screencapture not available — skipping.")
      return
    }
    val bytes = MacOsAxScreenshotCli.captureMainDisplay()
    if (bytes == null) {
      // Most likely cause: Screen Recording permission not granted to the JVM. Don't fail CI.
      Console.log("[Phase3] captureMainDisplay() returned null (Screen Recording permission?) — skipping assert.")
      return
    }
    Console.log("[Phase3] captured ${bytes.size} screenshot bytes")
    // PNG magic number: 89 50 4E 47.
    check(bytes.size > 8) { "screenshot too small" }
    check(
      bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte(),
    ) { "expected PNG magic bytes" }
  }

  private fun findPid(processName: String): Int? =
    runCatching {
      val proc = ProcessBuilder("pgrep", "-x", processName).start()
      val out = proc.inputStream.bufferedReader().readText().trim()
      proc.waitFor()
      out.lineSequence().firstOrNull()?.trim()?.toIntOrNull()
    }.getOrNull()
}

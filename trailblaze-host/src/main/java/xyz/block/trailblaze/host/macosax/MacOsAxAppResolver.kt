package xyz.block.trailblaze.host.macosax

import xyz.block.trailblaze.util.Console
import java.util.concurrent.TimeUnit

/**
 * Resolves a macOS **bundle id** into the runtime facts the AX driver needs: the live process id
 * of the running instance (launching it first if necessary), and the main-display size.
 *
 * Bundle id is the macOS analog of the iOS bundle id — it launches the app (`open -b`),
 * re-attaches to an already-running instance (bundle id → pid), and quits it, all without a
 * simulator-UDID equivalent. pid is derived here and kept internal; only the bundle id is ever
 * authored in a trail/target.
 */
object MacOsAxAppResolver {

  /** Main-display size in AX points (top-left origin), matching the full-display screenshot space. */
  data class DisplaySize(val width: Int, val height: Int)

  /**
   * Returns the pid of the running app with [bundleId], or null if it isn't running.
   * Uses `System Events` (Accessibility-independent) so it works before the AX tree is read.
   */
  fun runningPid(bundleId: String): Int? {
    val out = runOsascript(
      "tell application \"System Events\" to get unix id of " +
        "(first process whose bundle identifier is \"$bundleId\")",
    ) ?: return null
    return out.trim().toIntOrNull()
  }

  /**
   * Ensures the app for [bundleId] is running and returns its pid. Launches via `open -b` when
   * not already running, then polls up to [timeoutMs] for the process to appear. Returns null if
   * the app can't be launched/resolved in time.
   */
  fun ensureRunning(bundleId: String, timeoutMs: Long = 8_000L): Int? {
    runningPid(bundleId)?.let { return it }
    Console.log("[MacOsAxAppResolver] launching $bundleId via `open -b`")
    if (!shell(listOf("open", "-b", bundleId))) {
      Console.log("[MacOsAxAppResolver] `open -b $bundleId` failed")
      return null
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      runningPid(bundleId)?.let { return it }
      Thread.sleep(200L)
    }
    return null
  }

  /** Quits the app for [bundleId] via AppleScript (`application id … to quit`). */
  fun quit(bundleId: String): Boolean =
    shell(listOf("osascript", "-e", "tell application id \"$bundleId\" to quit"))

  /** Brings the app for [bundleId] to the foreground. */
  fun activate(bundleId: String): Boolean =
    shell(listOf("osascript", "-e", "tell application id \"$bundleId\" to activate"))

  /**
   * Main-display size in points. Uses the Finder desktop window bounds, which are reported in the
   * same point space as AX element bounds. Falls back to a 1440×900 default if unreadable.
   */
  fun mainDisplaySize(): DisplaySize {
    val out = runOsascript("tell application \"Finder\" to get bounds of window of desktop")
    // Format: "0, 0, 1920, 1080"
    val nums = out?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
    return if (nums != null && nums.size == 4) {
      DisplaySize(width = nums[2] - nums[0], height = nums[3] - nums[1])
    } else {
      Console.log("[MacOsAxAppResolver] couldn't read display size ('$out') — defaulting to 1440x900")
      DisplaySize(1440, 900)
    }
  }

  /**
   * True when the login session's screen is **locked**. macOS suppresses all AX *window* access
   * while locked (a security behavior) — every app's `AXWindows` returns empty and apps can't be
   * foregrounded — so the driver surfaces this as a clear, actionable error instead of a cryptic
   * "element not found" / windowless capture. Reads `CGSSessionScreenIsLocked` from the root
   * IORegistry session dict; absent key (older/unlocked sessions) reads as unlocked.
   */
  fun isScreenLocked(): Boolean {
    val out = try {
      val proc = ProcessBuilder("ioreg", "-n", "Root", "-d1", "-a").redirectErrorStream(false).start()
      val text = proc.inputStream.bufferedReader().readText()
      proc.waitFor(3, TimeUnit.SECONDS)
      text
    } catch (e: Exception) {
      return false
    }
    // Plist shape: `<key>CGSSessionScreenIsLocked</key><true/>` (or `<false/>`, or key absent).
    val after = out.substringAfter("CGSSessionScreenIsLocked", missingDelimiterValue = "")
    return after.take(30).contains("<true/>")
  }

  private fun runOsascript(script: String): String? = try {
    val proc = ProcessBuilder("osascript", "-e", script).redirectErrorStream(false).start()
    val out = proc.inputStream.bufferedReader().readText()
    val finished = proc.waitFor(5, TimeUnit.SECONDS)
    if (!finished) { proc.destroyForcibly(); null }
    else if (proc.exitValue() == 0) out.trim().ifBlank { null } else null
  } catch (e: Exception) {
    Console.log("[MacOsAxAppResolver] osascript failed: ${e.message}")
    null
  }

  private fun shell(args: List<String>, timeoutSeconds: Long = 10): Boolean = try {
    val proc = ProcessBuilder(args).redirectErrorStream(true).start()
    if (proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) proc.exitValue() == 0
    else { proc.destroyForcibly(); false }
  } catch (e: Exception) {
    Console.log("[MacOsAxAppResolver] shell ${args.firstOrNull()} failed: ${e.message}")
    false
  }
}

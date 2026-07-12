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

  /** One on-screen app the caller could switch to. */
  data class RunningApp(val name: String, val bundleId: String?)

  @Volatile private var bundleIdCache: Pair<Long, Map<String, String>> = 0L to emptyMap()

  /**
   * The apps that currently own an on-screen window, front-to-back, with their bundle ids — what a
   * snapshot lists so you know what else is running and how to switch to it.
   *
   * Names come free from the window list (already read for z-order). Bundle ids need an AppleScript
   * round trip through System Events, and that costs ~1.8 SECONDS — measured: it nearly doubled the
   * cost of an active-app snapshot, eating most of the speed this scope exists to buy. So it's
   * cached hard. The set of running apps changes only when you launch or quit one, which is rare
   * next to how often a snapshot is taken; an app launched inside the window is still listed (its
   * name comes from the window list), just without its bundle id until the cache turns over.
   */
  fun onScreenApps(): List<RunningApp> {
    val names = MacOsAxNative.onScreenAppPids().map { it.ownerName }
    val byName = bundleIdsByAppName()
    return names.map { RunningApp(name = it, bundleId = byName[it]) }
  }

  private fun bundleIdsByAppName(): Map<String, String> {
    val (cachedAt, cached) = bundleIdCache
    val now = System.currentTimeMillis()
    if (cached.isNotEmpty() && now - cachedAt < BUNDLE_ID_CACHE_MS) return cached

    val script = """
      set out to ""
      tell application "System Events"
        repeat with p in (every process whose background only is false)
          try
            set out to out & (name of p) & "\t" & (bundle identifier of p) & "\n"
          end try
        end repeat
      end tell
      return out
    """.trimIndent()
    val parsed = runOsascript(script)
      ?.lineSequence()
      ?.mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
      }
      ?.toMap()
      .orEmpty()
    if (parsed.isNotEmpty()) bundleIdCache = now to parsed
    return parsed
  }

  private const val BUNDLE_ID_CACHE_MS = 60_000L

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

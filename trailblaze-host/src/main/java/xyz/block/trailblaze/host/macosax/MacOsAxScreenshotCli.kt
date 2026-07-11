package xyz.block.trailblaze.host.macosax

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Screenshot capture for the macOS AX driver via the built-in `/usr/sbin/screencapture` system
 * binary (zero-install, unlike the iOS AXe path's Homebrew dependency).
 *
 * v1 captures the **whole main display**, not a window crop. This is deliberate: macOS AX
 * element bounds (`AXFrame`/`AXPosition`) are in *global* screen coordinates (top-left origin),
 * so a full-display image keeps the screenshot and the [TrailblazeNode] bounds in the **same**
 * coordinate space — set-of-mark annotations then line up by a simple pixels-per-point scale
 * with no per-window origin offset. Window-scoped capture + bounds translation, and
 * multi-display handling, are noted as follow-ups in the driver plan.
 *
 * Requires the **Screen Recording** permission for whatever process runs the JVM (the same
 * permission surfaced earlier when setting up VNC). Without it, `screencapture` succeeds but
 * produces a desktop-only image; capture never throws — a failure just yields null.
 */
object MacOsAxScreenshotCli {

  private const val SCREENCAPTURE_BIN = "/usr/sbin/screencapture"

  /**
   * Captures the main display as PNG bytes, or null on failure.
   *
   * `-x` silences the shutter sound, `-t png` forces PNG, `-C` excludes the cursor so it can't
   * be mistaken for UI, `-o` omits window shadow (no-op for full-display but harmless).
   */
  fun captureMainDisplay(timeoutSeconds: Long = 10): ByteArray? {
    val tmp = Files.createTempFile("macosax-screen-", ".png").toFile()
    return try {
      val proc = ProcessBuilder(SCREENCAPTURE_BIN, "-x", "-C", "-t", "png", tmp.absolutePath)
        .redirectErrorStream(true)
        .start()
      val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        System.err.println("[MacOsAxScreenshotCli] screencapture timed out after ${timeoutSeconds}s")
        return null
      }
      if (proc.exitValue() != 0) {
        System.err.println("[MacOsAxScreenshotCli] screencapture exited ${proc.exitValue()}")
        return null
      }
      if (!tmp.exists() || tmp.length() == 0L) null else tmp.readBytes()
    } catch (e: Exception) {
      System.err.println("[MacOsAxScreenshotCli] screencapture failed: ${e.message}")
      null
    } finally {
      runCatching { tmp.delete() }
    }
  }

  /** Reports whether the `screencapture` binary is present and executable. */
  fun isAvailable(): Boolean = File(SCREENCAPTURE_BIN).canExecute()
}

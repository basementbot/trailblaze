package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.host.macosax.MacOsAxEventSynthesizer
import xyz.block.trailblaze.host.macosax.MacOsAxScreenshotCli
import xyz.block.trailblaze.host.screenstate.MacOsAxScreenState
import xyz.block.trailblaze.host.util.BufferedImageUtils.scale
import xyz.block.trailblaze.host.util.BufferedImageUtils.toByteArray
import xyz.block.trailblaze.recording.DeviceScreenStream
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * [DeviceScreenStream] for the macOS desktop Accessibility driver's whole-screen device (the
 * `desktop/all` "macOS Desktop (all windows)" entry in the Live Device Viewer).
 *
 * - Frames are full main-display screenshots ([MacOsAxScreenshotCli.captureMainDisplay]).
 * - The hierarchy is the stitched whole-desktop AX tree ([MacOsAxScreenState] with
 *   `wholeScreen = true`) — the same capture the CLI snapshot and trail runs produce.
 * - Input is synthesized via `CGEvent`s ([MacOsAxEventSynthesizer]).
 *
 * All coordinates are **global screen points** (top-left origin), which is the same space the
 * screenshot and the AX `bounds` live in — so taps forwarded from the mirror land where the user
 * clicked with no per-window offset. This mirrors the whole-screen model of the iOS/Android/Web
 * device streams.
 *
 * [deviceWidth]/[deviceHeight] are the main display size in **points** (not screenshot pixels — the
 * screenshot is 2× on Retina); the viewer scales the mirror to fit and maps taps back through these.
 */
class MacOsAxDeviceScreenStream(
  override val deviceWidth: Int,
  override val deviceHeight: Int,
  private val frameIntervalMs: Long = 500,
) : DeviceScreenStream {

  override fun frames(): Flow<ByteArray> = flow {
    while (currentCoroutineContext().isActive) {
      mirrorFrame()?.let { emit(it) }
      delay(frameIntervalMs)
    }
  }

  // Recording captures need full fidelity (they pair with the AX tree for selector generation), so
  // return the raw full-resolution PNG here.
  override suspend fun getScreenshot(): ByteArray =
    MacOsAxScreenshotCli.captureMainDisplay() ?: ByteArray(0)

  // Mirror frames only need to be *looked at*: downscale + JPEG-compress so a Retina 4K desktop
  // isn't shipped as a ~7 MB PNG per frame — that size floods the multiplexed WebSocket and starves
  // concurrent RPCs (the Tool Palette's tool-catalog fetch was taking ~20s behind a frame push).
  // Coordinates are unaffected: the viewer maps taps through deviceWidth/deviceHeight (points), not
  // the frame's pixel size. Also skips the expensive whole-desktop AX walk that getViewHierarchy does.
  override suspend fun getMirrorScreenshot(): ByteArray = mirrorFrame() ?: ByteArray(0)

  private fun mirrorFrame(): ByteArray? {
    val raw = MacOsAxScreenshotCli.captureMainDisplay() ?: return null
    return runCatching {
      val image = ImageIO.read(ByteArrayInputStream(raw)) ?: return raw
      image.scale(MIRROR_MAX_DIMENSION_PX, MIRROR_MAX_DIMENSION_PX)
        .toByteArray(TrailblazeImageFormat.JPEG, compressionQuality = 0.6f)
    }.getOrDefault(raw)
  }

  // Fresh capture per call — the desktop changes continuously and the viewer only asks for the
  // tree on demand (overlay toggle / tap-to-inspect), not per frame.
  private fun freshScreenState(): MacOsAxScreenState = MacOsAxScreenState(
    pid = 0,
    deviceWidth = deviceWidth,
    deviceHeight = deviceHeight,
    wholeScreen = true,
  )

  override suspend fun getViewHierarchy(): ViewHierarchyTreeNode = freshScreenState().viewHierarchy

  override suspend fun getTrailblazeNodeTree(): TrailblazeNode? = freshScreenState().trailblazeNodeTree

  override suspend fun tap(x: Int, y: Int) {
    MacOsAxEventSynthesizer.click(x, y)
  }

  // macOS AX has no distinct long-press primitive; a click covers activation either way.
  override suspend fun longPress(x: Int, y: Int) {
    MacOsAxEventSynthesizer.click(x, y)
  }

  override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long?) {
    // Map a drag to a scroll at the start point — the common intent on desktop is scrolling
    // content. Scroll delta is the gesture delta (content follows the finger).
    MacOsAxEventSynthesizer.scroll(startX, startY, endX - startX, endY - startY)
  }

  override suspend fun inputText(text: String) {
    MacOsAxEventSynthesizer.typeText(text)
  }

  override suspend fun pressKey(key: String) {
    val code = when (key) {
      "Enter" -> MacOsAxEventSynthesizer.KEY_CODE_RETURN
      "Backspace" -> MacOsAxEventSynthesizer.KEY_CODE_DELETE
      "Tab" -> MacOsAxEventSynthesizer.KEY_CODE_TAB
      "Escape" -> MacOsAxEventSynthesizer.KEY_CODE_ESCAPE
      else -> return
    }
    MacOsAxEventSynthesizer.pressKeyCode(code)
  }

  private companion object {
    // Longest-side cap for mirror frames. A 4K desktop scales to ~1600×900 — plenty for a live
    // preview, and ~40× smaller than the full-resolution PNG.
    const val MIRROR_MAX_DIMENSION_PX = 1600
  }
}

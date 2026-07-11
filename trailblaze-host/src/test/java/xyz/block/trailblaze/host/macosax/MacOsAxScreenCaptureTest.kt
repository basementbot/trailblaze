package xyz.block.trailblaze.host.macosax

import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.util.Console

/**
 * Verifies whole-screen capture: [MacOsAxTreeWalker.captureScreen] enumerates every app with an
 * on-screen window and stitches their AX trees under one synthetic desktop root — matching the
 * whole-screen model of the iOS/Android/Web drivers. Lenient (skips when not AX-trusted / no
 * on-screen apps, e.g. a locked screen).
 */
class MacOsAxScreenCaptureTest {

  @Test
  fun captureEntireScreen() {
    if (!MacOsAxNative.isProcessTrusted()) { Console.log("[screen] not AX-trusted — skipping"); return }
    val apps = MacOsAxNative.onScreenAppPids()
    Console.log("[screen] on-screen apps (${apps.size}): ${apps.map { "${it.ownerName}(${it.pid})" }}")
    if (apps.isEmpty()) { Console.log("[screen] no on-screen windows (locked screen?) — skipping"); return }

    val tree = MacOsAxTreeWalker.captureScreen()
    val root = tree.driverDetail as DriverNodeDetail.MacOsAx
    Console.log("[screen] root role=${root.role} title=${root.stringAttribute("AXTitle")}, top-level apps=${tree.children.size}, total nodes=${tree.aggregate().size}")

    tree.children.forEach { appNode ->
      val d = appNode.driverDetail as? DriverNodeDetail.MacOsAx
      val nodes = appNode.aggregate().size
      val windows = appNode.aggregate().count { (it.driverDetail as? DriverNodeDetail.MacOsAx)?.role == "AXWindow" }
      val buttons = appNode.aggregate().count { (it.driverDetail as? DriverNodeDetail.MacOsAx)?.role == "AXButton" }
      Console.log("[screen]   app '${d?.stringAttribute("AXTitle")}' pid=${d?.pid} nodes=$nodes windows=$windows buttons=$buttons")
    }

    // Sanity: the assembled tree spans multiple apps and has real window content.
    check(tree.children.isNotEmpty()) { "expected at least one on-screen app" }
    val totalWindows = tree.aggregate().count { (it.driverDetail as? DriverNodeDetail.MacOsAx)?.role == "AXWindow" }
    Console.log("[screen] total AXWindows across the desktop = $totalWindows")
  }
}

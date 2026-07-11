package xyz.block.trailblaze.cli

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers the `trailblaze run` device-resolution glue for the macOS AX driver (see
 * docs/devlog/2026-07-10-macos-ax-driver.md): a MACOS_AX trail synthesizes a virtual "device"
 * keyed by the target app's bundle id (there's no adb/xcrun scan for a desktop app), and
 * `--device desktop/<bundleId>` must resolve to it.
 */
class TrailCommandMacOsAxDeviceResolutionTest {

  private fun macOsAxSummary(bundleId: String) = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.MACOS_AX,
    instanceId = bundleId,
    description = "macOS app ($bundleId)",
  )

  @Test
  fun driverKeyResolvesFromYamlKey() {
    // A trail's `config.driver: macos-ax` (the hyphenated yamlKey) must resolve to MACOS_AX,
    // as must the enum name form.
    assertEquals(TrailblazeDriverType.MACOS_AX, TrailblazeDriverType.fromString("macos-ax"))
    assertEquals(TrailblazeDriverType.MACOS_AX, TrailblazeDriverType.fromString("MACOS_AX"))
  }

  // A macOS AX trail — its `platform: desktop` / `driver: macos-ax` config is what
  // resolveRunDevice reads (via supportedPlatformsForTrail) to scope device resolution.
  private val macOsAxTrailYaml = """
    - config:
        platform: desktop
        driver: macos-ax
    - prompts:
      - step: noop
  """.trimIndent()

  @Test
  fun resolvesMacOsAxDeviceByFullyQualifiedSpec() {
    val cmd = TrailCommand()
    val devices = listOf(macOsAxSummary("com.apple.calculator"))
    val resolved = cmd.resolveRunDevice(
      yamlContent = macOsAxTrailYaml,
      connectedDevices = devices,
      trailDriverType = TrailblazeDriverType.MACOS_AX,
      deviceSpec = "desktop/com.apple.calculator",
    )
    assertIs<CliRunDeviceResolution.Selected>(resolved)
    assertEquals("com.apple.calculator", resolved.device.trailblazeDeviceId.instanceId)
    assertEquals(TrailblazeDriverType.MACOS_AX, resolved.device.trailblazeDriverType)
  }

  @Test
  fun resolvesMacOsAxDeviceByDriverTypeWhenNoSpec() {
    val cmd = TrailCommand()
    val devices = listOf(macOsAxSummary("com.apple.TextEdit"))
    // No --device: the single synthesized MACOS_AX device is auto-selected by driver type.
    val resolved = cmd.resolveRunDevice(
      yamlContent = macOsAxTrailYaml,
      connectedDevices = devices,
      trailDriverType = TrailblazeDriverType.MACOS_AX,
      deviceSpec = null,
    )
    assertIs<CliRunDeviceResolution.Selected>(resolved)
    assertEquals("com.apple.TextEdit", resolved.device.trailblazeDeviceId.instanceId)
  }
}

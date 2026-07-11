package xyz.block.trailblaze.host.devices

import maestro.DeviceInfo
import maestro.Driver
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider

/**
 * A connected device the daemon can drive.
 *
 * Sealed so new driver backends can declare their own native state (e.g. the AXe CLI on
 * iOS Simulator) without being forced through the Maestro-shaped API of
 * [MaestroConnectedDevice]. Screen dimensions are hoisted to the common base because
 * every consumer needs them for layout/classification; driver-specific accessors live on
 * the concrete subclasses and callers cast to reach them.
 */
sealed class TrailblazeConnectedDevice(
  val trailblazeDriverType: TrailblazeDriverType,
  val instanceId: String,
) {
  abstract val deviceWidth: Int
  abstract val deviceHeight: Int

  val trailblazeDeviceId: TrailblazeDeviceId = TrailblazeDeviceId(
    instanceId = instanceId,
    trailblazeDevicePlatform = trailblazeDriverType.platform,
  )
}

/**
 * Maestro-backed connected device — the current default path for iOS Simulator / real
 * devices (via XCUITest) and Android (via UiAutomator). Exposes the raw [Driver] for
 * consumers that still need Maestro-native operations (live screen streaming, Orchestra
 * command execution).
 */
class MaestroConnectedDevice(
  private val maestroDriver: Driver,
  trailblazeDriverType: TrailblazeDriverType,
  instanceId: String,
) : TrailblazeConnectedDevice(trailblazeDriverType, instanceId) {

  val initialMaestroDeviceInfo: DeviceInfo = maestroDriver.deviceInfo()

  override val deviceWidth: Int = initialMaestroDeviceInfo.widthPixels
  override val deviceHeight: Int = initialMaestroDeviceInfo.heightPixels

  /** Returns the underlying Maestro driver for direct access (e.g., live preview streaming). */
  fun getMaestroDriver(): Driver = maestroDriver

  fun getLoggingDriver(
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
  ): LoggingDriver = LoggingDriver(
    delegate = maestroDriver,
    screenStateProvider = {
      HostMaestroDriverScreenState(
        maestroDriver = maestroDriver,
      )
    },
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )
}

/**
 * AXe-backed connected device (POC) — iOS Simulator only. Drives the simulator by
 * shelling out to the [AXe CLI](https://github.com/cameroncooke/AXe) rather than going
 * through Maestro/XCUITest. What this unlocks vs. the Maestro path:
 *
 *  - **AX role vocabulary** — every node carries an Apple AX `role` string (`AXButton`,
 *    `AXStaticText`, …), a human-readable `role_description`, and optional `subrole`.
 *    Maestro exposes an integer `elementType` enum instead.
 *  - **Custom accessibility actions** (e.g. `Copy name`, `Show other options`) —
 *    not surfaced anywhere on the Maestro path.
 *  - **AXHelp** tooltip text — same story.
 *
 * On snapshot latency, AXe and a warm in-daemon Maestro RPC are comparable (AXe's
 * `describe-ui` forks a subprocess per call; Maestro's XCTest HTTP runner stays warm).
 * This driver is a fidelity play, not a speed play.
 */
class AxeConnectedDevice(
  /** Simulator UDID that the `axe` binary targets via `--udid`. */
  val udid: String,
  override val deviceWidth: Int,
  override val deviceHeight: Int,
) : TrailblazeConnectedDevice(
  trailblazeDriverType = TrailblazeDriverType.IOS_AXE,
  instanceId = udid,
)

/**
 * macOS-desktop AX-backed connected device. Drives a running macOS app directly through Apple's
 * Accessibility (`AXUIElement`) APIs via JNA (see the driver plan in
 * `docs/devlog/2026-07-10-macos-ax-driver.md`).
 *
 * The target is identified by **bundle id** — the macOS analog of the iOS bundle id: stable,
 * authorable in a committed trail, launchable at any time (`open -b <bundleId>`), and
 * re-attachable to a running instance (bundleId → live pid at connect time). [pid] is the
 * currently-resolved process id (an internal detail, not authored). Screen dimensions are the
 * main-display size in AX points, matching the full-display screenshot's coordinate space.
 */
class MacOsAxConnectedDevice(
  /** Bundle id of the target app, e.g. `com.apple.calculator`. The [instanceId]. The sentinel
   *  [WHOLE_SCREEN_INSTANCE_ID] means "the whole desktop" — every on-screen app stitched. */
  val bundleId: String,
  /** The currently-resolved process id for [bundleId]. `0` / ignored for whole-screen. */
  val pid: Int,
  override val deviceWidth: Int,
  override val deviceHeight: Int,
) : TrailblazeConnectedDevice(
  trailblazeDriverType = TrailblazeDriverType.MACOS_AX,
  instanceId = bundleId,
) {
  /** True when this device captures the entire desktop (all on-screen apps) rather than one app. */
  val wholeScreen: Boolean get() = bundleId == WHOLE_SCREEN_INSTANCE_ID

  companion object {
    /**
     * Sentinel instance id (`--device desktop/all`) for the whole-desktop macOS device — the one
     * that shows up in the multi-device grid "when available", matching the whole-screen model of
     * the iOS/Android/Web drivers. Captures every on-screen app under one tree.
     */
    const val WHOLE_SCREEN_INSTANCE_ID = "all"
  }
}

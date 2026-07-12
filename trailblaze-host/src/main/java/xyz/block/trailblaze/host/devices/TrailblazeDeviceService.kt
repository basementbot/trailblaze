package xyz.block.trailblaze.host.devices

import maestro.Maestro
import maestro.device.Device
import maestro.device.DeviceService
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getMaestroOnDeviceSpecificPort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.host.axe.AxeJsonMapper
import xyz.block.trailblaze.host.toTrailblazeDevicePlatform
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

object TrailblazeDeviceService {

  /**
   * Cached connected devices list with time-bounded staleness.
   * Device discovery (`xcrun simctl list`) is expensive (~300-500ms) and serializes
   * on the CoreSimulator database lock, so we cache results for [CACHE_TTL_MS].
   */
  private const val CACHE_TTL_MS = 30_000L

  private data class DeviceCache(
    val devices: List<Device.Connected>,
    val timestamp: Long,
  )

  @Volatile private var cache: DeviceCache? = null

  private val cachedConnectedDevices: List<Device.Connected>
    get() {
      val now = System.currentTimeMillis()
      val current = cache
      if (current == null || now - current.timestamp > CACHE_TTL_MS) {
        return DeviceService.listConnectedDevices().also {
          cache = DeviceCache(it, now)
        }
      }
      return current.devices
    }

  /**
   * Gets the first connected iOS Device backed by the Maestro/XCUITest driver.
   *
   * @param appTarget Optional - Configuration for the target application under test
   */
  fun getConnectedIosDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? {
    val connectedDevice: Device.Connected = cachedConnectedDevices.firstOrNull {
      TrailblazeDeviceId(
        instanceId = it.instanceId,
        trailblazeDevicePlatform = it.platform.toTrailblazeDevicePlatform(),
      ) == trailblazeDeviceId
    } ?: return null
    val iosDriver: Maestro = HostIosDriverFactory.createIOS(
      deviceId = connectedDevice.instanceId,
      openDriver = true,
      reinstallDriver = false,
      deviceType = connectedDevice.deviceType,
      driverHostPort = trailblazeDeviceId.getMaestroOnDeviceSpecificPort(),
      platformConfiguration = null,
      appTarget = appTarget,
    )
    return MaestroConnectedDevice(
      maestroDriver = iosDriver.driver,
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      instanceId = connectedDevice.instanceId,
    )
  }

  /**
   * Gets a connected iOS Simulator via the AXe CLI. Simulator-only by design — AXe uses
   * Apple's private Accessibility APIs which are not available on real devices.
   */
  fun getConnectedIosAxeDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDevice? {
    if (!AxeCli.isAvailable()) {
      System.err.println("axe binary not found. Install it with: brew install cameroncooke/axe/axe")
      return null
    }
    val udid = trailblazeDeviceId.instanceId

    // Bounds come from the AXe root `AXApplication` frame — the `application` element is
    // sized to the screen. Cheaper than calling `xcrun simctl` and doesn't require an
    // extra subprocess.
    val describe = AxeCli.describeUi(udid)
    if (!describe.success) {
      System.err.println("[IOS_AXE] axe describe-ui failed for $udid: ${describe.stderr.trim()}")
      return null
    }
    val tree = try {
      AxeJsonMapper.parse(describe.stdout)
    } catch (e: Exception) {
      System.err.println("[IOS_AXE] axe describe-ui produced unparseable JSON for $udid: ${e.message}")
      return null
    }
    val bounds = tree.bounds ?: run {
      System.err.println("[IOS_AXE] axe describe-ui returned no bounds for root — can't resolve device size")
      return null
    }

    return AxeConnectedDevice(
      udid = udid,
      deviceWidth = bounds.width,
      deviceHeight = bounds.height,
    )
  }

  /**
   * Gets a connected macOS desktop app via the Apple Accessibility (AX) driver. The
   * [TrailblazeDeviceId.instanceId] is the target app's **bundle id** (e.g.
   * `com.apple.calculator`) — the macOS analog of the iOS bundle id. Launches the app if it
   * isn't already running, resolves its live pid, and reads the main-display size for bounds.
   */
  fun getConnectedMacOsAxDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDevice? {
    if (!xyz.block.trailblaze.host.macosax.MacOsAxNative.isProcessTrusted()) {
      System.err.println(
        "[MACOS_AX] this process is not Accessibility-trusted. Grant it in System Settings → " +
          "Privacy & Security → Accessibility, then retry.",
      )
      return null
    }
    if (xyz.block.trailblaze.host.macosax.MacOsAxAppResolver.isScreenLocked()) {
      System.err.println(
        "[MACOS_AX] the Mac's screen is locked — macOS blocks Accessibility access to app windows " +
          "while locked. Unlock the screen and retry.",
      )
      return null
    }
    val bundleId = trailblazeDeviceId.instanceId
    val display = xyz.block.trailblaze.host.macosax.MacOsAxAppResolver.mainDisplaySize()

    // Desktop-scoped devices name no process, so their pid is a scope SENTINEL (see
    // MacOsAxTreeWalker): 0 = every on-screen app, -1 = whichever app is frontmost right now.
    // Nothing to launch or attach to in either case.
    if (bundleId == MacOsAxConnectedDevice.WHOLE_SCREEN_INSTANCE_ID) {
      return MacOsAxConnectedDevice(
        bundleId = bundleId,
        pid = xyz.block.trailblaze.host.macosax.MacOsAxTreeWalker.PID_ALL_APPS,
        deviceWidth = display.width,
        deviceHeight = display.height,
      )
    }
    if (bundleId == MacOsAxConnectedDevice.FRONTMOST_INSTANCE_ID) {
      return MacOsAxConnectedDevice(
        bundleId = bundleId,
        // Resolved per capture, not here: the frontmost app changes as the run drives the machine.
        pid = xyz.block.trailblaze.host.macosax.MacOsAxTreeWalker.PID_FRONTMOST_APP,
        deviceWidth = display.width,
        deviceHeight = display.height,
      )
    }

    // Single-app device (`desktop/<bundleId>`): launch/attach to the app.
    val pid = xyz.block.trailblaze.host.macosax.MacOsAxAppResolver.ensureRunning(bundleId)
    if (pid == null) {
      System.err.println("[MACOS_AX] could not launch/resolve a running pid for bundle id '$bundleId'")
      return null
    }
    return MacOsAxConnectedDevice(
      bundleId = bundleId,
      pid = pid,
      deviceWidth = display.width,
      deviceHeight = display.height,
    )
  }

  fun listConnectedTrailblazeDevices(): Set<TrailblazeDeviceId> {
    return cachedConnectedDevices.map {
      TrailblazeDeviceId(
        instanceId = it.instanceId,
        trailblazeDevicePlatform = it.platform.toTrailblazeDevicePlatform(),
      )
    }.toSet()
  }

  fun getConnectedDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    driverType: TrailblazeDriverType,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? = when (trailblazeDeviceId.trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID -> {
      // Android drivers (instrumentation + accessibility) communicate via the on-device RPC
      // server (`OnDeviceRpcClient` over a dadb-bridged HTTP port), not via a host-side
      // Maestro driver. There's no `MaestroConnectedDevice` to construct here — the
      // recording tab's Android path takes a different shape that doesn't go through this
      // method. Returning null is the correct contract for "no host-side Maestro device";
      // the recording tab interprets that and routes through the on-device path instead.
      null
    }
    TrailblazeDevicePlatform.IOS -> when (driverType) {
      TrailblazeDriverType.IOS_AXE -> getConnectedIosAxeDevice(trailblazeDeviceId)
      else -> getConnectedIosDevice(
        trailblazeDeviceId = trailblazeDeviceId,
        appTarget = appTarget,
      )
    }

    TrailblazeDevicePlatform.WEB -> error(
      "Web tests use PLAYWRIGHT_NATIVE path (BasePlaywrightNativeTest), not TrailblazeDeviceService"
    )

    // DESKTOP carries two driver types (mirrors IOS's IOS_HOST/IOS_AXE split):
    //  - MACOS_AX drives a running macOS app via the Apple Accessibility APIs (bundle-id target).
    //  - COMPOSE communicates via ComposeRpcClient, not this Maestro/host-driver fan-out; null =
    //    no Maestro device backing this CLI invocation, the RPC path takes over (same as ANDROID).
    TrailblazeDevicePlatform.DESKTOP -> when (driverType) {
      TrailblazeDriverType.MACOS_AX -> getConnectedMacOsAxDevice(trailblazeDeviceId)
      else -> null
    }
  }
}

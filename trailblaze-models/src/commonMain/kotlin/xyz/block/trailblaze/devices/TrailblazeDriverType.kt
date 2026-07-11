package xyz.block.trailblaze.devices

enum class TrailblazeDriverType(
  val platform: TrailblazeDevicePlatform,
  /**
   * Whether this driver requires a host machine to operate. Drivers with `requiresHost = false`
   * (e.g., on-device Android drivers) can run autonomously on the device via RPC; drivers with
   * `requiresHost = true` need a host-resident process (Maestro, Playwright, Revyl API, etc.).
   */
  val requiresHost: Boolean,
  /**
   * The YAML key used to reference this specific driver type in `trails/config/` YAML files
   * (targets, toolsets). Case-insensitive. Matches the keys in [DriverTypeKey].
   */
  val yamlKey: String,
  /**
   * Short identifier users type at the CLI to select this driver, e.g.
   * `trailblaze config android-driver accessibility`. `null` for drivers that aren't
   * user-selectable as a per-platform override (web drivers, Revyl cloud drivers). Kept
   * distinct from [yamlKey] because the CLI form drops the platform prefix — you already
   * know the platform from the config key (`android-driver` vs. `ios-driver`).
   */
  val cliShortName: String?,
) {
  ANDROID_ONDEVICE_ACCESSIBILITY(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-ondevice-accessibility",
    cliShortName = "accessibility",
  ),
  ANDROID_ONDEVICE_INSTRUMENTATION(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-ondevice-instrumentation",
    cliShortName = "instrumentation",
  ),
  IOS_HOST(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "ios-host",
    cliShortName = "host",
  ),
  IOS_AXE(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "ios-axe",
    cliShortName = "axe",
  ),
  PLAYWRIGHT_NATIVE(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "playwright-native",
    cliShortName = null,
  ),
  PLAYWRIGHT_ELECTRON(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "playwright-electron",
    cliShortName = null,
  ),
  REVYL_ANDROID(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = true,
    yamlKey = "revyl-android",
    cliShortName = null,
  ),
  REVYL_IOS(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "revyl-ios",
    cliShortName = null,
  ),
  // The Compose desktop driver. Bound to TrailblazeDevicePlatform.DESKTOP. Previously
  // bound to WEB as a workaround because adding DESKTOP would have required touching
  // every exhaustive `when` on the platform enum — that surgery has now landed, so
  // DESKTOP is the correct platform here.
  COMPOSE(
    platform = TrailblazeDevicePlatform.DESKTOP,
    requiresHost = true,
    yamlKey = "compose",
    cliShortName = null,
  ),

  // The macOS desktop Accessibility (AX) driver. Like COMPOSE it targets DESKTOP, but instead
  // of the in-process Compose RPC server it drives *any* running macOS app by reading its
  // AXUIElement tree directly via JNA (see docs/devlog/2026-07-10-macos-ax-driver.md). This is
  // the second driver type on DESKTOP, mirroring how IOS carries both IOS_HOST and IOS_AXE.
  // cliShortName "macos-ax" doesn't collide with iOS's "axe".
  MACOS_AX(
    platform = TrailblazeDevicePlatform.DESKTOP,
    requiresHost = true,
    yamlKey = "macos-ax",
    cliShortName = "macos-ax",
  ),
  ;

  companion object {
    val DEFAULT_ANDROID = ANDROID_ONDEVICE_ACCESSIBILITY
    val DEFAULT_IOS = IOS_HOST
    val DEFAULT_DESKTOP = COMPOSE

    val ANDROID_ON_DEVICE_DRIVER_TYPES = setOf(
      ANDROID_ONDEVICE_INSTRUMENTATION,
      ANDROID_ONDEVICE_ACCESSIBILITY,
    )

    /**
     * The driver type used when the user hasn't set an explicit per-platform override.
     * Returns `null` for platforms that don't have a user-togglable default (e.g. `WEB`).
     */
    fun defaultForPlatform(platform: TrailblazeDevicePlatform): TrailblazeDriverType? =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> DEFAULT_ANDROID
        TrailblazeDevicePlatform.IOS -> DEFAULT_IOS
        else -> null
      }

    /**
     * Drivers that the CLI exposes as a per-platform override via `config <platform>-driver`.
     * Determined by [cliShortName] being non-null, so adding a new user-selectable driver to
     * the enum automatically surfaces it in the CLI — no second list to keep in sync.
     */
    fun selectableForPlatform(platform: TrailblazeDevicePlatform): List<TrailblazeDriverType> =
      entries.filter { it.platform == platform && it.cliShortName != null }

    /**
     * Resolves a driver type from either its enum [name] (`MACOS_AX`) or its [yamlKey]
     * (`macos-ax`). Name is tried first for backward compatibility; the yamlKey fallback lets
     * trail `config.driver:` values (which use the hyphenated yamlKey convention, e.g.
     * `ios-axe`, `macos-ax`) resolve without the caller having to normalize them first.
     */
    fun fromString(value: String): TrailblazeDriverType? =
      entries.find { it.name.equals(value, ignoreCase = true) }
        ?: entries.find { it.yamlKey.equals(value, ignoreCase = true) }
  }
}

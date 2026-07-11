---
title: "macOS Desktop AX Driver — Implementation Plan"
type: plan
date: 2026-07-10
status: in-progress
branch: macos
---

# macOS Desktop AX Driver

Goal: let Trailblaze drive **any macOS app** (not just the Compose-desktop-app-under-test) by
capturing the real macOS Accessibility (AX) tree via `AXUIElement` APIs, paired with a
screenshot — the same shape as the existing `iosAxe` driver, so trails can be authored against
a live desktop app the same way they're authored against an iOS simulator today.

This file is the working checklist for that build. Update checkboxes as work lands; append a
`## Log` entry per session with what changed and what's next, so the plan stays a source of
truth across sessions rather than drifting from reality.

## Prior art this mirrors

Everything here is modeled directly on the iOS `axe` driver (`trailblaze-host/.../host/axe/*`,
`trailblaze-models/.../api/DriverNodeDetail.kt`'s `IosAxe` variant). Read those first — this
driver is a near-mechanical port to a different capture mechanism and action set.

## Key architecture decisions (made up front so implementation doesn't stall on them)

0. **Full attribute fidelity, not a fixed field subset — and 100% exact native macOS AX
   terminology throughout, no trimming/renaming/normalizing for anyone's convenience.** Unlike
   iOS (where AXe already normalizes down to a small fixed vocabulary: role, subrole, label,
   value, etc.), real macOS AX elements expose a large, open-ended, per-role attribute set —
   confirmed live via the Phase 0 spike's `spikeFullAttributeFidelity` test: Finder's app
   element exposes **20** attributes (`AXFrontmost`, `AXExtrasMenuBar`, `AXFocusedWindow`,
   `AXMenuBar`, ...), its window exposes a barely-overlapping **30** (`AXFullScreen`,
   `AXMinimizeButton`, `AXProxy`, `AXGrowArea`, `AXSections`, `AXDefaultButton`, `AXIdentifier`,
   ...). A table, an outline view, a slider, a text field will each expose yet another distinct
   set. The whole point of `TrailblazeNode`'s per-driver `driverDetail` design is capturing
   100% of driver fidelity for matching — so `DriverNodeDetail.MacOsAx` must **not** hardcode a
   trimmed field list, and must **not** rename native AX vocabulary into some genericized
   cross-platform-looking shape. Native first. Concretely, in `trailblaze-models`:
   - `DriverNodeDetail.MacOsAx` (naming convention: `MacOs` prefix throughout, mirroring how
     this codebase already renders "iOS" as `Ios` in `IosAxe`/`IosMaestro` — so "macOS" →
     `MacOs`, and this AX-based driver is `MacOsAx`, applied consistently to every class below,
     not a mix of `MacAx`/`Mac`/`AX` prefixes):
     `data class MacOsAx(val pid: Int, val attributes: Map<String, MacOsAxAttributeValue>,
     val actions: List<String>, val parameterizedAttributeNames: List<String>)`. `pid` is the
     one field that isn't itself AX vocabulary (it comes from `AXUIElementCreateApplication`'s
     own argument, not an attribute the element exposes), so it's fine as a plain top-level
     field. Everything that *is* AX vocabulary — `AXRole`, `AXSubrole`, `AXTitle`, `AXValue`,
     `AXIdentifier`, all of it — lives **only** in `attributes`, keyed by the exact, unmodified
     `AX*` string macOS itself uses (no stripping the `AX` prefix, no camelCasing, no pulling
     `AXRole` out into a separately-named `role`/`axRole` convenience field that could drift
     from the map or imply a normalized/reinterpreted concept). Any matching-logic code that
     needs the role reads `attributes["AXRole"]`, same as everything else — consistent with
     this driver's philosophy of zero abstraction between what macOS reports and what
     Trailblaze stores.
   - `attributes: Map<String, MacOsAxAttributeValue>` — **every** name returned by
     `AXUIElementCopyAttributeNames`, each resolved via `AXUIElementCopyAttributeValue` and
     decoded into a small serializable discriminated union (`MacOsAxAttributeValue`, new type
     in `trailblaze-models`): `Str`, `Num`, `Bool`, `Arr(List<MacOsAxAttributeValue>)`, and for
     `AXValueRef`-wrapped structs (decoded via `AXValueGetType`+`AXValueGetValue` — the spike
     confirms `AXFrame`/`AXPosition`/`AXSize`/`AXActivationPoint` all come back as this opaque
     struct-wrapper type, not plain numbers) — **field names mirror the real Core Graphics
     struct fields exactly**, not abbreviated: `Point(x: Double, y: Double)` (matches `CGPoint`),
     `Size(width: Double, height: Double)` (matches `CGSize` — `width`/`height`, not `w`/`h`),
     `Rect(origin: Point, size: Size)` (matches `CGRect { origin: CGPoint; size: CGSize }`
     exactly, nested rather than flattened), `Range(location: Long, length: Long)` (matches
     `CFRange` exactly). Plus `ElementRef(role: String?, identifier: String?, title: String?)`
     for nested-`AXUIElement`-typed attributes (`AXParent`, `AXMinimizeButton`, `AXProxy`,
     `AXFocusedUIElement`, etc. — summarized by identity, **not** recursively expanded, both to
     avoid `AXParent`-induced infinite cycles and unbounded duplication; `AXChildren` is the one
     exception, excluded from this map entirely because it drives `TrailblazeNode.children`
     structurally instead, same as every other driver), and `Unknown(cfTypeId: Long)` as a
     fidelity-preserving fallback for anything the decoder doesn't recognize yet (never silently
     drop — record that *something* was there).
   - `actions: List<String>` — full `AXUIElementCopyActionNames` result, **exact native action
     name strings** (`"AXRaise"`, `"AXPress"`, etc. — spike: window had `[AXRaise]`, app had
     `[]`, varies per element), never mapped onto some invented cross-platform action enum.
   - `parameterizedAttributeNames: List<String>` — names only for v1 (both spiked elements
     exposed `[AXReplaceRangeWithText]`); resolving parameterized attribute *values* needs a
     parameter to pass in, out of scope until something concrete needs it.
   - `Bounds` on the common `TrailblazeNode` gets derived from decoding `AXFrame` (preferred) or
     `AXPosition`+`AXSize`, same as every other attribute — no special-casing needed once
     `AXValue` struct decoding exists.
   - This naming discipline (`MacOs` prefix, zero renaming of native vocabulary) applies to
     every class in the file checklist below — `MacOsAxConnectedDevice`, `MacOsAxDeviceManager`,
     `MacOsAxAction`, `MacOsAxTrailRunner`, `MacOsAxTrailblazeAgent`, `MacOsAxScreenState`,
     `MacOsAxNative`, `MacOsAxTreeWalker`, `MacOsAxActionExecutor`, `MacOsAxScreenshotCli`,
     `MacOsAxStatus` — all under `host/macosax/` (renamed from the earlier draft's `host/macax/`
     for the same consistency reason). The Phase 0 spike file has already been renamed to match:
     `host/macosax/MacOsAxNativeSpikeTest.kt`.
1. **Capture mechanism: JNA-direct, not a companion CLI binary.**
   `trailblaze-host` already depends on JNA (`libs.jna`, `build.gradle.kts:280`) and already
   calls into the Objective-C runtime today — see `TrailblazeDesktopUtil.kt:442-468`
   (`MacOsApplication`, using `NativeLibrary.getInstance("objc")` +
   `objc_getClass`/`sel_registerName`/`objc_msgSend`). We extend that precedent to
   `ApplicationServices`/`HIServices` (`AXUIElementCreateApplication`,
   `AXUIElementCopyAttributeValue`, `AXUIElementCopyActionNames`, `AXUIElementPerformAction`,
   `AXUIElementSetAttributeValue` for text input, `CGWindowListCreateImage` or
   `screencapture -x` for screenshots). No external binary, no install step, no Homebrew
   dependency, unlike `axe`. This is a deliberate improvement over the iOS driver's POC
   Homebrew-binary approach, not just a mirror of it.
2. **Platform/driver-type: reuse `TrailblazeDevicePlatform.DESKTOP`, add
   `TrailblazeDriverType.MACOS_AX`.** `DESKTOP` today is 1:1 with the Compose RPC driver, but
   `TrailblazeDriverType` already models "one platform, multiple driver types" (`IOS` has both
   `IOS_HOST` and `IOS_AXE`). Same shape: `MACOS_AX(platform = DESKTOP, requiresHost = true,
   yamlKey = "macos-ax", cliShortName = "ax-mac")` (name TBD, must not collide with iOS's
   `"ax"` shortname). `TrailblazeDeviceService.getConnectedDevice`'s `DESKTOP` arm becomes a
   `when(driverType)` like `IOS`'s, instead of unconditionally returning null for Compose.
3. **Target selection: attach by running application, not simulator UDID.** AXe targets an iOS
   Simulator UDID; there's no equivalent concept on macOS. `MacOsAxConnectedDevice` identifies a
   target by **bundle ID or PID** of a running app (resolved via `NSWorkspace`/JNA at connect
   time), with the AX root being that app's `AXUIElementCreateApplication(pid)`.
4. **Permission model:** unlike AXe (needs Xcode CLT), this needs the **Accessibility**
   permission granted to whatever process runs the JVM (Terminal / the trailblaze CLI / the
   IDE), the same permission covered earlier this session in the bot-harness VNC work. Surface
   this clearly — a `AXIsProcessTrusted()` check at connect time with a specific, actionable
   error (not a generic "AX call failed"), mirroring `AxeCli`'s "not installed" message.

## File-by-file checklist

### trailblaze-models (shared data model — no macOS-specific code, pure Kotlin Multiplatform)

- [x] `api/DriverNodeDetail.kt` — added `MacOsAx` variant:
      `data class MacOsAx(val pid: Int, val attributes: Map<String, MacOsAxAttributeValue>,
      val actions: List<String>, val parameterizedAttributeNames: List<String>)`. See
      decision #0 — no fixed field list, exact native `AX*` keys in `attributes`, no
      renaming. `matchablePropertyNames`/`hasIdentifiableProperties`/`isInteractive`/
      `resolveText()` all read out of `attributes` (via a `stringAttribute(name)` helper), plus
      `MATCHABLE_ATTRIBUTES`/`IDENTITY_ATTRIBUTES`/`INTERACTIVE_ACTIONS`/`INTERACTIVE_ROLES`
      companion sets. `role` is a convenience getter for `attributes["AXRole"]` (read-only,
      no separate stored field).
- [x] `api/MacOsAxAttributeValue.kt` (new file) — the discriminated union described in
      decision #0: `Str`, `Num`, `Bool`, `Arr`, `Point(x, y)`, `Size(width, height)`,
      `Rect(origin: Point, size: Size)`, `Range(location, length)`,
      `ElementRef(role, identifier, title)`, `Unknown(cfTypeId)`. All `@Serializable` with
      `@SerialName` discriminators.
- [x] `api/TrailblazeNode.kt` — added `MacOsAx` arm to `describe()`
      (`resolveText() to detail.role`).
- [x] `api/CompactScreenElements.kt` — added `MacOsAxCompactElementList` +
      `CompactScreenElements.buildForMacOsAx(tree, screenHeight, screenWidth)`, mirroring
      `buildForIosAxe`.
- [x] `devices/TrailblazeDriverType.kt` — added `MACOS_AX` entry
      (`platform = DESKTOP, requiresHost = true, yamlKey = "macos-ax", cliShortName = "macos-ax"`).
- [x] `devices/TrailblazeDevicePlatform.kt` — `DESKTOP` doc comment updated to reflect it now
      covers two driver types (Compose RPC + macOS AX).
- [x] `api/TrailblazeNodeSelector.kt` / `DriverNodeMatch` — added `DriverNodeMatch.MacOsAx`
      (named `AX*` fields + an `attributeEquals: Map<String,String>` full-fidelity escape hatch
      for matching any native attribute), wired into `driverMatch`/`withMatch`/
      `toTrailblazeElementSelector`, and a `matchesMacOsAx` branch in
      `TrailblazeNodeSelectorResolver`.
- [x] **Downstream exhaustive-`when` fan-out** (not originally listed, but adding two sealed
      variants breaks every non-`else` `when` across the repo). Added `MacOsAx` branches to:
      `MatchDescriptorBuilder`, `TrailblazeNodeCompat`, `TrailblazeNodeSelectorQuality`,
      `TrailblazeNodeSelectorGeneratorEnumeration` (×2), `TrailblazeNodeSelectorGeneratorHelpers`
      (`buildTargetMatch`/`buildStructuralMatch`), `TrailblazeNodeSelectorMinimizer`
      (`minimizeMacOsAx` + `isEmpty`), new file
      `TrailblazeNodeSelectorGeneratorMacOsAx.kt` (`macOsAxStrategies` +
      `namedStructuralMacOsAxStrategies`) wired into `TrailblazeNodeSelectorGenerator` (×2);
      plus consumers `trailblaze-ui` (`InspectTrailblazeNodeComposable` with a full-fidelity
      `MacOsAxProperties` attribute dump, `WaypointVisualizerComposable`), `trailblaze-server`
      (`BridgeUiActionExecutor`), `trailblaze-host` (`WaypointMigrateTrailCommand`,
      `RecordRoutes`, `TrailblazeDeviceManager` — `MACOS_AX` grouped with `COMPOSE`'s
      "not-yet-supported" screen-state arm, to be filled in Phase 2/3/5).

### trailblaze-host (JVM host — new `host/macosax/` package, parallel to `host/axe/`)

- [x] `host/macosax/MacOsAxNative.kt` — the JNA layer.
      `NativeLibrary.getInstance("ApplicationServices")` (confirmed working directly in the
      Phase 0/spikeFullAttributeFidelity spike — no need for the HIServices sub-framework
      fallback in practice, though keep it as a defensive fallback), function pointers for
      `AXUIElementCreateApplication`, `AXUIElementCopyAttributeValue`,
      `AXUIElementCopyAttributeNames`, `AXUIElementCopyActionNames`,
      `AXUIElementCopyParameterizedAttributeNames`, `AXUIElementPerformAction`,
      `AXUIElementSetAttributeValue`, `AXUIElementCopyElementAtPosition`, `AXValueGetTypeID`,
      `AXValueGetType`, `AXValueGetValue`, `AXIsProcessTrusted`, plus the `CoreFoundation`
      helpers already proven in the spike (`CFStringCreateWithCString`/`CFStringGetCString`,
      `CFGetTypeID` + the per-type `CF*GetTypeID()` calls, `CFArrayGetCount`/
      `CFArrayGetValueAtIndex`, `CFRelease` — every `CFStringRef`/`CFArrayRef` this code creates
      or receives ownership of needs `CFRelease`'d; the spike doesn't bother since it's
      throwaway, the real implementation must not leak). This was the highest-risk/highest-
      unknown file going in; Phase 0 fully de-risked the JNA-to-CoreFoundation-to-AX round trip
      (see Log below) — what's left here is coverage (the fuller function list above) and
      correctness (release discipline, the `AXValueGetValue` struct decode), not open unknowns.
- [x] `host/macosax/MacOsAxTreeWalker.kt` — recursive walk from the app's root `AXUIElement`.
      Per element: `AXUIElementCopyAttributeNames` → for each name, resolve via
      `AXUIElementCopyAttributeValue` and decode through `MacOsAxAttributeValue` (decision #0)
      — **dynamic, not a hardcoded attribute list** (the spike's own `describeCfType`/`namesOf`
      helpers are the reference implementation to promote out of the throwaway test into this
      real file). `AXChildren` specifically drives recursion into `TrailblazeNode.children`
      rather than being decoded into `attributes`. `AXFrame`/`AXPosition`+`AXSize` (once
      decoded) populate `TrailblazeNode.bounds`. Also captures `AXUIElementCopyActionNames` and
      `AXUIElementCopyParameterizedAttributeNames` per element into the `MacOsAx` variant's
      `actions`/`parameterizedAttributeNames`. Builds `TrailblazeNode` directly (no intermediate
      JSON, unlike `AxeJsonMapper`, since there's no subprocess boundary to cross).
- [x] `host/macosax/MacOsAxActionExecutor.kt` — `tap` (via
      `AXUIElementPerformAction("AXPress")` *or* synthetic `CGEvent` click at bounds-center —
      decide per-element based on whether `"AXPress"` is in the element's `actions`, falling
      back to `CGEvent` for non-AX-actionable elements, same fallback pattern Maestro uses on
      Android/iOS), `type` (`AXUIElementSetAttributeValue("AXValue")` for text fields, or
      synthetic `CGEvent` keyboard events for apps that don't support direct value-setting),
      `swipe`/`scroll` (`CGEvent` scroll wheel events — AX has no native swipe concept on
      desktop), key press (`CGEvent` keyDown/keyUp).
- [x] `host/macosax/MacOsAxScreenshotCli.kt` — screenshot capture. **Implemented as full main-
      display capture** (`screencapture -x -C -t png`), NOT window-scoped `-l <windowID>`:
      macOS AX bounds are global screen coords, so a full-display image shares the node bounds'
      coordinate space and set-of-mark aligns by a plain pixels-per-point scale with no per-window
      origin offset. Window-scoped capture + bounds translation and multi-display are follow-ups.
      shell out to `/usr/sbin/screencapture -x -l <windowID>` (window-scoped, no shutter
      sound) via the same `ProcessBuilder` pattern as `AxeCli.kt`'s `screenshot()` method —
      no need to reinvent this in JNA/`CGWindowListCreateImage` given `screencapture` is a
      zero-install macOS system binary already used elsewhere this session.
- [x] `host/macosax/MacOsAxDeviceManager.kt` — mirrors `AxeDeviceManager.kt`:
      `execute(MacOsAxAction)`, `getScreenState()`, `captureTree()`, `waitForReady()`, selector
      resolution against a freshly-captured tree with a polling window (`awaitSelector`).
- [x] `host/macosax/MacOsAxAction.kt` — sealed action vocabulary: `Tap`, `TapOnElement(selector)`,
      `InputText`, `EraseText`, `Scroll(direction)`, `LaunchApp(bundleId)` (`open -b`),
      `ActivateApp`/`QuitApp` (`osascript`), `AssertVisible`/`AssertNotVisible`, `WaitForSettle`.
- [x] `host/macosax/MacOsAxEventSynthesizer.kt` (new — not in the original checklist) — the
      CoreGraphics `CGEvent` synthetic-HID layer: `click` (mouse down/up at a global point via
      `CGEventCreateMouseEvent` + a JNA `CGPoint` `Structure.ByValue`), `typeText` (unicode
      keyboard events), `pressKeyCode` (e.g. Delete/Backspace), `scroll` (scroll-wheel). This is
      the fallback for elements that aren't AX-actionable; the AX-native path is preferred.
      Compiled and wired; lightly exercised (the AX path won in the Calculator verification).
- [x] `host/macosax/MacOsAxTrailRunner.kt` — mirrors `AxeTrailRunner.kt` (sequential execute,
      error short-circuit → `TrailblazeToolResult`).
- [x] `host/macosax/MacOsAxTrailblazeAgent.kt` — extends `MaestroTrailblazeAgent`, mirrors
      `IosAxeTrailblazeAgent.kt`'s 5-method override + `usesAccessibilityDriver = true`.
      **VERIFIED at runtime** (`MacOsAxAgentTest`): `executeNodeSelectorTap(identifier="Seven")`
      against live Calculator → display "7". Plus `host/macosax/MaestroCommandToMacOsAxActionConverter.kt`
      (Maestro `Command` → `MacOsAxAction`; `text`→`AXTitle`, `id`→`AXIdentifier`).
- [x] `host/screenstate/MacOsAxScreenState.kt` — implements `ScreenState`, mirrors
      `AxeScreenState.kt` (lazy `parsedTree` via `MacOsAxTreeWalker.capture(pid)`,
      `compactElements` via `buildForMacOsAx`, `trailblazeNodeTree`, `screenshotBytes` via
      `MacOsAxScreenshotCli.captureMainDisplay()`).
- [x] `host/devices/TrailblazeConnectedDevice.kt` — added `MacOsAxConnectedDevice(bundleId, pid,
      deviceWidth, deviceHeight)`. **Target identified by bundle id** (the decided UX — the macOS
      analog of the iOS bundle id; pid resolved internally at connect).
- [x] `host/devices/TrailblazeDeviceService.kt` — added `getConnectedMacOsAxDevice(...)` (checks
      `MacOsAxNative.isProcessTrusted()`, resolves bundle id → pid via `MacOsAxAppResolver`
      launching if needed, main-display size for bounds), wired into `getConnectedDevice`'s
      `DESKTOP` arm (`when(driverType) { MACOS_AX -> …; else -> null }`). Plus new
      `host/macosax/MacOsAxAppResolver.kt` (bundle id → pid, launch/activate/quit, display size —
      all verified: `open -b` launches, System Events resolves the live pid, `osascript` quits).
- [x] MCP bridge wiring (`mcp/TrailblazeMcpBridgeImpl.kt`) — added `executeToolViaMacOsAx` (mirrors
      `executeToolViaAxe`: builds `MacOsAxDeviceManager` + `MacOsAxTrailblazeAgent` +
      `MacOsAxScreenState` per call), a `MACOS_AX` dispatch branch, and `MacOsAxConnectedDevice`
      arms in `getDirectScreenStateProvider` (×2). Also the `DevicesTab` connect `when` +
      `TrailblazeDeviceManager.getCurrentScreenState` MACOS_AX arm (now returns a real
      `MacOsAxScreenState`, no longer null-stubbed).
- [ ] Tool-availability UI: `MacOsAxStatus.kt` — **OPTIONAL / not done.** Cosmetic home-tab
      readiness display (sibling of `IosStatus`/`AdbStatus`). Not on any exhaustive `when`, so the
      build is green without it; the `AXIsProcessTrusted()` check already surfaces an actionable
      error at connect time (`getConnectedMacOsAxDevice`). Left as polish.

### trailblaze-server (MCP tool wiring)

- [ ] `mcp/agent/BridgeUiActionExecutor.kt` — add `MacOsAx` handling alongside the existing
      `AndroidCompactElementList`/`IosCompactElementList` pattern-matches.
- [ ] `mcp/newtools/StepToolSet.kt` — confirm `describeScreenState`/snapshot-detail flow works
      unmodified once `ScreenState` is implemented (per `ScreenState.kt`'s doc-comment caveat
      about Maestro-based drivers needing an override — macOS AX should NOT need this since
      it's not Maestro-based).

### CLI / trail config wiring (found during research, not yet fully explored — verify at

implementation time)

- [ ] Confirm `yamlKey = "macos-ax"` surfaces correctly in `trails/config/` target files and
      `config <platform>-driver` CLI output (per `TrailblazeDriverType.selectableForPlatform`
      filtering on non-null `cliShortName`).

## Phasing (build in this order — each phase should run from source and be manually verified before the next)

- [x] **Phase 0 — JNA AX spike (throwaway script, not wired into trailblaze yet).** Prove
      `AXUIElementCreateApplication` + `AXUIElementCopyAttributeValue` round-trips work from
      JNA against a real running app (e.g. TextEdit or Calculator), including `CFString`
      marshaling. This is the real unknown; everything else is mechanical Kotlin plumbing once
      this works. Requires the Accessibility permission granted to whatever process runs this
      spike (Terminal, per this session's earlier setup).
- [x] **Phase 1 — Data model.** `trailblaze-models` changes (compiles standalone, no runtime
      behavior yet). DONE — see the trailblaze-models checklist above and the Log entry below.
      `:trailblaze-models` compiles + `jvmTest` green; all downstream consumers
      (`:trailblaze-common`, `:trailblaze-server`, `:trailblaze-host`, `:trailblaze-android`,
      `:trailblaze-ui`) compile main + test sources clean after the exhaustive-`when` fan-out.
- [x] **Phase 2 — Tree capture, read-only.** `MacOsAxNative` + `MacOsAxTreeWalker` +
      `MacOsAxScreenState`. VERIFIED against live Finder (`MacOsAxCaptureTest`): captured **1232**
      nodes, root `AXRole=AXApplication`, **all 1232** nodes had decoded bounds, **1232** decoded
      an `AXFrame` `CGRect` (struct decode works), nested `AXUIElement`s summarized as `ElementRef`
      (no cycles), full native attribute fidelity, and the compact list renders a clean readable
      ref/role/title/id/subrole/action hierarchy. (`MacOsAxDeviceManager.captureTree()` deferred to
      Phase 5's device wiring — `MacOsAxScreenState` already provides capture standalone.)
- [x] **Phase 3 — Screenshot.** `MacOsAxScreenshotCli.captureMainDisplay()` (full-display, global-
      coord-aligned — see the file checklist note). Wired into `MacOsAxScreenState.screenshotBytes`.
- [x] **Phase 4 — Actions.** `MacOsAxActionExecutor` + `MacOsAxAction` + `MacOsAxEventSynthesizer`.
      VERIFIED end-to-end against live **Calculator** (`MacOsAxActionTest`): pressed `1 + 2 =`
      entirely through the real pipeline — `TrailblazeNodeSelector(macOsAx = identifier=…)` →
      `TrailblazeNodeSelectorResolver` (matching exact native `AXIdentifier`s "One"/"Add"/"Two"/
      "Equals") → `MacOsAxActionExecutor` → live hit-test → **AX-native `AXPress`** — then read the
      result back off a fresh AX capture and asserted `3`. Non-intrusive (AXPress doesn't move the
      cursor). This simultaneously validated Phase 1's selector/resolver/matcher code against a
      real macOS app. The synthetic-HID (`CGEvent`) path is implemented but was not the code path
      exercised (AX-native won), so it's lightly tested.
- [x] **Phase 5 — Agent + device-service wiring.** DONE (target-selection UX decided: **bundle
      id**). `MacOsAxDeviceManager` + `MacOsAxTrailRunner` (execution core), `MacOsAxAppResolver`
      (bundle id → pid/launch/quit + display size), `MacOsAxConnectedDevice`,
      `TrailblazeDeviceService.getConnectedMacOsAxDevice` + `DESKTOP` arm,
      `TrailblazeDeviceManager.getCurrentScreenState` MACOS_AX arm, MCP bridge
      `executeToolViaMacOsAx` + dispatch, `MacOsAxTrailblazeAgent` + converter. Agent entry point
      **verified at runtime** (`MacOsAxAgentTest`). Everything compiles across host+server+tests.
      Only optional `MacOsAxStatus` home-tab UI left. The full daemon→MCP→CLI round trip (config
      `desktop-driver macos-ax` + `--device desktop/<bundleId>`) is wired + compiled but not yet
      smoke-tested through a live daemon — the natural next manual check.
- [x] **Phase 6 — Author a real trail.** DONE via the execution core (`MacOsAxTrailEndToEndTest`,
      Calculator): authored a 5-step trail (`tap One → Add → Two → Equals → assertVisible
      AXStaticText value~"3"`) as `MacOsAxAction`s built from `TrailblazeNodeSelector`s over exact
      native `AX*` vocabulary, ran it through `MacOsAxTrailRunner → MacOsAxDeviceManager →
      MacOsAxActionExecutor → AXPress`, asserted the result via selector, and exercised
      `getScreenState()` (250-line compact list + 1.15 MB screenshot) — all green. This is the
      "real user" path minus the LLM-agent/CLI front door, which is the blocked Phase 5 remainder.

## Explicitly out of scope for v1

- Real-device (non-simulator-equivalent — N/A on macOS, but noting the analogy) or sandboxed
  App Store apps that restrict Accessibility API access.
- Multi-window / multi-display coordinate handling beyond "one focused window" — revisit once
  v1 works for the single-window case.
- Cross-platform text/role normalization in `DriverNodeDetail.MacOsAx` — matching `IosAxe`'s
  precedent of "raw platform vocabulary, no forced smoothing" (per `TrailblazeNode.kt`'s
  documented design philosophy), taken further than `IosAxe` per decision #0 (full dynamic
  attribute dictionary, not even a fixed field subset of the native vocabulary).
- Resolving parameterized attribute *values* (e.g. `AXReplaceRangeWithText`) — v1 only records
  their *names* per element for fidelity; invoking them needs a parameter, which is a real
  design question (where does the parameter come from? a future action type?) deferred until
  something concrete needs it.

## Log

- **2026-07-10**: Plan written after Explore-agent research into `iosAxe`/`TrailblazeDeviceService`/
  `TrailblazeNode` architecture. `macos` branch created off `origin/main` (24603778, the current
  tip — `develop` is 84 commits stale, not used as base). No code written yet; next session
  should start at Phase 0 (JNA spike).
- **2026-07-10 (same session, continued)**: Phase 0 spike done —
  `trailblaze-host/src/test/java/xyz/block/trailblaze/host/macosax/MacOsAxNativeSpikeTest.kt`
  (THROWAWAY, delete once Phase 2 lands a real `MacOsAxTreeWalker`). Ran via
  `./gradlew :trailblaze-host:test --tests "xyz.block.trailblaze.host.macosax.MacOsAxNativeSpikeTest" --info`
  against the real, already-running Finder process. Result: full success, no surprises —
  `NativeLibrary.getInstance("ApplicationServices")` resolves and exposes the AX symbols
  directly (no need for the HIServices sub-framework fallback path), `AXUIElementCreateApplication`
  + `AXUIElementCopyAttributeValue` + `CFStringCreateWithCString`/`CFStringGetCString` round-trip
  correctly (read back `AXTitle` = "Finder", `AXChildren` = 4 elements via `CFArrayGetCount`).
  **Bonus finding**: `AXIsProcessTrusted()` returned `true` for the Gradle test-worker JVM with
  *no* separate permission grant needed — it inherited trust via macOS's responsible-process
  tracking from Terminal (already Accessibility-trusted from earlier VNC work this session).
  Worth re-verifying this holds for a *fresh* Terminal/Gradle-daemon lifecycle (this was a
  warm daemon), but it means Phase 5's permission-check UX can likely be simpler than
  originally planned — the common case may just work.
- **2026-07-10 (same session, continued further)**: User feedback tightened decision #0
  significantly: capture **100% of native AX attribute fidelity**, not a fixed field subset
  mirroring `IosAxe` — and use **exact native macOS AX terminology** throughout (literal `AX*`
  attribute-name strings as map keys, real `CGRect`/`CGPoint`/`CGSize`/`CFRange` field names for
  decoded structs, no renaming/normalizing for ergonomics), plus a consistent `MacOs` class-name
  prefix everywhere (matching how this codebase already renders "iOS" as `Ios`). Validated the
  "attribute set is genuinely open-ended per role" premise with a second spike,
  `spikeFullAttributeFidelity` (same test file): Finder's app element → 20 attributes, its
  window → a barely-overlapping 30 (`AXFullScreen`, `AXMinimizeButton`, `AXProxy`, `AXGrowArea`,
  `AXSections`, `AXIdentifier`, ...), confirming a fixed field list would lose real data.
  Revised decision #0 and the whole file checklist in place (this file) to the
  `MacOsAx`/`MacOsAxAttributeValue`-based generic-attribute-map design, renamed
  `host/macax/` → `host/macosax/` throughout (including the already-written spike file, moved
  and renamed to `MacOsAxNativeSpikeTest.kt`, re-verified it still compiles and runs after the
  move). Task #2 (Phase 1) updated to reflect the corrected data model before any
  `trailblaze-models` code gets written, so Phase 1 doesn't start from a design that's already
  known to be wrong.
  Next: Phase 1 (trailblaze-models data model — `DriverNodeDetail.MacOsAx`,
  `MacOsAxAttributeValue`, `TrailblazeDriverType.MACOS_AX`, etc.), then fold the proven JNA
  patterns from the spike (including the `describeCfType`/`namesOf` full-attribute-walk helpers)
  into the real `MacOsAxNative.kt`/`MacOsAxTreeWalker.kt` (Phase 2).
- **2026-07-10 (same session, Phase 1 landed)**: Wrote the full trailblaze-models data model —
  `MacOsAxAttributeValue` (the fidelity-first CF/AXValue union), `DriverNodeDetail.MacOsAx`
  (dynamic `attributes: Map<String, MacOsAxAttributeValue>` keyed by exact `AX*` names, `pid`,
  `actions`, `parameterizedAttributeNames`), `DriverNodeMatch.MacOsAx` (named `AX*` matchers +
  `attributeEquals` escape hatch), `TrailblazeDriverType.MACOS_AX`, `MacOsAxCompactElementList` +
  `buildForMacOsAx`, and the `describe()`/resolver arms. Confirmed the earlier worry about scope:
  adding two sealed variants (`DriverNodeDetail` + `DriverNodeMatch`) broke **18** exhaustive
  `when`s across 6 modules (models, ui, server, host, +compiled android/common) — used the
  compiler as the driver, fixing each with a real `MacOsAx` branch (not throw-stubs) mirroring
  the `IosAxe` precedent, including a promoted selector-generation strategy file
  (`TrailblazeNodeSelectorGeneratorMacOsAx.kt`), a `minimizeMacOsAx`, and a full-fidelity
  `MacOsAxProperties` inspector panel that dumps every AX attribute verbatim. The one runtime
  dispatch that can't be honestly implemented yet (`TrailblazeDeviceManager.getCurrentScreenState`)
  is grouped with COMPOSE's "not supported → null" arm, tagged for Phase 2/3/5.
  Verified: `:trailblaze-models` compile + `jvmTest` green; `:trailblaze-common`/`:trailblaze-server`/
  `:trailblaze-host` main **and** test sources compile clean; `:trailblaze-android` debug compiles
  clean; `:trailblaze-ui` commonMain compiles clean (pulled in transitively). No runtime behavior
  yet — this is pure data-model + exhaustiveness plumbing, exactly the Phase 1 scope.
  Naming sanity-checked with the user mid-session: `MacOsAx` (no "e") is deliberate — the iOS
  `IosAxe` driver is named after the third-party **AXe** CLI binary, whereas this calls Apple's
  **AX** (`AXUIElement`) APIs directly, so `MacOsAx` = "macOS Accessibility", matching Apple's own
  `AX*` shorthand. User approved.
  Next: Phase 2 — promote the spike's `describeCfType`/`namesOf`/`getAttr` helpers into a real
  `host/macosax/MacOsAxNative.kt` + `MacOsAxTreeWalker.kt` that builds `TrailblazeNode` trees with
  populated `DriverNodeDetail.MacOsAx`, including `AXValueGetValue` struct decode (Point/Size/Rect/
  Range) and `CFRelease` discipline (the spike leaked; the real code must not).
- **2026-07-10 (same session, Phases 2–4 + 6 landed, Phase 5 partial)**: Built and **verified
  against live apps** the entire capture-and-drive core, all under `trailblaze-host/host/macosax/`
  (+ `host/screenstate/MacOsAxScreenState.kt`):
  - **Phase 2 (capture)**: `MacOsAxNative` (JNA layer, full function set, `AXValueGetValue` struct
    decode, strict `CFRelease` discipline) + `MacOsAxTreeWalker` (recursive walk → `TrailblazeNode`
    + `DriverNodeDetail.MacOsAx`). `MacOsAxCaptureTest` against live **Finder**: **1232 nodes**,
    root `AXApplication`, all nodes bounded, all 1232 decoded an `AXFrame` `CGRect`, nested
    `AXUIElement`s summarized as `ElementRef` (no cycles), compact list renders cleanly.
  - **Phase 3 (screenshot)**: `MacOsAxScreenshotCli.captureMainDisplay()` (full-display, global-
    coord aligned). Smoke test: **1 MB** valid PNG; Screen Recording permission inherited.
  - **Phase 4 (actions)**: `MacOsAxAction` (sealed) + `MacOsAxActionExecutor` (AX-native
    `AXPress`/`setAttributeValue` preferred, `CGEvent` synthetic-HID fallback) +
    `MacOsAxEventSynthesizer` (CoreGraphics, incl. a JNA `CGPoint` `Structure.ByValue`).
    `MacOsAxActionTest` against live **Calculator**: pressed `1+2=` via
    `TrailblazeNodeSelector(identifier=…)` → resolver (exact `AXIdentifier`) → `AXPress`, read
    result off a fresh capture, asserted **3**. Non-intrusive; also validated Phase 1's
    selector/resolver/matcher against a real app.
  - **Phase 5 core**: `MacOsAxDeviceManager` (execute + `getScreenState` + `captureTree` +
    `awaitSelector` polling) + `MacOsAxTrailRunner` (sequential runner → `TrailblazeToolResult`).
  - **Phase 6 (end-to-end trail)**: `MacOsAxTrailEndToEndTest` against **Calculator** — authored a
    5-step trail (tap One/Add/Two/Equals + `AssertVisible` on `AXStaticText value~"3"`), ran it
    through `MacOsAxTrailRunner → MacOsAxDeviceManager → executor → AXPress`, asserted via selector,
    and exercised `getScreenState()` (250-line compact list + 1.15 MB screenshot). All green.
  Every phase runs from source; all three macosax tests pass together. Two throwaway-ish tests
  (`MacOsAxCaptureTest`, `MacOsAxActionTest`) and one keeper e2e (`MacOsAxTrailEndToEndTest`) live
  under `trailblaze-host/src/test/.../host/macosax/` and are lenient (skip when not AX-trusted / app
  absent) so they're CI-safe. The Phase 0 spike file was superseded and deleted.
  **STOPPED for a product-design decision (this is the "literally need you" point):** the remaining
  Phase 5 work — `MacOsAxTrailblazeAgent` (LLM agent), `MacOsAxConnectedDevice`,
  `TrailblazeDeviceService` discovery + `getConnectedDevice`/`getCurrentScreenState` wiring,
  `MacOsAxStatus` UI, MCP bridge — all hinge on **how a user selects/discovers which macOS app to
  target**. iOS/Android identify a device by simulator-UDID / adb-serial and enumerate a device
  list; macOS has no equivalent — a "device" here is a *running app* (bundle id or pid). That
  choice determines the fully-qualified device-id format (`desktop/<bundleId>`? `desktop/<pid>`?),
  the CLI/`--device` flag shape, the `trails/config` target YAML, and the agent/device constructor
  signatures. Deferred to the maintainer rather than invented unilaterally.
- **2026-07-10 (same session, Phase 5 completed after the UX decision)**: User decided the target
  is identified by **bundle id** — the macOS analog of the iOS bundle id, with the existing
  "target" layer translating a friendly target → `appId` (bundleId) exactly as on iOS. Verified
  concretely that bundle id alone suffices: `open -b <bundleId>` launches at any time, System
  Events / `lsappinfo` resolve the running instance's live pid, and `osascript … application id`
  activates/quits — so pid stays an internal detail and only the bundle id is ever authored.
  Built the full framework wiring on that: `MacOsAxAppResolver` (bundleId→pid + launch/activate/
  quit + main-display size), `MacOsAxConnectedDevice(bundleId, pid, …)` (device-id
  `desktop/<bundleId>`), `TrailblazeDeviceService.getConnectedMacOsAxDevice` + the `DESKTOP`
  `when(driverType)` arm, `TrailblazeDeviceManager.getCurrentScreenState` MACOS_AX arm (real
  `MacOsAxScreenState`, no longer null), the MCP-bridge `executeToolViaMacOsAx` + dispatch branch +
  `getDirectScreenStateProvider` arms, `MacOsAxTrailblazeAgent` (extends `MaestroTrailblazeAgent`)
  + `MaestroCommandToMacOsAxActionConverter`, and the two `TrailblazeConnectedDevice` exhaustive-
  `when` fixups (MCP bridge, DevicesTab). Compiles clean across `:trailblaze-host` (main + test)
  and `:trailblaze-server`. **Verified the agent entry point at runtime** (`MacOsAxAgentTest`,
  Calculator): `MacOsAxTrailblazeAgent.executeNodeSelectorTap(identifier="Seven")` → display "7"
  — that's the exact class the MCP bridge constructs per tool call. Selection path for a live run:
  `trailblaze config desktop-driver macos-ax` + `--device desktop/<bundleId>`; the connect flow
  (`createPersistentDevice → getConnectedDevice`) registers the `MacOsAxConnectedDevice` and tool
  calls route through `executeToolViaMacOsAx`. **Remaining:** optional `MacOsAxStatus` home-tab UI
  (cosmetic), and a live daemon→MCP→CLI smoke test of that selection path (all wired + compiled,
  just not yet exercised through a running daemon). Nothing committed to git yet.

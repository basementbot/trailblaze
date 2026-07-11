package xyz.block.trailblaze.host.macosax

import maestro.KeyCode
import maestro.SwipeDirection
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.WaitForAnimationToEndCommand
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.util.Console

/**
 * Converts Maestro [Command]s to [MacOsAxAction]s, the desktop equivalent of
 * [xyz.block.trailblaze.host.axe.MaestroCommandToAxeActionConverter] (iOS). This is the
 * compatibility bridge for Maestro-shaped inputs; native trails author
 * [DriverNodeMatch.MacOsAx] selectors directly for full `AX*` fidelity.
 *
 * Commands with no macOS-desktop analog (back/home, hardware buttons, orientation) are logged
 * and skipped rather than silently dropped so trail authors can see what didn't translate.
 */
object MaestroCommandToMacOsAxActionConverter {

  private const val DEFAULT_ERASE_COUNT = 50

  fun convertAll(commands: List<Command>): List<MacOsAxAction> = commands.flatMap { convert(it) }

  fun convert(command: Command): List<MacOsAxAction> = when (command) {
    is TapOnPointCommand -> listOf(MacOsAxAction.Tap(command.x, command.y))
    is TapOnPointV2Command -> convertTapOnPointV2(command)
    is TapOnElementCommand -> listOf(convertTapOnElement(command))
    is SwipeCommand -> convertSwipe(command)
    is ScrollCommand -> listOf(MacOsAxAction.Scroll(MacOsAxAction.Direction.DOWN))
    is InputTextCommand -> listOf(MacOsAxAction.InputText(command.text))
    is InputRandomCommand -> listOf(MacOsAxAction.InputText(command.genRandomString()))
    is EraseTextCommand -> listOf(MacOsAxAction.EraseText(command.charactersToErase ?: DEFAULT_ERASE_COUNT))
    is WaitForAnimationToEndCommand ->
      listOf(MacOsAxAction.WaitForSettle(timeoutMs = command.timeout?.toLongOrNull() ?: 5_000L))
    is AssertConditionCommand -> convertAssertCondition(command)
    // Maestro's appId is the bundle id on macOS too.
    is LaunchAppCommand -> listOf(MacOsAxAction.LaunchApp(command.appId))
    is StopAppCommand -> listOf(MacOsAxAction.QuitApp(command.appId))
    is KillAppCommand -> listOf(MacOsAxAction.QuitApp(command.appId))
    is PressKeyCommand -> convertPressKey(command)
    else -> {
      Console.log("[MacOsAxConverter] Skipping unsupported command: ${command::class.simpleName}")
      emptyList()
    }
  }

  /**
   * Maps a Maestro [PressKeyCommand] to a macOS [MacOsAxAction.PressKey]. Only keys with a real
   * macOS analog are mapped; Android-specific keys (BACK, LOCK, hardware volume) have no desktop
   * equivalent and are logged + skipped.
   */
  private fun convertPressKey(command: PressKeyCommand): List<MacOsAxAction> {
    val (code, label) = when (command.code) {
      KeyCode.ENTER -> MacOsAxEventSynthesizer.KEY_CODE_RETURN to "Enter"
      KeyCode.BACKSPACE -> MacOsAxEventSynthesizer.KEY_CODE_DELETE to "Backspace"
      KeyCode.TAB -> MacOsAxEventSynthesizer.KEY_CODE_TAB to "Tab"
      KeyCode.ESCAPE -> MacOsAxEventSynthesizer.KEY_CODE_ESCAPE to "Escape"
      KeyCode.HOME -> MacOsAxEventSynthesizer.KEY_CODE_HOME to "Home"
      else -> {
        Console.log("[MacOsAxConverter] Skipping unsupported key: ${command.code.name}")
        return emptyList()
      }
    }
    return listOf(MacOsAxAction.PressKey(code, label))
  }

  private fun convertTapOnPointV2(command: TapOnPointV2Command): List<MacOsAxAction> {
    val parts = command.point.split(",").map { it.trim() }
    if (parts.size != 2) error("Invalid point format: ${command.point}")
    val (xStr, yStr) = parts
    if (xStr.endsWith("%") || yStr.endsWith("%")) {
      // Percent coords need the device size, which this stateless converter doesn't have.
      // Native macOS trails should target elements by selector rather than by percent point.
      Console.log("[MacOsAxConverter] percent-based TapOnPointV2 is unsupported on macOS — skipping")
      return emptyList()
    }
    return listOf(MacOsAxAction.Tap(xStr.toDouble().toInt(), yStr.toDouble().toInt()))
  }

  private fun convertTapOnElement(command: TapOnElementCommand): MacOsAxAction {
    val nodeSelector = convertElementSelectorToNodeSelector(convertMaestroSelector(command.selector))
    return MacOsAxAction.TapOnElement(nodeSelector = nodeSelector)
  }

  private fun convertSwipe(command: SwipeCommand): List<MacOsAxAction> = when {
    command.direction != null -> {
      val dir = when (command.direction!!) {
        SwipeDirection.UP -> MacOsAxAction.Direction.UP
        SwipeDirection.DOWN -> MacOsAxAction.Direction.DOWN
        SwipeDirection.LEFT -> MacOsAxAction.Direction.LEFT
        SwipeDirection.RIGHT -> MacOsAxAction.Direction.RIGHT
      }
      listOf(MacOsAxAction.Scroll(dir))
    }
    else -> {
      // macOS AX scroll is wheel-based, not a drag between two points; a point-to-point swipe
      // has no clean analog. Map to a plain vertical scroll rather than dropping the intent.
      Console.log("[MacOsAxConverter] point-based SwipeCommand mapped to a vertical scroll")
      listOf(MacOsAxAction.Scroll(MacOsAxAction.Direction.DOWN))
    }
  }

  private fun convertAssertCondition(command: AssertConditionCommand): List<MacOsAxAction> {
    val cond = command.condition
    val timeoutMs = command.timeoutMs() ?: 5_000L
    return when {
      cond.visible != null ->
        listOf(MacOsAxAction.AssertVisible(selectorOf(cond.visible!!), timeoutMs))
      cond.notVisible != null ->
        listOf(MacOsAxAction.AssertNotVisible(selectorOf(cond.notVisible!!), timeoutMs))
      else -> {
        Console.log("[MacOsAxConverter] AssertConditionCommand without visible/notVisible — skipping")
        emptyList()
      }
    }
  }

  private fun selectorOf(selector: maestro.orchestra.ElementSelector): TrailblazeNodeSelector =
    convertElementSelectorToNodeSelector(convertMaestroSelector(selector))

  /**
   * Maps a Maestro-shaped [TrailblazeElementSelector] to a [TrailblazeNodeSelector] carrying a
   * [DriverNodeMatch.MacOsAx]. Maestro `text` → `AXTitle` (`titleRegex`); Maestro `id` →
   * `AXIdentifier` (exact). State flags Maestro infers (selected/focused/checked) are dropped —
   * native trails should express state via the richer `MacOsAx` matcher directly.
   */
  private fun convertElementSelectorToNodeSelector(
    selector: TrailblazeElementSelector,
  ): TrailblazeNodeSelector {
    val hasMatch = selector.textRegex != null || selector.idRegex != null
    val driverMatch = if (hasMatch) {
      DriverNodeMatch.MacOsAx(
        titleRegex = selector.textRegex,
        identifier = selector.idRegex,
      )
    } else null

    return TrailblazeNodeSelector.withMatch(
      driverMatch,
      below = selector.below?.let { convertElementSelectorToNodeSelector(it) },
      above = selector.above?.let { convertElementSelectorToNodeSelector(it) },
      leftOf = selector.leftOf?.let { convertElementSelectorToNodeSelector(it) },
      rightOf = selector.rightOf?.let { convertElementSelectorToNodeSelector(it) },
      childOf = selector.childOf?.let { convertElementSelectorToNodeSelector(it) },
      containsChild = selector.containsChild?.let { convertElementSelectorToNodeSelector(it) },
      containsDescendants = selector.containsDescendants?.map { convertElementSelectorToNodeSelector(it) },
      index = selector.index?.toDoubleOrNull()?.toInt(),
    )
  }

  private fun convertMaestroSelector(
    selector: maestro.orchestra.ElementSelector,
  ): TrailblazeElementSelector = TrailblazeElementSelector(
    textRegex = selector.textRegex,
    idRegex = selector.idRegex,
    index = selector.index,
    enabled = selector.enabled,
    selected = selector.selected,
    checked = selector.checked,
    focused = selector.focused,
    below = selector.below?.let { convertMaestroSelector(it) },
    above = selector.above?.let { convertMaestroSelector(it) },
    leftOf = selector.leftOf?.let { convertMaestroSelector(it) },
    rightOf = selector.rightOf?.let { convertMaestroSelector(it) },
    containsChild = selector.containsChild?.let { convertMaestroSelector(it) },
    containsDescendants = selector.containsDescendants?.map { convertMaestroSelector(it) },
    childOf = selector.childOf?.let { convertMaestroSelector(it) },
  )
}

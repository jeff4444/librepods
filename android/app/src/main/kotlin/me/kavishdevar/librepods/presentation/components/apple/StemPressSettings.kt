/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.components.apple

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem

/** Actions offered for every press type, in the order the pickers list them. */
val stemActionChoices = listOf(
    StemAction.PLAY_PAUSE,
    StemAction.NEXT_TRACK,
    StemAction.PREVIOUS_TRACK,
    StemAction.VOLUME_UP,
    StemAction.VOLUME_DOWN,
    StemAction.CYCLE_listening_mode_MODES,
    StemAction.DIGITAL_ASSISTANT,
    StemAction.AUTOMATION,
    StemAction.NOTHING,
)

@Composable
fun stemActionLabel(action: StemAction): String = when (action) {
    StemAction.PLAY_PAUSE -> stringResource(R.string.stem_action_play_pause)
    StemAction.NEXT_TRACK -> stringResource(R.string.stem_action_next_track)
    StemAction.PREVIOUS_TRACK -> stringResource(R.string.stem_action_previous_track)
    StemAction.VOLUME_UP -> stringResource(R.string.stem_action_volume_up)
    StemAction.VOLUME_DOWN -> stringResource(R.string.stem_action_volume_down)
    StemAction.CYCLE_listening_mode_MODES -> stringResource(R.string.listening_mode)
    StemAction.DIGITAL_ASSISTANT -> stringResource(R.string.digital_assistant)
    StemAction.AUTOMATION -> stringResource(R.string.stem_action_automation)
    StemAction.NOTHING -> stringResource(R.string.stem_action_nothing)
}

@Composable
fun stemPressLabel(type: StemPressType): String = when (type) {
    StemPressType.SINGLE_PRESS -> stringResource(R.string.single_press)
    StemPressType.DOUBLE_PRESS -> stringResource(R.string.double_press)
    StemPressType.TRIPLE_PRESS -> stringResource(R.string.triple_press)
    StemPressType.LONG_PRESS -> stringResource(R.string.press_and_hold_airpods)
}

/** Single, double and triple press, each opening a picker for both buds. Long press has its own section. */
@Composable
fun StemPressSettings(
    settings: AppleSettings,
    navigateToStemPress: (StemPressType) -> Unit
) {
    StyledList(
        title = stringResource(R.string.stem_presses_title),
        description = stringResource(R.string.stem_presses_description)
    ) {
        listOf(StemPressType.SINGLE_PRESS, StemPressType.DOUBLE_PRESS, StemPressType.TRIPLE_PRESS).forEach { type ->
            styledListItem(
                contentText = stemPressLabel(type),
                supportingText = stringResource(
                    R.string.stem_press_both_buds,
                    stemActionLabel(settings.stemAction(StemPressBud.LEFT, type)),
                    stemActionLabel(settings.stemAction(StemPressBud.RIGHT, type))
                ),
                onClick = { navigateToStemPress(type) }
            )
        }
    }
}

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

package me.kavishdevar.librepods.presentation.screens.pixelbuds

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.PixelBudsDevice
import me.kavishdevar.librepods.core.devices.PixelBudsNoiseMode
import me.kavishdevar.librepods.core.devices.PixelBudsSetting
import me.kavishdevar.librepods.presentation.components.apple.BatteryView
import me.kavishdevar.librepods.presentation.components.pixelbuds.PixelBudsArt
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem

/**
 * Pixel Buds, read-only: what the buds report over Maestro. LibrePods changes nothing on them;
 * settings stay with Android's own Pixel Buds settings.
 */
@Composable
fun PixelBudsScreen(
    device: PixelBudsDevice,
    navigateBack: (() -> Unit)?
) {
    val state by device.state.collectAsState()
    val metadata by device.metadata.collectAsState()
    val connection by device.connectionState.collectAsState()

    StyledScaffold(
        title = metadata.name.ifBlank { metadata.modelName },
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            if (state.battery.any { it.status != BatteryStatus.DISCONNECTED }) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BatteryView(
                        batteryList = state.battery,
                        primaryImageRes = PixelBudsArt.buds(metadata),
                        caseImageRes = PixelBudsArt.case,
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(PixelBudsArt.buds(metadata)),
                        contentDescription = metadata.modelName,
                        modifier = Modifier.size(160.dp)
                    )
                }
            }

            if (connection != ConnectionState.CONNECTED) {
                Text(
                    text = "LibrePods reads Pixel Buds while they're connected to this phone and Google's " +
                        "Pixel Buds app isn't installed. Showing the last values it read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StyledList(title = "Status") {
                styledListItem(
                    contentText = "Noise control",
                    supportingText = when (state.noiseMode) {
                        PixelBudsNoiseMode.OFF -> "Off"
                        PixelBudsNoiseMode.NOISE_CANCELLATION -> "Active Noise Cancellation"
                        PixelBudsNoiseMode.TRANSPARENCY -> "Transparency"
                        PixelBudsNoiseMode.ADAPTIVE -> "Adaptive"
                        null -> "Unknown"
                    },
                    enabled = false
                )
                for ((component, label) in listOf(
                    BatteryComponent.LEFT to "Left bud",
                    BatteryComponent.RIGHT to "Right bud",
                    BatteryComponent.CASE to "Case"
                )) {
                    val battery = state.battery.find { it.component == component }
                    val place = when (component) {
                        BatteryComponent.LEFT -> state.leftInCase
                        BatteryComponent.RIGHT -> state.rightInCase
                        else -> null
                    }
                    styledListItem(
                        contentText = label,
                        supportingText = listOfNotNull(
                            battery?.let {
                                "${it.level}%" + when (it.status) {
                                    BatteryStatus.CHARGING -> ", charging"
                                    BatteryStatus.DISCONNECTED -> " (last known)"
                                    else -> ""
                                }
                            } ?: "No reading",
                            place?.let { if (it) "in the case" else "out of the case" }
                        ).joinToString(", "),
                        enabled = false
                    )
                }
            }

            StyledList(title = "Settings (read-only)") {
                fun onOff(id: Int) = when (state.flag(id)) {
                    true -> "On"
                    false -> "Off"
                    null -> "Unknown"
                }
                styledListItem(contentText = "Conversation detection", supportingText = onOff(PixelBudsSetting.CONVERSATION_DETECTION), enabled = false)
                styledListItem(contentText = "In-ear detection", supportingText = onOff(PixelBudsSetting.IN_EAR_DETECTION), enabled = false)
                styledListItem(contentText = "Multipoint", supportingText = onOff(PixelBudsSetting.MULTIPOINT), enabled = false)
                styledListItem(contentText = "Touch controls", supportingText = onOff(PixelBudsSetting.GESTURES), enabled = false)
                styledListItem(contentText = "Volume EQ", supportingText = onOff(PixelBudsSetting.VOLUME_EQ), enabled = false)
                styledListItem(contentText = "Mono audio", supportingText = onOff(PixelBudsSetting.MONO), enabled = false)
                styledListItem(
                    contentText = "Press and hold",
                    supportingText = state.holdActions?.let { (left, right) ->
                        "Left: ${holdAction(left)}, right: ${holdAction(right)}"
                    } ?: "Unknown",
                    enabled = false
                )
                styledListItem(
                    contentText = "Equalizer",
                    supportingText = state.eq?.let { bands ->
                        listOf("Low bass", "Bass", "Mid", "Treble", "Upper treble").zip(bands)
                            .joinToString(", ") { (name, value) -> "$name ${"%+.1f".format(value)}" }
                    } ?: "Unknown",
                    enabled = false
                )
            }

            StyledList(title = "About") {
                styledListItem(contentText = "Model", supportingText = listOfNotNull(metadata.modelName, metadata.colorName).joinToString(", "), enabled = false)
                styledListItem(contentText = "Firmware", supportingText = firmware(metadata.firmwareLeft, metadata.firmwareRight, metadata.firmwareCase), enabled = false)
                styledListItem(contentText = "Serial, left bud", supportingText = metadata.serialLeft.ifBlank { "Unknown" }, enabled = false)
                styledListItem(contentText = "Serial, right bud", supportingText = metadata.serialRight.ifBlank { "Unknown" }, enabled = false)
                styledListItem(contentText = "Serial, case", supportingText = metadata.serialCase.ifBlank { "Unknown" }, enabled = false)
                styledListItem(contentText = "Bluetooth address", supportingText = device.macAddress.value, enabled = false)
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

private fun holdAction(value: Int?) = when (value) {
    5 -> "Noise control"
    6 -> "Digital assistant"
    null -> "Unknown"
    else -> "Action $value"
}

private fun firmware(left: String, right: String, case: String): String {
    if (left.isBlank() && right.isBlank() && case.isBlank()) return "Unknown"
    if (left == right && right == case) return left
    return "Left $left, right $right, case $case"
}

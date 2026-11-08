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

import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.maestro.Proto
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.PixelBudsDevice
import me.kavishdevar.librepods.core.devices.PixelBudsFitResult
import me.kavishdevar.librepods.core.devices.PixelBudsHoldAction
import me.kavishdevar.librepods.core.devices.PixelBudsNoiseMode
import me.kavishdevar.librepods.core.devices.PixelBudsSetting
import me.kavishdevar.librepods.core.devices.PixelBudsState
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.presentation.components.apple.BatteryView
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.pixelbuds.PixelBudsArt
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListScope
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.overlays.LoopingVideoView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Google's EQ presets (low bass, bass, mid, treble, upper treble), from its Pixel Buds app. */
private val EQ_PRESETS = listOf(
    "Default" to listOf(0f, 0f, 0f, 0f, 0f),
    "Balanced" to listOf(-3.5f, 0.5f, 1f, -1f, 2.5f),
    "Clarity" to listOf(-2f, 0f, 2f, 3f, 5f),
    "Heavy bass" to listOf(5f, 3f, 0f, 0f, 0f),
    "Light bass" to listOf(-5f, -1.5f, 0f, 0f, 0f),
    "Vocal boost" to listOf(-1f, 0f, 4f, 2f, 0f),
)
private val EQ_BANDS = listOf("Low bass", "Bass", "Mid", "Treble", "Upper treble")

/** Pixel Buds settings, read and written over Maestro while LibrePods holds the channel. */
@Composable
fun PixelBudsScreen(
    device: PixelBudsDevice,
    navigateBack: (() -> Unit)?
) {
    val state by device.state.collectAsState()
    val metadata by device.metadata.collectAsState()
    val connection by device.connectionState.collectAsState()
    val live = connection == ConnectionState.CONNECTED

    var holdPicker by remember { mutableStateOf<Boolean?>(null) }  // true: left bud, false: right

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

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.battery.any { it.status != BatteryStatus.DISCONNECTED }) {
                    BatteryView(
                        batteryList = state.battery,
                        primaryImageRes = PixelBudsArt.buds(metadata),
                        caseImageRes = PixelBudsArt.case,
                    )
                } else {
                    Image(
                        painter = painterResource(PixelBudsArt.buds(metadata)),
                        contentDescription = metadata.modelName,
                        modifier = Modifier.size(160.dp)
                    )
                }
            }

            if (!live) {
                Text(
                    text = "Take a bud out of the case to connect. Changes need the buds connected to this phone " +
                        "(and Google's Pixel Buds app not installed); showing the last values read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.liveOnly(live), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NoiseControlSettings(
                    // The Apple slider needs a fixed height; the Material buttons need room for their labels.
                    modifier = if (LocalDesign.current.system == DesignSystem.Apple) Modifier.height(100.dp) else Modifier,
                    showOffListeningMode = true,
                    noiseControlModeValue = state.noiseMode?.value ?: PixelBudsNoiseMode.NOISE_CANCELLATION.value,
                    onNoiseControlModeChanged = { value -> PixelBudsNoiseMode.from(value)?.let(device::setNoiseMode) },
                    adaptiveStrength = 50,
                    onAdaptiveStrengthChanged = { },
                )

                StyledList {
                    flagToggle(device, state, PixelBudsSetting.CONVERSATION_DETECTION, "Conversation detection",
                        "Pauses media and switches to Transparency when you start talking")
                }

                TouchControls(device, state, onPick = { holdPicker = it })

                Equalizer(device, state)

                StyledList(title = "Sound") {
                    flagToggle(device, state, PixelBudsSetting.VOLUME_EQ, "Volume EQ", "Tunes bass and treble to how loud you listen")
                    flagToggle(device, state, PixelBudsSetting.MONO, "Mono audio", "Plays the same sound in both buds")
                }
                Balance(device, state)

                FitTestSection(device, metadata.sku)

                SoundLevel(device, state)

                StyledList(title = "Connection") {
                    flagToggle(device, state, PixelBudsSetting.IN_EAR_DETECTION, "In-ear detection", "Pauses when you take a bud out")
                    flagToggle(device, state, PixelBudsSetting.MULTIPOINT, "Multipoint", "Stays connected to two devices at once")
                }

                HeadGestures(device)

                UnnamedSettings(state)
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

    holdPicker?.let { left ->
        val (l, r) = state.holdActions ?: (null to null)
        HoldActionPicker(
            title = if (left) "Press and hold, left bud" else "Press and hold, right bud",
            current = if (left) l else r,
            onPick = { action ->
                val newLeft = if (left) action else l ?: PixelBudsHoldAction.NOISE_CONTROL
                val newRight = if (left) r ?: PixelBudsHoldAction.NOISE_CONTROL else action
                device.setHoldActions(newLeft, newRight)
                holdPicker = null
            },
            onDismiss = { holdPicker = null },
        )
    }
}

/** Dims and blocks touches while the buds aren't connected. */
private fun Modifier.liveOnly(live: Boolean): Modifier = if (live) this else this
    .alpha(0.5f)
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

@Composable
private fun StyledListScope.flagToggle(
    device: PixelBudsDevice,
    state: PixelBudsState,
    id: Int,
    label: String,
    description: String,
) {
    val value = state.flag(id)
    styledToggle(
        label = label,
        description = if (value == null) "$description (not read yet)" else description,
        checked = value == true,
        onCheckedChange = { device.setFlag(id, it) },
    )
}

@Composable
private fun TouchControls(device: PixelBudsDevice, state: PixelBudsState, onPick: (Boolean) -> Unit) {
    val (left, right) = state.holdActions ?: (null to null)
    StyledList(title = "Touch controls") {
        flagToggle(device, state, PixelBudsSetting.GESTURES, "Touch controls", "Tap, swipe and press and hold on the buds")
        styledListItem(contentText = "Press and hold, left", supportingText = holdActionLabel(left), onClick = { onPick(true) })
        styledListItem(contentText = "Press and hold, right", supportingText = holdActionLabel(right), onClick = { onPick(false) })
    }
    val loop = state.noiseLoop
    AnimatedVisibility(
        visible = left == PixelBudsHoldAction.NOISE_CONTROL || right == PixelBudsHoldAction.NOISE_CONTROL,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        StyledList(
            title = "Press and hold switches between",
            description = "At least two modes."
        ) {
            for ((mode, label) in listOf(
                PixelBudsNoiseMode.NOISE_CANCELLATION to "Active Noise Cancellation",
                PixelBudsNoiseMode.ADAPTIVE to "Adaptive",
                PixelBudsNoiseMode.TRANSPARENCY to "Transparency",
                PixelBudsNoiseMode.OFF to "Off",
            )) {
                val on = loop?.contains(mode) == true
                styledToggle(
                    label = label,
                    checked = on,
                    enabled = loop != null && (!on || loop.size > 2),
                    onCheckedChange = { checked -> loop?.let { device.setNoiseLoop(if (checked) it + mode else it - mode) } },
                )
            }
        }
    }
}

private fun holdActionLabel(value: Int?) = when (value) {
    PixelBudsHoldAction.NOISE_CONTROL -> "Switch noise control"
    PixelBudsHoldAction.ASSISTANT -> "Digital assistant"
    PixelBudsHoldAction.ACTION_7 -> "Gemini (likely; seen on these buds)"
    PixelBudsHoldAction.PLAY_PAUSE -> "Play and pause"
    PixelBudsHoldAction.NEXT_TRACK -> "Next track"
    PixelBudsHoldAction.PREVIOUS_TRACK -> "Previous track"
    PixelBudsHoldAction.CHECK_NOTIFICATIONS -> "Check notifications"
    null -> "Not read yet"
    else -> "Action $value"
}

@Composable
private fun HoldActionPicker(title: String, current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (action in PixelBudsHoldAction.ALL) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(action) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == action, onClick = { onPick(action) })
                        Column {
                            Text(holdActionLabel(action), style = MaterialTheme.typography.bodyLarge)
                            if (action !in listOf(PixelBudsHoldAction.NOISE_CONTROL, PixelBudsHoldAction.ASSISTANT)) {
                                Text(
                                    "Not offered by Google's app; the buds may ignore it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Equalizer(device: PixelBudsDevice, state: PixelBudsState) {
    val bands = state.eq
    // Local copy while dragging, so the sliders don't jump back between writes.
    val local = remember { mutableStateListOf<Float>() }
    LaunchedEffect(bands) {
        if (bands != null && (local.size != 5 || bands.zip(local).any { (a, b) -> abs(a - b) > 0.05f })) {
            local.clear(); local.addAll(bands)
        }
    }
    val preset = EQ_PRESETS.firstOrNull { (_, values) -> local.size == 5 && values.zip(local).all { (a, b) -> abs(a - b) < 0.05f } }?.first

    StyledList(title = "Equalizer", description = if (bands == null) "Not read yet." else null) {
        item { _, _ ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((name, values) in EQ_PRESETS) {
                    FilterChip(
                        selected = preset == name,
                        onClick = {
                            local.clear(); local.addAll(values)
                            device.setEq(values)
                        },
                        label = { Text(name) },
                    )
                }
                FilterChip(selected = preset == null && local.size == 5, onClick = { }, label = { Text("Custom") })
            }
        }
        if (local.size == 5) {
            EQ_BANDS.forEachIndexed { i, name ->
                item { _, _ ->
                    StyledSlider(
                        label = "$name  ${"%+.1f".format(local[i])} dB",
                        value = local[i],
                        onValueChange = { v ->
                            local[i] = (v * 2).roundToInt() / 2f
                            device.setEq(local.toList())
                        },
                        valueRange = PixelBudsSetting.EQ_MIN..PixelBudsSetting.EQ_MAX,
                        snapPoints = listOf(0f),
                        index = i,
                        count = EQ_BANDS.size,
                    )
                }
            }
        }
    }
}

@Composable
private fun Balance(device: PixelBudsDevice, state: PixelBudsState) {
    var local by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.balance) { state.balance?.let { local = it.toFloat() } }
    val value = local ?: return
    val v = value.roundToInt()
    StyledSlider(
        label = "Balance  " + when {
            v < 0 -> "${-v}% left"
            v > 0 -> "$v% right"
            else -> "centred"
        },
        value = value,
        onValueChange = { local = it; device.setBalance(it.roundToInt()) },
        valueRange = -100f..100f,
        snapPoints = listOf(0f),
        startLabel = "L",
        endLabel = "R",
        independent = true,
    )
}

@Composable
private fun FitTestSection(device: PixelBudsDevice, sku: Int?) {
    val context = LocalContext.current
    val test = remember(device) { PixelBudsFitTest(context.applicationContext, device) }
    val s by test.state.collectAsState()
    DisposableEffect(test) { onDispose { test.finish() } }

    StyledList(
        title = "Ear tip seal check",
        description = "Put both buds in and stay somewhere quiet. Plays a short sound and checks how well each bud seals."
    ) {
        styledListItem(
            contentText = if (s.running) "Checking…" else if (s.ran) "Check again" else "Check the seal",
            supportingContent = if (s.running) ({ LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }) else null,
            onClick = if (s.running) null else ({ test.start() }),
            orientation = StyledListItemOrientation.Vertical,
        )
        if (s.ran || s.running) {
            styledListItem(contentText = "Left bud", supportingText = fitText(s.left, s.running), enabled = false)
            styledListItem(contentText = "Right bud", supportingText = fitText(s.right, s.running), enabled = false)
        }
    }
    val needsFixing = listOfNotNull(s.left, s.right).any { it.grade == PixelBudsFitResult.Grade.ADJUST || it.grade == PixelBudsFitResult.Grade.CHANGE_TIPS }
    AnimatedVisibility(visible = (s.running || needsFixing) && (sku == null || sku == 10)) {
        LoopingVideoView(
            videoResId = R.raw.pixel_buds_fitting_hazel,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        )
    }
}

private fun fitText(result: PixelBudsFitResult?, running: Boolean): String {
    if (result == null) return if (running) "Listening…" else "No result"
    val words = when (result.grade) {
        PixelBudsFitResult.Grade.GOOD -> "Good seal"
        PixelBudsFitResult.Grade.ADJUST -> "Twist the bud to adjust it"
        PixelBudsFitResult.Grade.CHANGE_TIPS -> "Try a different ear tip size"
        PixelBudsFitResult.Grade.NOISY -> "Too noisy here, try somewhere quieter"
        PixelBudsFitResult.Grade.FAILED -> "Couldn't measure"
    }
    return "$words (fit ${"%.0f".format(result.fit)}, noise ${"%.0f".format(result.noise)})"
}

@Composable
private fun SoundLevel(device: PixelBudsDevice, state: PixelBudsState) {
    val level by device.soundLevel.collectAsState()
    DisposableEffect(device) {
        device.watchSoundLevel(true)
        onDispose { device.watchSoundLevel(false) }
    }
    StyledList(title = "Hearing") {
        styledListItem(
            contentText = "Sound level",
            supportingText = level?.takeIf { it > 0f }?.let { "${it.roundToInt()} dB at your ears" } ?: "Play something to measure",
            enabled = false,
        )
        flagToggle(device, state, PixelBudsSetting.VOLUME_EXPOSURE_NOTIFICATIONS, "Volume exposure notifications",
            "Warns you when you've been listening loud for a long time")
    }
}

@Composable
private fun HeadGestures(device: PixelBudsDevice) {
    var on by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    DisposableEffect(on) {
        if (on) device.startHeadGestures { value -> last = value to System.currentTimeMillis() }
        onDispose { if (on) device.stopHeadGestures() }
    }
    StyledList(
        title = "Head gestures (experimental)",
        description = "Nod or shake your head. LibrePods logs what the buds report so the gestures can be named."
    ) {
        styledToggle(label = "Detect nods and shakes", checked = on, onCheckedChange = { on = it })
        if (on) {
            styledListItem(
                contentText = "Last gesture",
                supportingText = last?.let { (value, at) -> "Raw value $value, ${(System.currentTimeMillis() - at) / 1000}s ago" } ?: "Nothing yet",
                enabled = false,
            )
        }
    }
    AnimatedVisibility(visible = on) {
        var nod by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { while (true) { delay(4000); nod = !nod } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LottieLoop(if (nod) R.raw.pixel_buds_head_nod else R.raw.pixel_buds_head_shake, Modifier.weight(1f).clip(RoundedCornerShape(24.dp)))
        }
    }
}

@Composable
private fun LottieLoop(@RawRes resId: Int, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resId))
    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        modifier = modifier.aspectRatio(412f / 300f)
    )
}

@Composable
private fun UnnamedSettings(state: PixelBudsState) {
    val unnamed = state.settings.filterKeys { it !in PixelBudsSetting.NAMED }.toSortedMap()
    if (unnamed.isEmpty()) return
    StyledList(
        title = "Unnamed settings (experimental)",
        description = "Values the buds report that nobody has named yet. Change something in Android's Pixel Buds " +
            "settings and watch which one moves."
    ) {
        for ((id, field) in unnamed) {
            styledListItem(contentText = "Setting $id", supportingText = Proto.describe(field), enabled = false)
        }
    }
}

private fun firmware(left: String, right: String, case: String): String {
    if (left.isBlank() && right.isBlank() && case.isBlank()) return "Unknown"
    if (left == right && right == case) return left
    return "Left $left, right $right, case $case"
}

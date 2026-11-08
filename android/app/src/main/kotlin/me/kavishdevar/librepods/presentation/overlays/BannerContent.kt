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

package me.kavishdevar.librepods.presentation.overlays

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.icons.LocalIcons

/** One part's battery for the banner's line: "Left 100%". */
data class BannerBattery(val label: String, val level: Int, val charging: Boolean = false)

/**
 * The connection banner for the Material design: a Material 3 Expressive card that drops in from
 * under the status bar, like Android's own device banners, instead of the island. The icon sits in
 * a slowly turning cookie shape inside a wavy battery ring that fills as it appears, and the battery
 * numbers count up. Swipe it up to dismiss; tap to open the app.
 */
@Composable
fun BannerContent(
    name: String,
    type: IslandType,
    batteryLevel: Int,
    batteries: List<BannerBattery>,
    reversed: Boolean,
    otherDeviceName: String?,
    islandState: IslandState,

    openApp: () -> Unit,
    requestClose: () -> Unit,
    onClosed: () -> Unit,
    holdOpen: (Boolean) -> Unit,

    @DrawableRes monochromeIconRes: Int? = null,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 0 = tucked above the screen, 1 = in place. Springs in with a little bounce; leaves quickly.
    val shown = remember { Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(islandState) {
        if (islandState == IslandState.CLOSE) {
            shown.animateTo(0f, tween(240))
            onClosed()
        } else {
            shown.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    val compact = islandState == IslandState.COMPACT

    val subtitle = when (type) {
        IslandType.CONNECTED -> null
        IslandType.TAKING_OVER -> stringResource(R.string.island_taking_over_text)
        IslandType.MOVED_TO_REMOTE -> stringResource(R.string.island_moved_to_remote_text)
        IslandType.MOVED_TO_OTHER_DEVICE -> if (reversed) {
            stringResource(R.string.island_moved_to_other_device_reversed_text)
        } else {
            stringResource(R.string.island_moved_to_other_device_text, otherDeviceName ?: "")
        }
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 480.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            onClick = openApp,
            // Soft corners rather than a pill.
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .then(if (compact) Modifier else Modifier.fillMaxWidth())
                .graphicsLayer {
                    val hidden = -(size.height + 24.dp.toPx())
                    // Pulling down stretches a little and springs back; pulling up follows the finger.
                    val pull = if (dragY > 0f) dragY * 0.25f else dragY
                    translationY = (1f - shown.value) * hidden + pull
                    alpha = shown.value.coerceIn(0f, 1f)
                    val s = 0.92f + 0.08f * shown.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { holdOpen(true) },
                        onDragEnd = {
                            if (dragY < -with(density) { 36.dp.toPx() }) {
                                requestClose()
                            } else {
                                holdOpen(false)
                                scope.launch {
                                    Animatable(dragY).animateTo(0f, spring(dampingRatio = 0.6f)) { dragY = value }
                                }
                            }
                        },
                        onDragCancel = { dragY = 0f; holdOpen(false) },
                    ) { change, amount ->
                        change.consume()
                        dragY += amount
                    }
                },
        ) {
            Column(
                modifier = Modifier.animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BatteryIcon(
                        level = batteryLevel,
                        charging = batteries.any { it.charging },
                        monochromeIconRes = monochromeIconRes,
                        appeared = shown.value > 0.5f,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    if (compact) {
                        Text(
                            text = "$batteryLevel%",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                    } else {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            AnimatedContent(
                                targetState = subtitle,
                                transitionSpec = {
                                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                                },
                            ) { text ->
                                if (text != null) {
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    BatteryLine(batteries.ifEmpty { listOf(BannerBattery("", batteryLevel)) }, appeared = shown.value > 0.5f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The device icon in a slowly turning cookie, inside a wavy ring that fills to the battery level. */
@Composable
private fun BatteryIcon(level: Int, charging: Boolean, @DrawableRes monochromeIconRes: Int?, appeared: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (appeared) level.coerceIn(0, 100) / 100f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
    )
    // The cookie grows in with a bounce, then keeps turning (faster while charging).
    val pop by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.4f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
    )
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(charging) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            rotation = (rotation + (now - last) / 1e9f * if (charging) 45f else 12f) % 360f
            last = now
        }
    }
    val low = level <= 20 && !charging
    val ring = when {
        low -> MaterialTheme.colorScheme.error
        charging -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        CircularWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            color = ring,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                    rotationZ = rotation
                }
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(if (low) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
        )
        val tint = if (low) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
        if (monochromeIconRes != null) {
            Icon(painter = painterResource(monochromeIconRes), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        } else {
            Icon(imageVector = LocalIcons.current.AirPodsPro3, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** "Left 100%   Case 53%   Right 100%", each number counting up as the banner appears. */
@Composable
private fun BatteryLine(batteries: List<BannerBattery>, appeared: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        batteries.forEachIndexed { i, battery ->
            var start by remember { mutableStateOf(false) }
            LaunchedEffect(appeared) {
                if (appeared) {
                    delay(120L * i)
                    start = true
                }
            }
            val shownLevel by animateIntAsState(
                targetValue = if (start) battery.level else 0,
                animationSpec = tween(durationMillis = 650),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (battery.label.isNotEmpty()) {
                    Text(
                        text = battery.label + " ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "$shownLevel%",
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                    color = if (battery.level <= 20 && !battery.charging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (battery.charging) {
                    Box(
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                }
            }
        }
    }
}

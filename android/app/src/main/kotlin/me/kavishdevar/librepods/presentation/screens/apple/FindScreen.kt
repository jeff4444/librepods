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

package me.kavishdevar.librepods.presentation.screens.apple

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.data.apple.SignalSource
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import kotlin.math.roundToInt

private const val TAG = "FindScreen"

// Smoothing for the raw readings, which jump by several dB from one advert to the next.
private const val SMOOTHING = 0.25f

// A source counts as lost after this long without an advert.
private const val LOST_AFTER_MS = 5_000L

// The trend compares the smoothed signal now with this long ago, and needs this many dB of change.
private const val TREND_WINDOW_MS = 2_000L
private const val TREND_THRESHOLD_DB = 3f

/**
 * Hot/cold finder (roadmap #5): live signal strength of the buds' and the case's Bluetooth adverts,
 * whether it is getting stronger or weaker, and optional beeps that speed up as it gets stronger.
 * Works whether or not the AirPods are connected, as long as they advertise.
 */
@Composable
fun FindScreen(
    device: AppleDevice,
    navigateBack: (() -> Unit)?
) {
    // The phone scans at full speed only while the screen is on.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val buds = remember { SignalTracker() }
    val case = remember { SignalTracker() }
    var budSide by remember { mutableStateOf<DeviceComponent?>(null) }

    LaunchedEffect(device) {
        device.signalReadings.collect { reading ->
            when (reading.source) {
                SignalSource.BUDS -> {
                    buds.add(reading.rssi, reading.timeMillis)
                    budSide = reading.bud
                }
                SignalSource.CASE -> case.add(reading.rssi, reading.timeMillis)
            }
            Log.v(TAG, "${reading.source} ${reading.bud ?: ""} ${reading.rssi} dBm")
        }
    }

    // Ticks the clock so "not seen for" and the trend stay current between adverts.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    var beepOn by rememberSaveable { mutableStateOf(false) }
    var beepSource by rememberSaveable { mutableStateOf(SignalSource.CASE) }

    LaunchedEffect(beepOn, beepSource) {
        if (!beepOn) return@LaunchedEffect
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        try {
            while (true) {
                val tracker = if (beepSource == SignalSource.BUDS) buds else case
                val smoothed = tracker.smoothed
                if (smoothed != null && System.currentTimeMillis() - tracker.lastSeen < LOST_AFTER_MS) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                    // About 1.4 s between beeps at the weakest signal, down to 0.15 s at the strongest.
                    delay((1400 - 1250 * strength(smoothed)).toLong())
                } else {
                    delay(300)
                }
            }
        } finally {
            tone.release()
        }
    }

    val scrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.find_title),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            SignalCard(
                title = stringResource(R.string.find_airpods),
                tracker = buds,
                now = now,
                detail = when (budSide) {
                    DeviceComponent.LEFT -> stringResource(R.string.find_via_left_bud)
                    DeviceComponent.RIGHT -> stringResource(R.string.find_via_right_bud)
                    else -> null
                }
            )

            SignalCard(
                title = stringResource(R.string.find_case),
                tracker = case,
                now = now,
                detail = null
            )

            StyledList(
                title = stringResource(R.string.find_beep_title),
                description = stringResource(R.string.find_how)
            ) {
                styledToggle(
                    label = stringResource(R.string.find_beep),
                    checked = beepOn,
                    onCheckedChange = { beepOn = it }
                )
                styledListItem(
                    contentText = stringResource(R.string.find_case),
                    selected = beepSource == SignalSource.CASE,
                    onClick = { beepSource = SignalSource.CASE }
                )
                styledListItem(
                    contentText = stringResource(R.string.find_airpods),
                    selected = beepSource == SignalSource.BUDS,
                    onClick = { beepSource = SignalSource.BUDS }
                )
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun SignalCard(
    title: String,
    tracker: SignalTracker,
    now: Long,
    detail: String?
) {
    val smoothed = tracker.smoothed
    val age = now - tracker.lastSeen
    val lost = smoothed == null || age > LOST_AFTER_MS

    val headline = when {
        smoothed == null -> stringResource(R.string.find_searching)
        lost -> stringResource(R.string.find_not_seen, (age / 1000).toInt())
        else -> stringResource(proximityLabel(smoothed))
    }

    val trend = if (lost) null else tracker.trend(now)
    val trendText = when {
        trend == null -> null
        trend >= TREND_THRESHOLD_DB -> stringResource(R.string.find_warmer)
        trend <= -TREND_THRESHOLD_DB -> stringResource(R.string.find_colder)
        else -> stringResource(R.string.find_steady)
    }

    val meter by animateFloatAsState(
        targetValue = if (smoothed == null) 0f else strength(smoothed),
        animationSpec = tween(250),
        label = "meter"
    )

    StyledList(title = title) {
        styledListItem(
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (lost) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(meter)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (lost) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                                )
                        )
                    }

                    val parts = buildList {
                        smoothed?.let { add("${it.roundToInt()} dBm") }
                        trendText?.let { add(it) }
                        if (!lost) detail?.let { add(it) }
                        add(stringResource(R.string.find_adverts_per_second, tracker.ratePerSecond(now)))
                    }
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

/** 0 at -100 dBm or weaker, 1 at -40 dBm or stronger. */
private fun strength(rssi: Float): Float = ((rssi + 100f) / 60f).coerceIn(0f, 1f)

// Rough bands for AirPods adverts; walls and bodies shift them, so the trend matters more.
private fun proximityLabel(rssi: Float): Int = when {
    rssi >= -50f -> R.string.find_very_close
    rssi >= -62f -> R.string.find_close
    rssi >= -75f -> R.string.find_nearby
    rssi >= -85f -> R.string.find_far
    else -> R.string.find_very_far
}

/** Smooths one source's readings and remembers enough of them for a trend and a rate. */
private class SignalTracker {
    var smoothed by mutableStateOf<Float?>(null)
        private set
    var lastSeen by mutableLongStateOf(0L)
        private set
    private var version by mutableIntStateOf(0)

    private val history = ArrayDeque<Pair<Long, Float>>()

    fun add(rssi: Int, timeMillis: Long) {
        val next = smoothed?.let { it + SMOOTHING * (rssi - it) } ?: rssi.toFloat()
        smoothed = next
        lastSeen = timeMillis
        history.addLast(timeMillis to next)
        while (history.isNotEmpty() && timeMillis - history.first().first > 6_000) history.removeFirst()
        version++
    }

    /** Change in dB of the smoothed signal over the last TREND_WINDOW_MS, or null without enough history. */
    fun trend(now: Long): Float? {
        version
        val current = smoothed ?: return null
        val past = history.lastOrNull { now - it.first >= TREND_WINDOW_MS } ?: return null
        return current - past.second
    }

    /** Adverts per second over the last 5 s. */
    fun ratePerSecond(now: Long): Float {
        version
        val count = history.count { now - it.first <= 5_000 }
        return (count / 5f * 10f).roundToInt() / 10f
    }
}

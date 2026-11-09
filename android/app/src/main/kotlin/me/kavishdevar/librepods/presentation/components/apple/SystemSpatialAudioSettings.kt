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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.spatial.system.SurroundTest
import me.kavishdevar.librepods.spatial.system.SystemHeadTracker
import me.kavishdevar.librepods.spatial.system.SystemHeadTracker.ShizukuStatus
import kotlin.math.roundToInt

/** Roadmap #17: the AirPods as Android's head tracker, for spatial audio in every app. */
@Composable
fun SystemSpatialAudioSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = SystemHeadTracker.state.collectAsState().value
    val testing = SurroundTest.playing.collectAsState().value
    LaunchedEffect(Unit) { SystemHeadTracker.refresh() }
    val yaw = s.yawDeg.roundToInt()
    val sp = s.spatializer

    StyledList(
        modifier = modifier,
        key = listOf(s, testing, yaw),
        title = "System-wide spatial audio (experimental)",
        description = "Lets Android's own spatial audio follow your head through the AirPods, in every app " +
            "that plays spatial audio. Needs Shizuku."
    ) {
        styledToggle(
            label = "AirPods as head tracker",
            description = when {
                !s.enabled -> "Off"
                s.shizuku == ShizukuStatus.NOT_INSTALLED -> "Install Shizuku first"
                s.shizuku == ShizukuStatus.NOT_RUNNING -> "Start Shizuku (open it and tap Start)"
                s.shizuku == ShizukuStatus.NOT_ALLOWED -> "Allow LibrePods in Shizuku (below)"
                !s.airPods -> "Waiting for your AirPods to connect"
                !s.created -> if (s.failed) "Couldn't create the head tracker; retrying" else "Creating the head tracker…"
                !s.androidSees -> "Created; waiting for Android to pick it up"
                s.streaming && s.androidReading == true ->
                    "Android is following your head: ${if (yaw == 0) "ahead" else if (yaw > 0) "$yaw° right" else "${-yaw}° left"}"
                s.streaming -> "Android sees the AirPods as a head tracker (following, ${if (yaw > 0) "$yaw° right" else if (yaw < 0) "${-yaw}° left" else "ahead"})"
                else -> "Android sees the AirPods as a head tracker; it reads it while spatial audio plays"
            },
            checked = s.enabled,
            onCheckedChange = { SystemHeadTracker.setEnabled(it) },
        )
        if (s.enabled && s.shizuku == ShizukuStatus.NOT_ALLOWED) {
            styledListItem(
                contentText = "Allow in Shizuku",
                supportingText = "Shizuku asks whether LibrePods may use it",
                onClick = { SystemHeadTracker.requestPermission() },
            )
        }
        styledListItem(
            contentText = "Android's spatial audio",
            supportingText = when {
                !sp.supported -> "This phone has no spatial audio"
                !sp.enabled -> "Off: turn on Spatial audio in the AirPods' Bluetooth settings"
                !sp.available -> "On, but not for what's playing to right now"
                sp.stereo -> "On, for stereo and surround audio"
                sp.multichannel -> "On, for surround (5.1 and up) audio; stereo plays as normal"
                else -> "On"
            } + if (s.androidSees) ". Turn on Head tracking there too." else "",
        )
        styledListItem(
            contentText = if (testing) "Stop the test sound" else "Play a 5.1 test sound",
            supportingText = "Soft bursts from the centre speaker; they should stay put as you turn",
            onClick = { SurroundTest.toggle(context) },
        )
        if (s.created) {
            styledListItem(
                contentText = "Recentre",
                supportingText = "Make where you're facing the front",
                onClick = { SystemHeadTracker.recentre() },
            )
        }
    }
}

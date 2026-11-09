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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.spatial.SpatialPlayer
import kotlin.math.roundToInt

/** Roadmap #16: a song played as two speakers that stay put while the head turns. */
@Composable
fun SpatialAudioSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = SpatialPlayer.state.collectAsState().value
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) SpatialPlayer.open(context, uri)
    }
    val yaw = s.yawDeg.roundToInt()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StyledList(
            // Rows come and go with the song; the angle keeps the readout current.
            key = listOf(s.title != null, s.playing, s.tracking, s.headTracked, yaw, s.positionMs / 1000, s.error),
            title = "Spatial audio (experimental)",
            description = "Plays a song from your phone as two speakers in front of you that stay put while you turn " +
                "your head. Only for songs played here."
        ) {
            styledListItem(
                contentText = s.title ?: "Choose a song",
                supportingText = s.error?.let { "Couldn't play it: $it" }
                    ?: if (s.title == null) "Any audio file on this phone" else "${time(s.positionMs)} / ${time(s.durationMs)} · tap to choose another",
                onClick = { pick.launch(arrayOf("audio/*")) },
            )
            if (s.title != null) {
                styledListItem(
                    contentText = if (s.playing) "Pause" else "Play",
                    onClick = { if (s.playing) SpatialPlayer.pause() else SpatialPlayer.play() },
                )
            }
            styledToggle(
                label = "Head tracking",
                description = when {
                    !s.tracking -> "Off: the speakers turn with your head, like normal headphones"
                    s.headTracked -> "Facing ${if (yaw == 0) "ahead" else if (yaw > 0) "$yaw° right" else "${-yaw}° left"}"
                    s.playing -> "Waiting for the AirPods' motion"
                    else -> "Follows your head while a song plays"
                },
                checked = s.tracking,
                onCheckedChange = { SpatialPlayer.setTracking(it) },
            )
            styledListItem(
                contentText = "Recentre",
                supportingText = "Make where you're facing the front",
                onClick = { SpatialPlayer.recentre() },
            )
        }
        StyledSlider(
            label = "Speaker width: ${s.spreadDeg.roundToInt()}° each side",
            value = s.spreadDeg,
            onValueChange = { SpatialPlayer.setSpread(it) },
            valueRange = 10f..90f,
            snapPoints = listOf(30f),
            independent = true,
        )
        StyledSlider(
            label = "Bluetooth delay compensation: ${s.lookaheadMs} ms",
            value = s.lookaheadMs.toFloat(),
            onValueChange = { SpatialPlayer.setLookahead(it.roundToInt()) },
            valueRange = 0f..300f,
            snapPoints = listOf(120f),
            independent = true,
        )
    }
}

private fun time(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

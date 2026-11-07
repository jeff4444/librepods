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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.presentation.components.apple.stemActionChoices
import me.kavishdevar.librepods.presentation.components.apple.stemActionLabel
import me.kavishdevar.librepods.presentation.components.apple.stemPressLabel
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

/** Picks what a single, double or triple press does, for each bud. */
@Composable
fun StemPressScreen(
    viewModel: AppleViewModel,
    pressType: StemPressType,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings
    val scrollState = rememberScrollState()

    StyledScaffold(
        title = stemPressLabel(pressType),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            listOf(StemPressBud.LEFT, StemPressBud.RIGHT).forEachIndexed { index, bud ->
                if (index > 0) Spacer(modifier = Modifier.height(24.dp))
                val current = settings.stemAction(bud, pressType)
                StyledList(
                    title = stringResource(if (bud == StemPressBud.LEFT) R.string.left else R.string.right),
                    description = if (bud == StemPressBud.RIGHT) stringResource(R.string.stem_presses_description) else null
                ) {
                    stemActionChoices.forEach { action ->
                        styledListItem(
                            contentText = stemActionLabel(action),
                            supportingText = if (action == StemAction.AUTOMATION) stringResource(R.string.stem_action_automation_description) else null,
                            selected = current == action,
                            onClick = { viewModel.setStemAction(bud, pressType, action) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

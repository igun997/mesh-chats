package com.meshchats.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meshchats.app.ui.components.HairlineDivider
import com.meshchats.app.ui.navigation.TopLevelDestination
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshSpec
import com.meshchats.app.ui.theme.MetaTextStyle

/**
 * Four tabs split 2/2 around the docked SOS button. Selection is shown three ways
 * (filled glyph, full-opacity label, 3dp underline) since color is unavailable.
 */
@Composable
fun MeshBottomBar(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations = TopLevelDestination.entries

    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.take(2).forEach { destination ->
                TabItem(
                    destination = destination,
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }

            // Reserved space for the docked SOS control.
            Spacer(Modifier.width(MeshSpec.sosDockSize + 16.dp))

            destinations.drop(2).forEach { destination ->
                TabItem(
                    destination = destination,
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMeshTokens.current
    val contentColor = if (selected) tokens.glyphActive else tokens.glyphIdle

    Column(
        modifier = modifier
            .height(MeshSpec.minTouchTarget + 8.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = destination.label,
            style = MetaTextStyle,
            color = contentColor,
            modifier = Modifier.padding(top = 2.dp),
        )
        Box(
            Modifier
                .padding(top = 3.dp)
                .width(18.dp)
                .height(3.dp)
                .background(if (selected) tokens.glyphActive else androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

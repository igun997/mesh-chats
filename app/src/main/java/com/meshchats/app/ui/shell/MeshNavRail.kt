package com.meshchats.app.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meshchats.app.ui.components.SosDock
import com.meshchats.app.ui.navigation.TopLevelDestination
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MetaTextStyle

/** Medium+ width layout: tabs move to a rail, SOS docks at the rail foot. */
@Composable
fun MeshNavRail(
    selected: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    onSosArmed: () -> Unit,
    showSos: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMeshTokens.current

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(88.dp)
            .safeDrawingPadding()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            val color = if (isSelected) tokens.glyphActive else tokens.glyphIdle
            Column(
                modifier = Modifier
                    .size(width = 64.dp, height = 56.dp)
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(destination) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (isSelected) {
                        destination.selectedIcon
                    } else {
                        destination.unselectedIcon
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp),
                )
                Text(destination.label, style = MetaTextStyle, color = color)
            }
        }

        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

        if (showSos) {
            SosDock(onArmed = onSosArmed)
        }
    }
}

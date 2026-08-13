package com.meshchats.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MetaTextStyle

/**
 * Outgoing bubbles use the highest surface step, never inversion: full inversion
 * stays reserved for SOS so it keeps its meaning.
 */
@Composable
fun MessageBubble(
    text: String,
    isOutgoing: Boolean,
    time: String,
    transport: TransportId,
    hops: Int,
    deliveryState: DeliveryState,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
) {
    val tokens = LocalMeshTokens.current
    val shape = RoundedCornerShape(16.dp)
    val spoken = buildString {
        append("${if (isOutgoing) "Sent" else "Received"}: $text. ")
        append("$time, ${transport.shortLabel}, $hops hops")
        if (isOutgoing) append(", ${deliveryState.spoken}")
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
    ) {
        BoxWithConstraints {
            val maxBubbleWidth = this.maxWidth * 0.8f
            Column(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(shape)
                .then(
                    if (isOutgoing) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    } else {
                        Modifier.border(1.dp, tokens.hairline, shape)
                    },
                )
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = spoken },
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(time, style = MetaTextStyle, color = tokens.meta)
                    Text("·", style = MetaTextStyle, color = tokens.meta)
                    Text(transport.shortLabel, style = MetaTextStyle, color = tokens.meta)
                    Text("·", style = MetaTextStyle, color = tokens.meta)
                    Text("${hops}h", style = MetaTextStyle, color = tokens.meta)
                    if (isOutgoing) DeliveryGlyph(deliveryState)
                }
            }
        }
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true, widthDp = 360)
@Composable
private fun MessageBubblePreviewDark() {
    MeshChatsTheme(darkTheme = true) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(12.dp)) {
            MessageBubble(
                text = "peer discovered on ble, handshake pending",
                isOutgoing = false,
                time = "18:42",
                transport = TransportId.BT,
                hops = 2,
                deliveryState = DeliveryState.DELIVERED,
            )
            MessageBubble(
                text = "handshake ok, verified fingerprint",
                isOutgoing = true,
                time = "18:43",
                transport = TransportId.WIFI,
                hops = 1,
                deliveryState = DeliveryState.SENT,
            )
            MessageBubble(
                text = "queued for lora window",
                isOutgoing = true,
                time = "18:44",
                transport = TransportId.LORA,
                hops = 3,
                deliveryState = DeliveryState.QUEUED,
            )
            MessageBubble(
                text = "no route to peer",
                isOutgoing = true,
                time = "18:45",
                transport = TransportId.RELAY,
                hops = 0,
                deliveryState = DeliveryState.FAILED,
            )
        }
    }
}

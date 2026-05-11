package com.viel.aplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.ui.utils.formatTime

@Composable
fun PlaybackProgress(
    currentPosition: () -> Long,
    duration: () -> Long,
    markers: List<Float>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val progressProvider = remember(currentPosition, duration) {
            {
                val d = duration()
                if (d > 0) (currentPosition().toFloat() / d.toFloat()) else 0f
            }
        }

        AudioProgressBar(
            progress = progressProvider,
            onProgressChange = { newProgress ->
                val d = duration()
                if (d > 0) {
                    onSeek((newProgress * d).toLong())
                }
            },
            markers = markers,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentPosition()),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = formatTime(duration()),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PlaybackProgressPreview() {
    APlayerTheme {
        Surface {
            PlaybackProgress(
                currentPosition = { 120000L },
                duration = { 360000L },
                markers = listOf(0.2f, 0.5f, 0.8f),
                onSeek = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

package com.viel.aplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.viel.aplayer.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.ui.theme.APlayerTheme
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactMediaPlayer(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    title: String = "Audiobook Title",
    author: String = "Unknown Author",
    narrator: String = "",
    coverPath: String? = null,
    progress: () -> Float = { 0f },
    showProgressBar: Boolean = true,
    onPlayPauseClick: () -> Unit = {},
    onLongClickCover: () -> Unit = {}, // 测试操作：长按封面回调
) {
    val subtitle = remember(author, narrator) {
        listOf(author, narrator)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .ifBlank { "Unknown" }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        // Remove top rounded corners
        shape = RoundedCornerShape(0.dp)
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Progress bar at the very top, not clipped
            if (showProgressBar) {
                AudioProgressBar(
                    progress = progress,
                    onProgressChange = {}, // Read-only in compact player
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    showKnob = false
                )
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val coverShape = RoundedCornerShape(8.dp)
                val coverModifier = Modifier.size(48.dp)

                Box(
                    modifier = coverModifier
                        .clip(coverShape)
                        .combinedClickable(
                            onClick = {}, // 保持点击穿透或根据需要处理
                            onLongClick = onLongClickCover // 测试操作：长按封面隐藏 mini 播放器
                        )
                ) {
                    val coverFile = remember(coverPath) { if (coverPath != null) File(coverPath) else null }
                    if (coverFile != null && coverFile.exists()) {
                        AsyncImage(
                            model = coverFile,
                            contentDescription = "Cover",
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                }
                
                IconButton(
                    onClick = onPlayPauseClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = if (isPlaying) painterResource(R.drawable.ic_rounded_pause) else painterResource(R.drawable.ic_rounded_play_arrow),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "イン・ザ・メガチャーチ",
            author = "朝井 リョウ",
            narrator = "岩崎 了",
            isPlaying = false,
            progress = { 0.23f }
        )
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerPlayingPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "イン・ザ・メガチャーチ",
            author = "朝井 リョウ",
            isPlaying = true,
            progress = { 0.65f }
        )
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun CompactMediaPlayerNoProgressBarPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "イン・ザ・メガチャーチ",
            author = "朝井 リョウ",
            isPlaying = false,
            showProgressBar = false
        )
    }
}

@Preview(showBackground = true, name = "Long Title & Narrator", apiLevel = 36)
@Composable
fun CompactMediaPlayerLongTitlePreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "転生したらスライムだった件 21 (通常版) (デジタル版有声書)",
            author = "伏瀬",
            narrator = "岡咲 美保, 豊口 めぐみ, 前野 智昭",
            isPlaying = true,
            progress = { 0.45f }
        )
    }
}

@Preview(showBackground = true, name = "Author Only", apiLevel = 36)
@Composable
fun CompactMediaPlayerAuthorOnlyPreview() {
    APlayerTheme {
        CompactMediaPlayer(
            title = "晓星",
            author = "凑 かなえ",
            narrator = "",
            isPlaying = false,
            progress = { 0.12f }
        )
    }
}

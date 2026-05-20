package com.viel.aplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.theme.APlayerTheme

@Composable
fun RecentlyItem(
    title: String,
    author: String,
    narrator: String,
    progressText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
    coverLastUpdated: Long = 0L // 详尽中文注释：用于传递封面文件自愈重建时间戳，用以触发响应式强打破缓存
) {
    Column(
        modifier = modifier
            .width(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if ((coverPath != null) && File(coverPath).exists()) {
                // 详尽中文注释：使用 LocalContext 构建附带 lastScannedAt 作为更新戳的 ImageRequest，在底层打破 Coil 对于相同物理文件的本地与内存缓存
                val context = androidx.compose.ui.platform.LocalContext.current
                val request = remember(coverPath, coverLastUpdated) {
                    coil.request.ImageRequest.Builder(context)
                        .data(File(coverPath))
                        .memoryCacheKey("$coverPath?t=$coverLastUpdated")
                        .diskCacheKey("$coverPath?t=$coverLastUpdated")
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { state ->
                        // 详尽中文注释：当封面物理文件损坏、Scoped Storage 权限临时受阻等加载失败时，在控制台打印高清晰的可调试路径和根本原因
                        android.util.Log.e(
                            "RecentlyItem",
                            "RecentlyItem 封面加载失败！物理路径: $coverPath, 原因: ${state.result.throwable.message}",
                            state.result.throwable
                        )
                    }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            // Progress Badge
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = progressText,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatPeopleSubtitle(
                author.takeIf { it.isNotBlank() } ?: "Unknown",
                narrator.takeIf { it.isNotBlank() } ?: "Unknown"
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview(showBackground = true, name = "Recently Item NEW")
@Composable
fun RecentlyItemNewPreview() {
    APlayerTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            RecentlyItem(
                title = "The Great Adventure",
                author = "John Doe",
                narrator = "Jane Smith",
                progressText = "NEW",
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Recently Item Progress")
@Composable
fun RecentlyItemProgressPreview() {
    APlayerTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            RecentlyItem(
                title = "In the Megachurch",
                author = "Ryo Asai",
                narrator = "Unknown",
                progressText = "45%",
                onClick = {}
            )
        }
    }
}
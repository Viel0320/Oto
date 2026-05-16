package com.viel.aplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.ui.utils.formatCompactDuration
import com.viel.aplayer.ui.utils.formatPeopleSubtitle
import com.viel.aplayer.ui.theme.APlayerTheme
import java.io.File

@Composable
fun AudiobookListItem(
    title: String,
    author: String,
    narrator: String,
    duration: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
    progressPercent: Int? = null,
//    addedAt: Long? = null,
    onPlayClick: () -> Unit = {}
) {
    ListItem(
        modifier = modifier.clickable { onClick() },
        headlineContent = { 
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    title, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                ) 
                Text(
                    formatPeopleSubtitle(author, narrator), 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val separator = " • "
                    val textStyle = MaterialTheme.typography.labelSmall
                    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

                    if (progressPercent != null && progressPercent > 0) {
                        Text(
                            text = "$progressPercent%",
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = separator, style = textStyle, color = textColor)
                    } else {
                        Text(
                            text = "NEW",
                            style = textStyle,
                            color = textColor
                        )
                        Text(text = separator, style = textStyle, color = textColor)
                    }

                    Text(
                        text = formatCompactDuration(duration),
                        style = textStyle,
                        color = textColor
                    )
//
//                    if (addedAt != null) {
//                        Text(text = separator, style = textStyle, color = textColor)
//                        Text(
//                            text = formatShortDate(addedAt) + " added at " + formatShortDate(addedAt) ,
//                            style = textStyle,
//                            color = textColor
//                        )
//                    }
                }
            }
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                val isPreview = LocalInspectionMode.current
                if (!isPreview && (coverPath != null) && File(coverPath).exists()) {
                    AsyncImage(
                        model = File(coverPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onPlayClick) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play")
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Preview(showBackground = true, name = "New Book", apiLevel = 36)
@Composable
fun AudiobookListItemNewPreview() {
    APlayerTheme(dynamicColor = false) {
        Surface {
            AudiobookListItem(
                title = "The Great Adventure",
                author = "John Doe",
                narrator = "Jane Smith",
                duration = 3600000L,
                progressPercent = 0,
//                addedAt = System.currentTimeMillis(),
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "In Progress", apiLevel = 36)
@Composable
fun AudiobookListItemProgressPreview() {
    APlayerTheme(dynamicColor = false) {
        Surface {
            AudiobookListItem(
                title = "Mystery in the Woods",
                author = "Arthur Conan Doyle",
                narrator = "Stephen Fry",
                duration = 7200000L,
                progressPercent = 45,
//                addedAt = System.currentTimeMillis() - 86400000,
                onClick = {}
            )
        }
    }
}

package com.viel.aplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.utils.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    isVisible: Boolean,
    chapters: List<ChapterEntity>,
    currentChapter: ChapterEntity?,
    onDismissRequest: () -> Unit,
    onChapterClick: (Long) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    if (isVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            val listState = rememberLazyListState()
            
            // Auto-scroll to current chapter when sheet is shown
            LaunchedEffect(isVisible) {
                if (isVisible && chapters.isNotEmpty()) {
                    val index = chapters.indexOf(currentChapter).coerceAtLeast(0)
                    if (index >= 0) {
                        listState.scrollToItem(index)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = "Chapters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (chapters.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No chapters available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        itemsIndexed(chapters) { index, chapter ->
                            val isCurrent = chapter == currentChapter
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = chapter.title,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingContent = {
                                    Text(
                                        text = (index + 1).toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingContent = {
                                    Text(
                                        text = formatTime(chapter.startPosition),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onChapterClick(chapter.startPosition)
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
                                )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

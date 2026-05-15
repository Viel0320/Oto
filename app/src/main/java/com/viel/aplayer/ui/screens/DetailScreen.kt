package com.viel.aplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.compose.ui.graphics.toArgb
import coil.compose.AsyncImage
import com.viel.aplayer.R
import com.viel.aplayer.ui.state.DetailUiState
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.ui.utils.formatFileSize
import com.viel.aplayer.ui.utils.formatTime
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    uiState: DetailUiState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    onSearchClick: (String) -> Unit = {},
) {
    val book = uiState.book
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }
    
    val animatedBgColor by animateColorAsState(
        targetValue = Color(uiState.backgroundColorArgb),
        animationSpec = tween(300),
        label = "bg_color"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painterResource(R.drawable.ic_rounded_arrow_back),
                            contentDescription = stringResource(R.string.back_content_description)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            painterResource(R.drawable.ic_rounded_more_vert),
                            contentDescription = stringResource(R.string.more_content_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            animatedBgColor.copy(alpha = 0.6f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Cover Art
                Surface(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp
                ) {
                    val coverPath = book?.thumbnailPath ?: book?.coverPath
                    if ((coverPath != null) && File(coverPath).exists()) {
                        AsyncImage(
                            model = File(coverPath),
                            contentDescription = "Cover",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_rounded_play_arrow),
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Title
                SelectionContainer {
                    Text(
                        text = book?.title ?: "Unknown Title",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Author & Narrator Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = { book?.author?.let { onSearchClick("author:$it") } },
                                onLongClick = { 
                                    infoDialogTitle = "Author"
                                    infoDialogText = book?.author 
                                }
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.author_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = book?.author ?: "Unknown Author",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .height(32.dp)
                            .padding(horizontal = 8.dp),
                        thickness = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = { book?.narrator?.let { onSearchClick("narrator:$it") } },
                                onLongClick = { 
                                    infoDialogTitle = "Narrator"
                                    infoDialogText = book?.narrator 
                                }
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.narrator_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = book?.narrator ?: "Unknown Narrator",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Metadata Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DetailInfoChip(
                        icon = painterResource(R.drawable.ic_rounded_event),
                        value = if (!book?.year.isNullOrBlank()) book.year else "Unknown"
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    DetailInfoChip(
                        icon = painterResource(R.drawable.ic_rounded_timelapse),
                        value = formatTime(book?.duration ?: 0L)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    DetailInfoChip(
                        icon = painterResource(R.drawable.ic_rounded_storage),
                        value = formatFileSize(book?.fileSize ?: 0L)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Play/Continue Button
                Button(
                    onClick = { if (uiState.isAvailable) onPlayClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = if (uiState.isAvailable) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                ) {
                    Icon(
                        painter = if (!uiState.isAvailable) painterResource(R.drawable.ic_rounded_storage) 
                        else if (uiState.progressPercent > 0) painterResource(R.drawable.ic_rounded_history) 
                        else painterResource(R.drawable.ic_rounded_play_arrow),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (!uiState.isAvailable) "File not found"
                               else if (uiState.progressPercent > 0) "Continue at ${uiState.progressPercent}%" 
                               else "Start Listening",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Description/Summary
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.summary_label),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val htmlDescription = book?.description ?: ""
                    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                    
                    AndroidView(
                        factory = { context ->
                            TextView(context).apply {
                                setTextColor(textColor)
                                textSize = 16f
                                setLineSpacing(0f, 1.2f)
                                setTextIsSelectable(true)
                            }
                        },
                        update = { textView ->
                            val spanned = HtmlCompat.fromHtml(htmlDescription, HtmlCompat.FROM_HTML_MODE_COMPACT)
                            val spannable = android.text.SpannableStringBuilder(spanned)
                            
                            // 计算两个字符的缩进像素值 (使用当前 TextView 的字体测量)
                            val indentPx = textView.paint.measureText("\u3000\u3000").toInt()
                            
                            spannable.setSpan(
                                android.text.style.LeadingMarginSpan.Standard(indentPx, 0),
                                0,
                                spannable.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            textView.text = spannable
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
            }
        }
    }

    // Full info dialog for long press
    if (infoDialogText != null) {
        AlertDialog(
            onDismissRequest = {
            },
            confirmButton = {
                TextButton(onClick = {
                }) {
                    Text("OK")
                }
            },
            title = { infoDialogTitle?.let { Text(it) } },
            text = { 
                SelectionContainer {
                    Text(
                        text = infoDialogText!!,
                        style = MaterialTheme.typography.bodyLarge // 内容文字调大
                    )
                }
            }
        )
    }
}

@Composable
fun DetailInfoChip(
    icon: androidx.compose.ui.graphics.painter.Painter,
    value: String,
    modifier: Modifier = Modifier
) {
    SuggestionChip(
        onClick = { },
        label = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        },
        icon = {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                tint = LocalContentColor.current
            )
        },
        shape = RoundedCornerShape(12.dp),
        colors = SuggestionChipDefaults.suggestionChipColors(
            labelColor = LocalContentColor.current,
            iconContentColor = LocalContentColor.current
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(
            enabled = true,
            borderColor = LocalContentColor.current
        ),
        modifier = modifier
    )
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun DetailScreenPreview() {
    APlayerTheme {
        DetailScreen(
            uiState = DetailUiState(
                book = com.viel.aplayer.data.AudiobookEntity(
                    uri = "uri",
                    title = "In the Megachurch",
                    author = "Ryo Asai",
                    narrator = "Narrator A",
                    duration = 3600000L,
                    year = "2023",
                    fileSize = 45000000L,
                    description = "A preview description for the audiobook detail screen."
                ),
                isAvailable = true,
                progressPercent = 45
            ),
            onBackClick = {}
        )
    }
}

package com.viel.aplayer.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.LeadingMarginSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
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
                SelectableTextView(
                    text = book?.title ?: "Unknown Title",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    textColor = MaterialTheme.colorScheme.onSurface,
                    textSizeSp = 28f,
                    lineSpacingExtraSp = 0f,
                    gravity = Gravity.CENTER,
                    typefaceStyle = android.graphics.Typeface.BOLD
                )

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
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = book?.author ?: "Unknown Author",
                            style = MaterialTheme.typography.titleMedium.copy(
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
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = book?.narrator ?: "Unknown Narrator",
                            style = MaterialTheme.typography.titleMedium.copy(
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
                    
                    SelectableTextView(
                        text = HtmlCompat.fromHtml(htmlDescription, HtmlCompat.FROM_HTML_MODE_COMPACT),
                        modifier = Modifier.fillMaxWidth(),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        textSizeSp = 16f,
                        lineSpacingExtraSp = 4f,
                        firstLineIndentEm = 2f
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
                infoDialogText = null
                infoDialogTitle = null
            },
            confirmButton = {
                TextButton(onClick = {
                    infoDialogText = null
                    infoDialogTitle = null
                }) {
                    Text("OK")
                }
            },
            title = { infoDialogTitle?.let { Text(it) } },
            text = {
                SelectableTextView(
                    text = infoDialogText!!,
                    modifier = Modifier.fillMaxWidth(),
                    textColor = MaterialTheme.colorScheme.onSurface,
                    textSizeSp = 16f,
                    lineSpacingExtraSp = 4f
                )
            }
        )
    }
}

@Composable
private fun SelectableTextView(
    text: CharSequence,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSizeSp: Float = 16f,
    lineSpacingExtraSp: Float = 0f,
    firstLineIndentEm: Float = 0f,
    gravity: Int = Gravity.START,
    typefaceStyle: Int = android.graphics.Typeface.NORMAL
) {
    val textColorInt = textColor.toArgb()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineSpacingExtraPx = with(density) { lineSpacingExtraSp.sp.toPx() }
    val firstLineIndentPx = with(density) {
        (textSizeSp * firstLineIndentEm).sp.toPx().toInt()
    }
    val displayText = remember(text, firstLineIndentPx) {
        if (firstLineIndentPx > 0) {
            SpannableStringBuilder(text).apply {
                setSpan(
                    LeadingMarginSpan.Standard(firstLineIndentPx, 0),
                    0,
                    length,
                    0
                )
            }
        } else {
            text
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                background = null
                setPadding(0, 0, 0, 0)
                setHorizontallyScrolling(false)
                customSelectionActionModeCallback = ProcessTextMenuCallback(this)
            }
        },
        update = { tv ->
            if (tv.text?.toString() != displayText.toString()) {
                tv.text = displayText
            }
            tv.setTextColor(textColorInt)
            tv.textSize = textSizeSp
            tv.gravity = gravity
            tv.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                typefaceStyle
            )
            tv.setLineSpacing(lineSpacingExtraPx, 1.0f)
        }
    )
}

private class ProcessTextMenuCallback(
    private val textView: TextView
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val pm = textView.context.packageManager
        val baseIntent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(baseIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(baseIntent, 0)
        }
        activities.forEachIndexed { index, ri ->
            val label = ri.loadLabel(pm).toString()
            if (menu.findItem(label.hashCode()) == null) {
                menu.add(Menu.NONE, label.hashCode(), 100 + index, label)
                    .setIntent(
                        Intent(baseIntent)
                            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    )
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val intent = item.intent ?: return false
        val s = textView.selectionStart.coerceAtLeast(0)
        val e = textView.selectionEnd.coerceAtLeast(0)
        val text = textView.text.subSequence(minOf(s, e), maxOf(s, e)).toString()
        if (text.isEmpty()) return false
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        return try {
            textView.context.startActivity(intent)
            mode.finish()
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
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

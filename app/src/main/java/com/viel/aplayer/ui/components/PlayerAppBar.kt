package com.viel.aplayer.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.viel.aplayer.R
import androidx.compose.ui.tooling.preview.Preview
import com.viel.aplayer.ui.theme.APlayerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerAppBar(
    title: String,
    author: String,
    narrator: String,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: Painter? = null,
    onActionClick: () -> Unit = {},
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current
) {
    val navPainter = navigationIcon ?: painterResource(R.drawable.ic_rounded_keyboard_arrow_down)
    CenterAlignedTopAppBar(
        modifier = modifier.statusBarsPadding(),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                val subtitle = remember(author, narrator) {
                    listOf(author, narrator)
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                        .ifBlank { "Unknown" }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (contentColor == Color.White) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(
                    painter = navPainter,
                    contentDescription = "Back",
                    tint = contentColor
                )
            }
        },
        actions = {
            IconButton(onClick = onActionClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_rounded_more_vert),
                    contentDescription = "More",
                    tint = contentColor
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = Color.Unspecified,
            navigationIconContentColor = Color.Unspecified,
            titleContentColor = Color.Unspecified,
            actionIconContentColor = Color.Unspecified
        )
    )
}

@Preview(apiLevel = 36)
@Composable
fun PlayerAppBarPreview() {
    APlayerTheme {
        Surface(color = Color(0xFF1C1B1F)) {
            PlayerAppBar(
                title = "暁星",
                author = "湊 かなえ",
                narrator = "大森 ゆき",
                onNavigationClick = {},
                contentColor = Color.White
            )
        }
    }
}

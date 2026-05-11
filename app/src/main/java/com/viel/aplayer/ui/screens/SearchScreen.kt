package com.viel.aplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.viel.aplayer.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.theme.APlayerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(value = true) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { /* todo Execute search */ },
                        expanded = active,
                        onExpandedChange = { if (!it) onBack() },
                        placeholder = { Text("Search your library...") },
                        leadingIcon = {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_rounded_arrow_back), contentDescription = "Back")
                            }
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(painterResource(R.drawable.ic_rounded_clear), contentDescription = "Clear")
                                }
                            }
                        }
                    )
                },
                expanded = active,
                onExpandedChange = { 
                    if (!it) onBack() 
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "Suggested",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(5) { index ->
                        ListItem(
                            modifier = Modifier.clickable { onNavigateToDetail("dummy_uri_$index") },
                            headlineContent = { Text("Search Result ${index + 1}", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Author Name • Audiobook") },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painterResource(R.drawable.ic_rounded_search),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    ) { padding ->
        // This is empty because SearchBar covers the screen when active
        Box(modifier = Modifier.padding(padding))
    }
}

@Preview(showBackground = true)
@Composable
fun SearchScreenPreview() {
    APlayerTheme {
        SearchScreen()
    }
}

package com.viel.oto.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.viel.oto.R
import com.viel.oto.application.library.search.SearchHistoryItem
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.theme.OtoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks touch target semantics for search shell commands.
 *
 * Exercises SearchContent directly so history command regressions are caught without loading the
 * route, ViewModel, or app dependency di that is unrelated to the accessibility contract.
 */
@RunWith(AndroidJUnit4::class)
class SearchContentAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clearAllHistoryCommandExposesButtonClickActionAndMinimumTouchTarget() {
        val clearAllText = composeRule.activity.getString(R.string.search_clear_all)

        composeRule.setContent {
            OtoTheme(dynamicColor = false) {
                SearchContent(
                    query = TextFieldValue(""),
                    searchResults = emptyList(),
                    searchHistory = listOf(SearchHistoryItem(query = "recent query", createdAt = 1L)),
                    commandSuggestions = emptyList(),
                    onQueryChange = {},
                    onSearch = {},
                    onClearQuery = {},
                    onDeleteHistory = {},
                    onClearHistory = {},
                    onBack = {},
                    onNavigateToDetail = {},
                    onLoadBook = {},
                    onNavigateToPlayer = {},
                    glassEffectMode = GlassEffectMode.Material,
                    autoFocus = false
                )
            }
        }

        composeRule
            .onNodeWithText(clearAllText)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}

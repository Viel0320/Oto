package com.viel.aplayer.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.viel.aplayer.R
import com.viel.aplayer.application.library.search.SearchHistoryItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.APlayerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search Content Accessibility Test (Locks touch target semantics for search shell commands)
 *
 * Exercises SearchContent directly so history command regressions are caught without loading the
 * route, ViewModel, or app dependency graph that is unrelated to the accessibility contract.
 */
@RunWith(AndroidJUnit4::class)
class SearchContentAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clearAllHistoryCommandExposesButtonClickActionAndMinimumTouchTarget() {
        val clearAllText = composeRule.activity.getString(R.string.search_clear_all)

        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                // Search History Fixture (Render the history header that owns the Clear All command)
                // A single history row is enough to compose the command while keeping the test focused on button semantics.
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

        // Clear History Button Contract (Verify accessibility action and minimum target size together)
        // The assertion fails if the command is rendered as raw clickable text instead of a Material button.
        composeRule
            .onNodeWithText(clearAllText)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}

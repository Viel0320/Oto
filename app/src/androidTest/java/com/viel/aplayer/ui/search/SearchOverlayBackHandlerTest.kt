package com.viel.aplayer.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.APlayerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search Overlay Back Handler Test (Locks system-back dismissal for the Home-hosted search shell)
 *
 * Verifies the overlay consumes Android system back without unmounting the Home root that sits
 * underneath it, matching the production Nav3 shell where Search is not its own destination.
 */
@RunWith(AndroidJUnit4::class)
class SearchOverlayBackHandlerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackDismissesSearchOverlayAndKeepsHomeMounted() {
        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                var isSearchVisible by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize()) {
                    Text(HOME_MOUNT_SENTINEL)
                    Button(
                        onClick = {
                            // Test Search Entry Point (Model the Home shell action that opens Search)
                            // Starting closed and opening by click exercises the overlay visibility transition before system back dismisses it.
                            isSearchVisible = true
                        }
                    ) {
                        Text(OPEN_SEARCH_SENTINEL)
                    }

                    SearchOverlay(
                        visible = isSearchVisible,
                        onBack = {
                            // Test Overlay Dismissal Command (Mirror SearchRoute's visibility mutation)
                            // The test drives the same state transition that production wires from SearchOverlay to SearchViewModel.
                            isSearchVisible = false
                        },
                        glassEffectMode = GlassEffectMode.Material
                    ) {
                        TextField(
                            value = "",
                            onValueChange = {},
                            // Search Field Sentinel (Expose a stable assertion target for overlay visibility)
                            // A plain text field keeps the test focused on the overlay shell instead of full search data dependencies.
                            label = { Text(SEARCH_FIELD_SENTINEL) }
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText(HOME_MOUNT_SENTINEL).assertIsDisplayed()
        composeRule.onNodeWithText(OPEN_SEARCH_SENTINEL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SEARCH_FIELD_SENTINEL).assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            // System Back Dispatch (Exercise Activity back handling instead of the SearchScreen UI button)
            // This reproduces the failing path where NavDisplay could not see the overlay-only search state.
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(350)

        // Search Overlay Removal Assertion (Check semantics tree directly after the exit animation)
        // This avoids depending on Compose test assertion helpers that vary across BOM versions.
        assertTrue(composeRule.onAllNodesWithText(SEARCH_FIELD_SENTINEL).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText(HOME_MOUNT_SENTINEL).assertIsDisplayed()
    }

    private companion object {
        // Home Mount Sentinel (Stable text proving the root route remains composed after overlay dismissal)
        // The assertion protects against accidentally converting overlay back into route-level pop behavior.
        private const val HOME_MOUNT_SENTINEL = "Home root remains mounted"

        // Search Field Sentinel (Stable text proving the search overlay content has left composition)
        // Using an app-local string avoids locale-dependent resource text in the instrumentation assertion.
        private const val SEARCH_FIELD_SENTINEL = "Search field sentinel"

        // Open Search Sentinel (Stable test-only entry point for opening the overlay)
        // This models Home chrome requesting Search without requiring the full application dependency graph.
        private const val OPEN_SEARCH_SENTINEL = "Open search sentinel"
    }
}

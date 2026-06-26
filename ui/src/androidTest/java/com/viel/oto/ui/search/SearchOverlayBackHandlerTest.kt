package com.viel.oto.ui.search

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
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.theme.OtoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks system-back dismissal for the Home-hosted search shell.
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
            OtoTheme(dynamicColor = false) {
                var isSearchVisible by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize()) {
                    Text(HOME_MOUNT_SENTINEL)
                    Button(
                        onClick = {
                            isSearchVisible = true
                        }
                    ) {
                        Text(OPEN_SEARCH_SENTINEL)
                    }

                    SearchOverlay(
                        visible = isSearchVisible,
                        onBack = {
                            isSearchVisible = false
                        },
                        glassEffectMode = GlassEffectMode.Material
                    ) {
                        TextField(
                            value = "",
                            onValueChange = {},
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
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(350)

        assertTrue(composeRule.onAllNodesWithText(SEARCH_FIELD_SENTINEL).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText(HOME_MOUNT_SENTINEL).assertIsDisplayed()
    }

    private companion object {
        private const val HOME_MOUNT_SENTINEL = "Home root remains mounted"

        private const val SEARCH_FIELD_SENTINEL = "Search field sentinel"

        private const val OPEN_SEARCH_SENTINEL = "Open search sentinel"
    }
}

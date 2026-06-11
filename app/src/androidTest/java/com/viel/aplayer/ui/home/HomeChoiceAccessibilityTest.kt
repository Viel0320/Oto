package com.viel.aplayer.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.HomeFilter
import com.viel.aplayer.ui.common.AudiobookActionDialog
import com.viel.aplayer.ui.common.AudiobookActionDialogBook
import com.viel.aplayer.ui.common.theme.APlayerTheme
import dev.chrisbanes.haze.HazeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home Choice Accessibility Test (Locks custom Home choice controls for assistive technology)
 *
 * Exercises the Home filter row and read-status dialog at their composition boundaries so custom
 * Haze and dialog chips cannot regress to visual-only selection cues.
 */
@RunWith(AndroidJUnit4::class)
class HomeChoiceAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hazeFilterChipsExposeRadioSelectionSemanticsAndMinimumTarget() {
        val selectedLabel = composeRule.activity.getString(R.string.filter_in_progress)
        val unselectedLabel = composeRule.activity.getString(R.string.filter_not_started)
        val selectedStateDescription = composeRule.activity.getString(R.string.accessibility_choice_selected)
        val unselectedStateDescription = composeRule.activity.getString(R.string.accessibility_choice_unselected)

        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                val homeHazeState = remember { HazeState() }

                // Haze Filter Regression Fixture (Render Home filters through the custom glass branch)
                // Passing Haze mode with a real HazeState exercises the Box-based chip path that previously exposed only raw clickable semantics.
                HomeContent(
                    selectedFilter = HomeFilter.InProgress,
                    glassEffectMode = GlassEffectMode.Haze,
                    homeHazeState = homeHazeState
                )
            }
        }

        // Filter Group Contract (Expose one mutually exclusive Home filter group)
        // The row-level marker lets assistive services relate the individual category chips as alternatives.
        composeRule.onAllNodes(hasSelectableGroup(), useUnmergedTree = true).assertCountEquals(1)

        // Selected Filter Contract (Expose the selected chip as a radio choice with a standard target)
        // This catches regressions where the Haze chip falls back to color-only state or a too-small custom Box.
        composeRule
            .onNode(hasText(selectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = true))
            .assert(hasStateDescription(selectedStateDescription))
            .assertHeightIsAtLeast(48.dp)

        // Unselected Filter Contract (Expose non-selected alternatives with the same role and target size)
        // Switch Access and TalkBack should still identify inactive chips as selectable choices.
        composeRule
            .onNode(hasText(unselectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = false))
            .assert(hasStateDescription(unselectedStateDescription))
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun materialFilterChipsUseCustomRadioSemanticsAndMinimumTarget() {
        val selectedLabel = composeRule.activity.getString(R.string.filter_in_progress)
        val unselectedLabel = composeRule.activity.getString(R.string.filter_not_started)
        val selectedStateDescription = composeRule.activity.getString(R.string.accessibility_choice_selected)
        val unselectedStateDescription = composeRule.activity.getString(R.string.accessibility_choice_unselected)

        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                val homeHazeState = remember { HazeState() }

                // Material Filter Regression Fixture (Render Home filters through the non-Haze custom branch)
                // The custom chip must keep radio semantics and 48.dp targets even when no glass effect is active.
                HomeContent(
                    selectedFilter = HomeFilter.InProgress,
                    glassEffectMode = GlassEffectMode.Material,
                    homeHazeState = homeHazeState
                )
            }
        }

        // Material Filter Group Contract (Keep the Home filters as one exclusive choice row)
        // This prevents the non-Haze branch from regressing into unrelated clickable chips.
        composeRule.onAllNodes(hasSelectableGroup(), useUnmergedTree = true).assertCountEquals(1)

        // Material Selected Filter Contract (Expose selected radio state without relying on official FilterChip)
        // The custom Material branch must announce the same selected state as the Haze branch.
        composeRule
            .onNode(hasText(selectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = true))
            .assert(hasStateDescription(selectedStateDescription))
            .assertHeightIsAtLeast(48.dp)

        // Material Unselected Filter Contract (Keep inactive choices reachable and large enough)
        // The visible chip is compact, but the semantics node must still meet Android touch-target guidance.
        composeRule
            .onNode(hasText(unselectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = false))
            .assert(hasStateDescription(unselectedStateDescription))
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun readStatusDialogChipsExposeRadioSelectionSemanticsAndMinimumTarget() {
        val selectedLabel = composeRule.activity.getString(R.string.filter_in_progress)
        val unselectedLabel = composeRule.activity.getString(R.string.filter_finished)
        val selectedStateDescription = composeRule.activity.getString(R.string.accessibility_choice_selected)
        val unselectedStateDescription = composeRule.activity.getString(R.string.accessibility_choice_unselected)

        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                // Read Status Dialog Fixture (Render only the long-press action dialog)
                // A minimal shared dialog payload keeps the test focused on the read-status choice group without mounting Home navigation or persistence state.
                AudiobookActionDialog(
                    book = audiobookActionDialogBook(readStatus = AudiobookSchema.ReadStatus.IN_PROGRESS),
                    glassEffectMode = GlassEffectMode.Material,
                    onDismissRequest = {},
                    onUpdateReadStatus = { _, _ -> },
                    onForceRegenerate = {}
                ) {}
            }
        }

        // Read Status Group Contract (Expose one mutually exclusive status group)
        // The dialog choices represent one read-status field, not three unrelated buttons.
        composeRule.onAllNodes(hasSelectableGroup(), useUnmergedTree = true).assertCountEquals(1)

        // Selected Read Status Contract (Expose the current status beyond visual accent cues)
        // The selected state must be announced through semantics even if color, border, or dot size are unavailable.
        composeRule
            .onNode(hasText(selectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = true))
            .assert(hasStateDescription(selectedStateDescription))
            .assertHeightIsAtLeast(48.dp)

        // Unselected Read Status Contract (Keep inactive status choices reachable and large enough)
        // The 48.dp assertion protects touch and Switch Access targets from shrinking back to the prior 42.dp chip.
        composeRule
            .onNode(hasText(unselectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = false))
            .assert(hasStateDescription(unselectedStateDescription))
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun readStatusDialogWithMissingStatusDoesNotSelectAnyChoice() {
        val notStartedLabel = composeRule.activity.getString(R.string.filter_not_started)
        val inProgressLabel = composeRule.activity.getString(R.string.filter_in_progress)
        val finishedLabel = composeRule.activity.getString(R.string.filter_finished)
        val unselectedStateDescription = composeRule.activity.getString(R.string.accessibility_choice_unselected)

        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                // Missing Read Status Fixture (Render the shared action dialog without a current status)
                // Detail projections may intentionally omit readStatus, so the shared chips must expose no selected radio item instead of defaulting to Not Started.
                AudiobookActionDialog(
                    book = audiobookActionDialogBook(readStatus = null),
                    glassEffectMode = GlassEffectMode.Material,
                    onDismissRequest = {},
                    onUpdateReadStatus = { _, _ -> },
                    onForceRegenerate = {}
                ) {}
            }
        }

        // Missing Status Group Contract (Keep the status choices grouped while leaving every option unselected)
        // This locks the Detail action-dialog requirement that absent readStatus data must not synthesize a selected state.
        composeRule.onAllNodes(hasSelectableGroup(), useUnmergedTree = true).assertCountEquals(1)

        listOf(notStartedLabel, inProgressLabel, finishedLabel).forEach { label ->
            composeRule
                .onNode(hasText(label) and hasRole(Role.RadioButton))
                .assertHasClickAction()
                .assert(hasSelectedState(selected = false))
                .assert(hasStateDescription(unselectedStateDescription))
                .assertHeightIsAtLeast(48.dp)
        }
    }

    private companion object {
        // Title: Fix Test Dialog Helper Parameter (Change parameter type from String? to AudiobookSchema.ReadStatus? to match data class)
        private fun audiobookActionDialogBook(readStatus: AudiobookSchema.ReadStatus?): AudiobookActionDialogBook =
            AudiobookActionDialogBook(
                id = "accessibility-book-id",
                title = "Accessibility Test Book",
                author = "",
                narrator = "",
                coverPath = null,
                thumbnailPath = null,
                lastScannedAt = 0L,
                readStatus = readStatus
            )

        // Role Matcher (Read the public Compose semantics role property directly)
        // Local matcher ownership avoids relying on optional helper APIs that can move across Compose BOM updates.
        private fun hasRole(role: Role): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

        // Selected State Matcher (Read radio-style selected semantics from custom chips)
        // The regression target is the selectable node, not decorative text, color, or dot children.
        private fun hasSelectedState(selected: Boolean): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, selected)

        // State Description Matcher (Read localized selected/unselected announcement text)
        // This guarantees the custom controls provide explicit state text for accessibility services.
        private fun hasStateDescription(description: String): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

        // Selectable Group Matcher (Detect grouped mutually exclusive choices)
        // Home filters and read-status chips should each expose one group marker around their alternatives.
        private fun hasSelectableGroup(): SemanticsMatcher =
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
    }
}

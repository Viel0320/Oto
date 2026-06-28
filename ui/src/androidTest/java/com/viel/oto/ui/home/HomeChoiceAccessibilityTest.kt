package com.viel.oto.ui.home

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
import com.viel.oto.shared.R
import com.viel.oto.application.library.LibraryReadStatus
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.shared.model.HomeFilter
import com.viel.oto.ui.common.AudiobookActionDialog
import com.viel.oto.ui.common.AudiobookActionDialogBook
import com.viel.oto.ui.common.theme.LocalGlassEffectMode
import com.viel.oto.ui.common.theme.LocalIsBlur
import com.viel.oto.ui.common.theme.OtoTheme
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.HazeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks custom Home choice controls for assistive technology.
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
            OtoTheme(dynamicColor = false) {
                val homeHazeState = remember { HazeState() }

                CompositionLocalProvider(
                    LocalGlassEffectMode provides GlassEffectMode.Haze,
                    LocalIsBlur provides true
                ) {
                    HomeContent(
                        selectedFilter = HomeFilter.InProgress,
                        homeHazeState = homeHazeState
                    )
                }
            }
        }

        composeRule.onAllNodes(hasSelectableGroup(), useUnmergedTree = true).assertCountEquals(1)

        composeRule
            .onNode(hasText(selectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = true))
            .assert(hasStateDescription(selectedStateDescription))
            .assertHeightIsAtLeast(48.dp)

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
            OtoTheme(dynamicColor = false) {
                val homeHazeState = remember { HazeState() }

                CompositionLocalProvider(
                    LocalGlassEffectMode provides GlassEffectMode.Material,
                    LocalIsBlur provides false
                ) {
                    HomeContent(
                        selectedFilter = HomeFilter.InProgress,
                        homeHazeState = homeHazeState
                    )
                }
            }
        }

        composeRule.onAllNodes(hasSelectableGroup(), useUnmergedTree = true).assertCountEquals(1)

        composeRule
            .onNode(hasText(selectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = true))
            .assert(hasStateDescription(selectedStateDescription))
            .assertHeightIsAtLeast(48.dp)

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
            OtoTheme(dynamicColor = false) {
                AudiobookActionDialog(
                    book = audiobookActionDialogBook(readStatus = LibraryReadStatus.IN_PROGRESS),
                    glassEffectMode = GlassEffectMode.Material,
                    onDismissRequest = {},
                    onUpdateReadStatus = { _, _ -> },
                    onForceRegenerate = {}
                ) {}
            }
        }

        composeRule.onAllNodes(hasSelectableGroup(), useUnmergedTree = true).assertCountEquals(1)

        composeRule
            .onNode(hasText(selectedLabel) and hasRole(Role.RadioButton))
            .assertHasClickAction()
            .assert(hasSelectedState(selected = true))
            .assert(hasStateDescription(selectedStateDescription))
            .assertHeightIsAtLeast(48.dp)

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
            OtoTheme(dynamicColor = false) {
                AudiobookActionDialog(
                    book = audiobookActionDialogBook(readStatus = null),
                    glassEffectMode = GlassEffectMode.Material,
                    onDismissRequest = {},
                    onUpdateReadStatus = { _, _ -> },
                    onForceRegenerate = {}
                ) {}
            }
        }

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
        private fun audiobookActionDialogBook(readStatus: LibraryReadStatus?): AudiobookActionDialogBook =
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

        private fun hasRole(role: Role): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

        private fun hasSelectedState(selected: Boolean): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, selected)

        private fun hasStateDescription(description: String): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

        private fun hasSelectableGroup(): SemanticsMatcher =
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
    }
}

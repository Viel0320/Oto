package com.viel.oto.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.viel.oto.shared.R
import com.viel.oto.shared.model.SeekStepSeconds
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.settings.components.SettingsSegmentedSeekStepItem
import com.viel.oto.ui.settings.components.SettingsToggleItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.viel.oto.ui.common.icons.OtoIcons

/**
 * Locks Settings row semantics for TalkBack.
 *
 * Exercises reusable Settings controls directly so accessibility regressions are caught at the
 * component boundary without mounting the full Settings route or persistence di.
 */
@RunWith(AndroidJUnit4::class)
class SettingsControlsAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun toggleItemExposesOneTitleBoundSwitchNode() {
        val title = "Settings toggle sentinel"
        val subtitle = "Only one accessibility focus should own this setting"
        val onStateDescription = composeRule.activity.getString(R.string.settings_toggle_state_on)
        val switchMatcher = hasRole(Role.Switch)

        composeRule.setContent {
            OtoTheme(dynamicColor = false) {
                SettingsToggleItem(
                    title = title,
                    subtitle = subtitle,
                    icon = OtoIcons.Rounded.LinearScale,
                    checked = true,
                    onCheckedChange = {}
                )
            }
        }

        composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(switchMatcher, useUnmergedTree = true).assertCountEquals(1)

        composeRule
            .onNode(switchMatcher and hasText(title) and hasText(subtitle))
            .assertHasClickAction()
            .assert(hasToggleableState(ToggleableState.On))
            .assert(hasStateDescription(onStateDescription))
    }

    @Test
    fun segmentedSettingKeepsRowPassiveAndExposesSelectableGroup() {
        val title = "Settings segmented sentinel"
        val subtitle = "The container describes choices but does not choose for the user"
        val selectableGroupMatcher = hasSelectableGroup()

        composeRule.setContent {
            OtoTheme(dynamicColor = false) {
                SettingsSegmentedSeekStepItem(
                    title = title,
                    subtitle = subtitle,
                    icon = OtoIcons.Rounded.LinearScale,
                    selectedStep = SeekStepSeconds.Ten,
                    onStepSelected = {}
                )
            }
        }

        composeRule.onAllNodes(hasClickAction() and hasText(title), useUnmergedTree = true).assertCountEquals(0)

        composeRule.onAllNodes(selectableGroupMatcher, useUnmergedTree = true).assertCountEquals(1)
    }

    private companion object {
        private fun hasRole(role: Role): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

        private fun hasToggleableState(state: ToggleableState): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

        private fun hasStateDescription(description: String): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

        private fun hasSelectableGroup(): SemanticsMatcher =
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
    }
}

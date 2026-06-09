package com.viel.aplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinearScale
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
import com.viel.aplayer.R
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.settings.components.SettingsSegmentedSeekStepItem
import com.viel.aplayer.ui.settings.components.SettingsToggleItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings Controls Accessibility Test (Locks Settings row semantics for TalkBack)
 *
 * Exercises reusable Settings controls directly so accessibility regressions are caught at the
 * component boundary without mounting the full Settings route or persistence graph.
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
            APlayerTheme(dynamicColor = false) {
                SettingsToggleItem(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Rounded.LinearScale,
                    checked = true,
                    onCheckedChange = {}
                )
            }
        }

        // Toggle Focus Contract (Allow exactly one clickable semantics node for a boolean setting)
        // The old row-click-plus-Switch pattern exposed two click targets, while the fixed row owns the only toggle action.
        composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(switchMatcher, useUnmergedTree = true).assertCountEquals(1)

        // Toggle Announcement Contract (Bind title, subtitle, role, checked state, and state text to one node)
        // TalkBack should announce the row as a single switch instead of separating the label text from the visual Switch.
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
            APlayerTheme(dynamicColor = false) {
                SettingsSegmentedSeekStepItem(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Rounded.LinearScale,
                    selectedStep = SeekStepSeconds.Ten,
                    onStepSelected = {}
                )
            }
        }

        // Segmented Row Container Contract (Keep the descriptive Settings row passive)
        // A segmented control has multiple valid choices, so a click target containing the title would be ambiguous.
        composeRule.onAllNodes(hasClickAction() and hasText(title), useUnmergedTree = true).assertCountEquals(0)

        // Segmented Choice Group Contract (Mark the concrete segment row as a mutually exclusive group)
        // Accessibility services can then announce the individual segment buttons as related alternatives.
        composeRule.onAllNodes(selectableGroupMatcher, useUnmergedTree = true).assertCountEquals(1)
    }

    private companion object {
        // Role Matcher (Reads the public Compose semantics role property directly)
        // Keeping matchers local avoids depending on optional test helper names that can move across Compose BOM updates.
        private fun hasRole(role: Role): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

        // Toggle State Matcher (Reads the merged switch checked state from the row node)
        // The regression needs the parent row, not the decorative Switch child, to own this state.
        private fun hasToggleableState(state: ToggleableState): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

        // State Description Matcher (Reads localized TalkBack state text from the row node)
        // This ensures the custom On/Off state description stays bound to the setting label.
        private fun hasStateDescription(description: String): SemanticsMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

        // Selectable Group Matcher (Detects the radio-style grouping marker on segmented controls)
        // The group marker belongs on the segment row rather than the outer Settings layout row.
        private fun hasSelectableGroup(): SemanticsMatcher =
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
    }
}

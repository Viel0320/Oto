package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.HomeSortRule
import com.viel.aplayer.data.store.HomeViewStyle
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import dev.chrisbanes.haze.HazeState

/**
 * Home View Preference Dialog (Home catalog layout and grouping controls)
 *
 * Presents Home-specific display preferences without mixing them into general Settings screens.
 * The dialog writes selections through callbacks immediately so the catalog can switch renderers and ordering while the dialog remains open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeViewPreferenceDialog(
    selectedViewStyle: HomeViewStyle,
    selectedSortRule: HomeSortRule,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    onViewStyleSelected: (HomeViewStyle) -> Unit,
    onSortRuleSelected: (HomeSortRule) -> Unit,
    onDismissRequest: () -> Unit
) {
    // Home View Style Options (Bind stable enum values to compact user-facing labels)
    // Labels stay local to this Home-only dialog while persisted enum names remain language-independent.
    val viewStyleOptions = listOf(
        HomeViewStyle.List to "列表",
        HomeViewStyle.Grid to "网格"
    )
    // Home Sort Rule Options (Bind sorting pivots to concise labels)
    // The ViewModel applies the same selected enum to grouping, pinyin-descending ordering, and section header generation.
    val sortRuleOptions = listOf(
        HomeSortRule.Author to "作者",
        HomeSortRule.Narrator to "朗读者",
        HomeSortRule.Series to "系列名"
    )

    APlayerDialogTemplate(
        onDismissRequest = onDismissRequest,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        // Home View Dialog Center Alignment (Keep all header slots visually centered)
        // The view switcher is a compact preference chooser, so centered chrome better matches its modal selection purpose.
        headerAlignment = Alignment.CenterHorizontally,
        title = {
            Text(
                text = "首页视图",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        body = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                // Home View Dialog Body Alignment (Center section labels above their segmented controls)
                // Keeps the dialog's visual axis consistent from title through controls.
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "视图样式",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    viewStyleOptions.forEachIndexed { index, (style, label) ->
                        SegmentedButton(
                            selected = selectedViewStyle == style,
                            onClick = { onViewStyleSelected(style) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = viewStyleOptions.size
                            )
                        ) {
                            Text(text = label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "排序规则",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    sortRuleOptions.forEachIndexed { index, (rule, label) ->
                        SegmentedButton(
                            selected = selectedSortRule == rule,
                            onClick = { onSortRuleSelected(rule) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = sortRuleOptions.size
                            )
                        ) {
                            Text(text = label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        actions = {
            TextButton(onClick = onDismissRequest) {
                Text("关闭")
            }
        }
    )
}

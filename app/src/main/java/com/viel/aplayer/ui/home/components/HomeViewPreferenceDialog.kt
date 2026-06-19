package com.viel.aplayer.ui.home.components

// Import HomeBookStatusFilter (Brings in the relocated type-safe availability filter from the data store layer)
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.shared.settings.HomeBookStatusFilter
import com.viel.aplayer.shared.settings.HomeSortDirection
import com.viel.aplayer.shared.settings.HomeSortRule
import com.viel.aplayer.shared.settings.HomeViewStyle
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
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
    selectedSortDirection: HomeSortDirection,
    selectedBookStatusFilter: HomeBookStatusFilter,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    onViewStyleSelected: (HomeViewStyle) -> Unit,
    onSortRuleSelected: (HomeSortRule) -> Unit,
    onSortDirectionSelected: (HomeSortDirection) -> Unit,
    onBookStatusFilterSelected: (HomeBookStatusFilter) -> Unit,
    onDismissRequest: () -> Unit
) {
    // Home View Style Options (Bind stable enum values to compact user-facing labels)
    // Labels stay local to this Home-only dialog while persisted enum names remain language-independent.
    val viewStyleOptions = listOf(
        HomeViewStyle.List to stringResource(R.string.home_view_style_list),
        HomeViewStyle.Grid to stringResource(R.string.home_view_style_grid)
    )
    // Home Sort Rule Options (Bind sorting pivots to concise labels)
    // The ViewModel applies the same selected enum to grouping, script-clustered ordering, and section header generation.
    val sortRuleOptions = listOf(
        HomeSortRule.Author to stringResource(R.string.home_sort_rule_author),
        HomeSortRule.Narrator to stringResource(R.string.home_sort_rule_narrator),
        HomeSortRule.Series to stringResource(R.string.home_sort_rule_series)
    )
    // Home Sort Direction Options (Bind in-cluster direction choices to compact labels)
    // Direction only changes ordering inside C/J/K/E/Other clusters, preserving the global mixed-script cluster sequence.
    val sortDirectionOptions = listOf(
        HomeSortDirection.Ascending to stringResource(R.string.home_sort_direction_ascending),
        HomeSortDirection.Descending to stringResource(R.string.home_sort_direction_descending)
    )
    // Home Book Status Segment Options (Bind the four active BookStatus filter values to localized labels)
    // Conflict is intentionally omitted because the current catalog workflow no longer exposes that state, and deleted books are hidden at the DAO boundary.
    val bookStatusFilterOptions = listOf(
        HomeBookStatusFilter.All to stringResource(R.string.home_book_status_all),
        HomeBookStatusFilter.Ready to stringResource(R.string.home_book_status_ready),
        HomeBookStatusFilter.Partial to stringResource(R.string.home_book_status_partial),
        HomeBookStatusFilter.Unavailable to stringResource(R.string.home_book_status_unavailable)
    )
    val useColumnarDialog = LocalAppWindowSizeClass.current.columnsCount > 1

    APlayerDialogTemplate(
        onDismissRequest = onDismissRequest,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        dialogMaxWidth = if (useColumnarDialog) 680.dp else 460.dp,
        // Home View Dialog Center Alignment (Keep all header slots visually centered)
        // The view switcher is a compact preference chooser, so centered chrome better matches its modal selection purpose.
        headerAlignment = Alignment.CenterHorizontally,
        title = {
            Text(
                text = stringResource(R.string.home_view_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        body = {
            HomeViewPreferenceDialogBody(
                useColumnarDialog = useColumnarDialog,
                viewStyleOptions = viewStyleOptions,
                selectedViewStyle = selectedViewStyle,
                onViewStyleSelected = onViewStyleSelected,
                sortRuleOptions = sortRuleOptions,
                selectedSortRule = selectedSortRule,
                onSortRuleSelected = onSortRuleSelected,
                sortDirectionOptions = sortDirectionOptions,
                selectedSortDirection = selectedSortDirection,
                onSortDirectionSelected = onSortDirectionSelected,
                bookStatusFilterOptions = bookStatusFilterOptions,
                selectedBookStatusFilter = selectedBookStatusFilter,
                onBookStatusFilterSelected = onBookStatusFilterSelected
            )
        },
        actions = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.home_view_dialog_close))
            }
        }
    )
}

/**
 * Home View Dialog Body (Places independent catalog preference groups in one or two columns)
 *
 * Keeps phone layouts vertical while giving landscape and tablet dialogs enough horizontal structure for faster scanning.
 */
@Composable
private fun HomeViewPreferenceDialogBody(
    useColumnarDialog: Boolean,
    viewStyleOptions: List<Pair<HomeViewStyle, String>>,
    selectedViewStyle: HomeViewStyle,
    onViewStyleSelected: (HomeViewStyle) -> Unit,
    sortRuleOptions: List<Pair<HomeSortRule, String>>,
    selectedSortRule: HomeSortRule,
    onSortRuleSelected: (HomeSortRule) -> Unit,
    sortDirectionOptions: List<Pair<HomeSortDirection, String>>,
    selectedSortDirection: HomeSortDirection,
    onSortDirectionSelected: (HomeSortDirection) -> Unit,
    bookStatusFilterOptions: List<Pair<HomeBookStatusFilter, String>>,
    selectedBookStatusFilter: HomeBookStatusFilter,
    onBookStatusFilterSelected: (HomeBookStatusFilter) -> Unit
) {
    if (useColumnarDialog) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                HomeViewPreferenceSegmentSection(
                    title = stringResource(R.string.home_view_style_title),
                    options = viewStyleOptions,
                    selectedValue = selectedViewStyle,
                    onSelected = onViewStyleSelected,
                    modifier = Modifier.weight(1f)
                )
                HomeViewPreferenceSegmentSection(
                    title = stringResource(R.string.home_sort_rule_title),
                    options = sortRuleOptions,
                    selectedValue = selectedSortRule,
                    onSelected = onSortRuleSelected,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                HomeViewPreferenceSegmentSection(
                    title = stringResource(R.string.home_sort_direction_title),
                    options = sortDirectionOptions,
                    selectedValue = selectedSortDirection,
                    onSelected = onSortDirectionSelected,
                    modifier = Modifier.weight(1f)
                )
                HomeViewPreferenceSegmentSection(
                    title = stringResource(R.string.home_book_status_filter_title),
                    options = bookStatusFilterOptions,
                    selectedValue = selectedBookStatusFilter,
                    onSelected = onBookStatusFilterSelected,
                    modifier = Modifier.weight(1f),
                    compactLabels = true
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            // Home View Dialog Body Alignment (Center section labels above their segmented controls)
            // Keeps the dialog's visual axis consistent from title through controls.
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            HomeViewPreferenceSegmentSection(
                title = stringResource(R.string.home_view_style_title),
                options = viewStyleOptions,
                selectedValue = selectedViewStyle,
                onSelected = onViewStyleSelected
            )
            HomeViewPreferenceSegmentSection(
                title = stringResource(R.string.home_sort_rule_title),
                options = sortRuleOptions,
                selectedValue = selectedSortRule,
                onSelected = onSortRuleSelected
            )
            HomeViewPreferenceSegmentSection(
                title = stringResource(R.string.home_sort_direction_title),
                options = sortDirectionOptions,
                selectedValue = selectedSortDirection,
                onSelected = onSortDirectionSelected
            )
            HomeViewPreferenceSegmentSection(
                title = stringResource(R.string.home_book_status_filter_title),
                options = bookStatusFilterOptions,
                selectedValue = selectedBookStatusFilter,
                onSelected = onBookStatusFilterSelected,
                compactLabels = true
            )
        }
    }
}

/**
 * Home View Preference Segment Section (Renders one titled segmented selector)
 *
 * Shares title and segmented-button layout across the dialog so adaptive row and column branches cannot drift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> HomeViewPreferenceSegmentSection(
    title: String,
    options: List<Pair<T, String>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    compactLabels: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selectedValue == value,
                    onClick = { onSelected(value) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    )
                ) {
                    Text(
                        text = label,
                        style = if (compactLabels) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

package com.viel.oto.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.theme.LocalHazeState
import com.viel.oto.ui.common.theme.LocalIsBlur
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.viel.oto.ui.common.icons.OtoIcons

// ==========================================
// Public API
// ==========================================

/**
 * One Popup option.
 *
 * [content] is the primary slot; callers fully own the row's leading/main content. [count] is an
 * optional trailing slot (pass null to omit), so the same component renders both "label + count"
 * filter rows and bare action rows. [key] gives each row a stable identity used for stagger keying
 * and for mapping the currently-selected row to its in-list position during the morph.
 */
@Immutable
class OtoPopupItem(
    val key: Any,
    val enabled: Boolean = true,
    val content: @Composable RowScope.() -> Unit,
    val count: (@Composable () -> Unit)? = null,
)

/**
 * Convenience builder for the common "label + optional count" row, such as "All 75".
 *
 * Keeps callers from writing a slot lambda for the dominant case while still producing a full
 * [OtoPopupItem] so the count slot stays optional and the row stays customizable elsewhere.
 */
fun TextPopupItem(
    key: Any,
    label: String,
    count: Int? = null,
    enabled: Boolean = true,
): OtoPopupItem = OtoPopupItem(
    key = key,
    enabled = enabled,
    content = {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.Unspecified,
            maxLines = 1,
        )
    },
    count = count?.let {
        {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    },
)

/**
 * Requested expansion origin. [Auto] picks the corner with the most available room; explicit
 * corners force the origin and diagonal direction.
 */
enum class OtoPopupAlignment {
    Auto,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight
}

/**
 * Expanded panel width strategy.
 *
 * [MatchAnchor] keeps the panel exactly as wide as the button so the morph grows straight out of it.
 * [Wrap] sizes to the widest option. [Fixed] pins an explicit width.
 */
@Immutable
sealed interface OtoPopupWidth {
    data object MatchAnchor : OtoPopupWidth
    data object Wrap : OtoPopupWidth
    data class Fixed(val width: Dp) : OtoPopupWidth
}

/**
 * Color roles for both morph endpoints. Interpolated continuously by the expand fraction, so the
 * collapsed outlined button and the filled floating panel are two ends of one surface, not two
 * surfaces swapped at a threshold.
 */
@Immutable
data class OtoPopupColors(
    val collapsedContainer: Color,
    val collapsedBorder: Color,
    val expandedContainer: Color,
    val expandedBorder: Color,
    val content: Color,
)

/** Corner radii for both morph endpoints; the live corner is lerp'd between them by the fraction. */
@Immutable
data class OtoPopupShapes(
    val collapsedCorner: Dp = 8.dp,
    val expandedCorner: Dp = 8.dp,
)

/**
 * Theme-aligned defaults. Collapsed reads as a Material outlined button (surface + 1dp outline);
 * expanded reads as a filled menu surface (surfaceContainerHigh, no border, soft elevation).
 */
object OtoPopupDefaults {
    val CollapsedElevation = 0.dp
    val ExpandedElevation = 6.dp

    @Composable
    fun colors(): OtoPopupColors {
        val scheme = MaterialTheme.colorScheme
        return OtoPopupColors(
            collapsedContainer = scheme.surface,
            collapsedBorder = scheme.outline,
            expandedContainer = scheme.surfaceContainerHigh,
            expandedBorder = Color.Transparent,
            content = scheme.onSurface,
        )
    }

    @Composable
    fun shapes(): OtoPopupShapes = OtoPopupShapes()
}

// ==========================================
// Entry Point
// ==========================================

/**
 * A Popup that morphs between a collapsed outlined button and an expanded floating panel.
 *
 * One component serves two roles: a single-select FILTER (pass [selectedIndex]; that row shows in the
 * button and flies to its list position on expand) and an action MENU (pass selectedIndex = null and
 * a [collapsedContent] slot; no row flies and every option enters staggered). [items] are slot-based
 * rows with an optional trailing count.
 *
 * The expand/collapse is driven by one fraction over a floating [Popup], so the surface's size,
 * corner, background, border, and elevation interpolate continuously. With [alignment] = Auto, the
 * panel opens up/down from vertical room and also shifts its horizontal growth side when centering
 * would cross a screen edge. State is hoisted via [expanded] / [onExpandedChange]; [onSelect]
 * reports a chosen row and never mutates selection itself.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun OtoPopupSelector(
    items: List<OtoPopupItem>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    collapsedContent: (@Composable RowScope.() -> Unit)? = null,
    alignment: OtoPopupAlignment = OtoPopupAlignment.Auto,
    panelWidth: OtoPopupWidth = OtoPopupWidth.MatchAnchor,
    panelMaxHeight: Dp = Dp.Unspecified,
    hazeState: HazeState? = null,
    colors: OtoPopupColors = OtoPopupDefaults.colors(),
    shapes: OtoPopupShapes = OtoPopupDefaults.shapes(),
    collapsedHeight: Dp = PopupUnifiedHeight,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val morph = remember { PopupMorphState(if (expanded) 1f else 0f, scope) }
    LaunchedEffect(expanded) { morph.animateTo(if (expanded) 1f else 0f) }

    val safeSelectedIndex = selectedIndex?.takeIf { it in items.indices }

    val resolvedHazeState = hazeState ?: LocalHazeState.current

    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val fractionProvider = morph.provider()

    Box(
        modifier = modifier,
        propagateMinConstraints = true
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = 1f - fractionProvider().coerceIn(0f, 1f) },
            propagateMinConstraints = true
        ) {
            CollapsedAnchor(
                items = items,
                selectedIndex = safeSelectedIndex,
                collapsedContent = collapsedContent,
                colors = colors,
                shapes = shapes,
                expanded = expanded,
                hazeState = resolvedHazeState,
                onClick = { onExpandedChange(!expanded) },
                onVisualBoundsMeasured = { anchorBounds = it },
                collapsedHeight = collapsedHeight,
            )
        }

        val bounds = anchorBounds
        if (bounds != null && morph.isVisible) {
            PopupPanel(
                anchor = bounds,
                items = items,
                selectedIndex = safeSelectedIndex,
                morph = morph,
                colors = colors,
                shapes = shapes,
                alignment = alignment,
                panelWidth = panelWidth,
                panelMaxHeight = panelMaxHeight,
                hazeState = resolvedHazeState,
                density = density,
                onSelect = onSelect,
                onDismiss = { onExpandedChange(false) },
                collapsedHeight = collapsedHeight,
            )
        }
    }
}

// ==========================================
// Composition Tree
// ==========================================

/**
 * The resting collapsed button: an outlined surface with the current selection (filter) or a custom
 * [collapsedContent] (menu), plus a chevron. This is the in-layout node whose bounds are measured;
 * during the morph it is hidden and the popup's morph surface stands in for it.
 * The touch target is intentionally taller than the visual pill, so the pill is centered inside the
 * tappable area instead of clinging to the top edge while collapsed.
 *
 * In [GlassEffectMode.Haze] the visual pill carries the same haze effect as the expanded morph
 * surface, so the resting state reads as glass too instead of a bare outlined chip.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun CollapsedAnchor(
    items: List<OtoPopupItem>,
    selectedIndex: Int?,
    collapsedContent: (@Composable RowScope.() -> Unit)?,
    colors: OtoPopupColors,
    shapes: OtoPopupShapes,
    expanded: Boolean,
    hazeState: HazeState?,
    onClick: () -> Unit,
    onVisualBoundsMeasured: (Rect) -> Unit,
    collapsedHeight: Dp,
) {
    val shape = RoundedCornerShape(shapes.collapsedCorner)
    val expandedStateDescription = if (expanded) "expanded" else "collapsed"

    val containerColor = Color.Transparent
    val borderColor = colors.collapsedBorder
    val contentColor = colors.content

    val hazeModifier = if (LocalIsBlur.current && hazeState != null) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin())
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = PopupCollapsedTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.DropdownList,
                onClick = onClick,
            )
            .semantics { stateDescription = expandedStateDescription },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .onGloballyPositioned { onVisualBoundsMeasured(it.boundsInWindow()) }
                // Animate the chip's footprint when the selected row changes width, so the
                // collapsed pill (and the surrounding layout) grows/shrinks smoothly instead of
                // snapping. Height is fixed below, so only the width is animated.
                .animateContentSize()
                .height(collapsedHeight)
                .clip(shape)
                .then(hazeModifier)
                .background(containerColor, shape)
                .border(PopupBorderWidth, borderColor, shape)
                .padding(start = PopupSpacingBase, end = PopupCollapsedHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PopupSpacingBase),
        ) {
            Icon(
                imageVector = OtoIcons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(PopupChevronSize),
                tint = contentColor,
            )
            if (collapsedContent != null) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    collapsedContent()
                }
            } else {
                val item = selectedIndex?.let(items::getOrNull)
                if (item != null) {
                    ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                        PopupRowBody(item = item, contentColor = contentColor)
                    }
                }
            }
        }
    }
}

/**
 * Hosts the morph surface in a full-screen [PopupPanel] with clipping disabled, so the surface can paint
 * above or below the anchor. Estimates the panel height to choose a direction on the first frame (no
 * jump), then refines from the real measured height. All morphed geometry is computed per-frame from
 * the shared fraction and read only in draw/layout lambdas.
 */
@Composable
private fun PopupPanel(
    anchor: Rect,
    items: List<OtoPopupItem>,
    selectedIndex: Int?,
    morph: PopupMorphState,
    colors: OtoPopupColors,
    shapes: OtoPopupShapes,
    alignment: OtoPopupAlignment,
    panelWidth: OtoPopupWidth,
    panelMaxHeight: Dp,
    hazeState: HazeState?,
    density: Density,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    collapsedHeight: Dp,
) {
    Popup(
        popupPositionProvider = TopLeftPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val winWPx = with(density) { maxWidth.toPx() }
            val winHPx = with(density) { maxHeight.toPx() }
            val edgeMarginPx = with(density) { PopupSpacingBase.toPx() }
            val maxWrapWidth = maxWidth - PopupSpacingBase * 2

            var measuredPanelHeightPx by remember { mutableStateOf<Float?>(null) }
            var measuredPanelWidthPx by remember { mutableStateOf<Float?>(null) }

            val fixedWidthDp: Dp? = when (panelWidth) {
                OtoPopupWidth.MatchAnchor -> with(density) { anchor.width.toDp() }
                is OtoPopupWidth.Fixed -> panelWidth.width
                OtoPopupWidth.Wrap -> null
            }

            val hasMeasured = measuredPanelWidthPx != null && measuredPanelHeightPx != null
            if (!hasMeasured) {
                PanelMeasurer(
                    items = items,
                    contentColor = colors.content,
                    fixedWidth = fixedWidthDp,
                    maxWrapWidth = maxWrapWidth,
                    onMeasured = { w, h ->
                        measuredPanelWidthPx = w
                        measuredPanelHeightPx = h
                    },
                )
            }

            val estimatedHeightPx = with(density) { calculatePanelHeight(items.size) }
            val maxHeightPx = if (panelMaxHeight != Dp.Unspecified) {
                with(density) { panelMaxHeight.toPx() }
            } else {
                winHPx * PopupMaxHeightFraction
            }
            val panelHeightPx = (measuredPanelHeightPx ?: estimatedHeightPx).coerceAtMost(maxHeightPx)

            val resolvedWidthPx = when (panelWidth) {
                OtoPopupWidth.MatchAnchor -> anchor.width
                is OtoPopupWidth.Fixed -> with(density) { panelWidth.width.toPx() }
                OtoPopupWidth.Wrap -> {
                    val minWidthPx = anchor.width
                    (measuredPanelWidthPx ?: anchor.width)
                        .coerceIn(minWidthPx, (winWPx - edgeMarginPx * 2f).coerceAtLeast(minWidthPx))
                }
            }

            val direction = remember(
                anchor,
                resolvedWidthPx,
                estimatedHeightPx,
                maxHeightPx,
                winHPx,
                winWPx,
                edgeMarginPx,
                alignment,
            ) {
                resolveDirection(
                    anchor = anchor,
                    panelHeightPx = estimatedHeightPx.coerceAtMost(maxHeightPx),
                    panelWidthPx = resolvedWidthPx,
                    windowHeightPx = winHPx,
                    windowWidthPx = winWPx,
                    edgeMarginPx = edgeMarginPx,
                    requested = alignment,
                )
            }

            val panelRect = resolvePanelRect(
                anchor = anchor,
                direction = direction,
                panelWidthPx = resolvedWidthPx,
                panelHeightPx = panelHeightPx,
                windowWidthPx = winWPx,
                edgeMarginPx = edgeMarginPx,
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
            )

            PopupMorphSurface(
                anchor = anchor,
                panelRect = panelRect,
                direction = direction,
                items = items,
                selectedIndex = selectedIndex,
                morph = morph,
                colors = colors,
                shapes = shapes,
                hazeState = hazeState,
                density = density,
                onSelect = onSelect,
                onDismiss = onDismiss,
                collapsedHeight = collapsedHeight,
            )
        }
    }
}

/**
 * Off-surface measurement pass.
 *
 * Lays out the rows at their natural size, unconstrained by the animating morph surface, and
 * reports the panel's intrinsic width/height. Rendered at alpha 0 and non-interactive; it exists
 * only so the visible surface can size to real content without a measure -> size -> measure loop.
 * Its row shell mirrors the visible list exactly so the reported size matches the real panel.
 */
@Composable
private fun PanelMeasurer(
    items: List<OtoPopupItem>,
    contentColor: Color,
    fixedWidth: Dp?,
    maxWrapWidth: Dp,
    onMeasured: (widthPx: Float, heightPx: Float) -> Unit,
) {
    val widthModifier = if (fixedWidth != null) {
        Modifier.width(fixedWidth)
    } else {
        Modifier
            .wrapContentWidth()
            .widthIn(max = maxWrapWidth)
    }
    Column(
        modifier = widthModifier
            .alpha(0f)
            .onGloballyPositioned { onMeasured(it.size.width.toFloat(), it.size.height.toFloat()) }
            .padding(vertical = PopupPanelVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(PopupRowVerticalSpacing),
    ) {
        items.forEach { item ->
            Box(
                modifier = Modifier
                    .wrapContentWidth()
                    .heightIn(min = PopupUnifiedHeight)
                    .padding(horizontal = PopupRowHorizontalPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                PopupRowBody(item = item, contentColor = contentColor)
            }
        }
    }
}

/**
 * The single morphing surface plus its contents.
 *
 * The outer Box is positioned and sized by [Modifier.layout] from `lerpRect(anchor, panelRect, f)`,
 * and its background, border, corner, and elevation are lerp'd in the same draw pass, so the
 * collapsed button and the expanded panel are literally one surface at two fractions. Inside, the
 * option rows are laid out at full panel size from the start (stable slots) while their reveal is
 * staggered; in filter mode the selected row is drawn as a separate child that travels from the
 * button's content box to its in-list slot.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun PopupMorphSurface(
    anchor: Rect,
    panelRect: Rect,
    direction: OpenDirection,
    items: List<OtoPopupItem>,
    selectedIndex: Int?,
    morph: PopupMorphState,
    colors: OtoPopupColors,
    shapes: OtoPopupShapes,
    hazeState: HazeState?,
    density: Density,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    collapsedHeight: Dp,
) {
    val fractionProvider = morph.provider()
    val collapsedCornerPx = with(density) { shapes.collapsedCorner.toPx() }
    val expandedCornerPx = with(density) { shapes.expandedCorner.toPx() }
    val collapsedBorderPx = with(density) { PopupBorderWidth.toPx() }
    val collapsedElevationPx = with(density) { OtoPopupDefaults.CollapsedElevation.toPx() }
    val expandedElevationPx = with(density) { OtoPopupDefaults.ExpandedElevation.toPx() }
    val panelPaddingPx = with(density) { PopupPanelVerticalPadding.toPx() }

    val isBlur = LocalIsBlur.current
    val hazeModifier = if (isBlur && hazeState != null) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin())
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val f = fractionProvider().coerceIn(0f, 1f)
                val rect = lerpRect(anchor, panelRect, f)
                val w = rect.width.roundToInt().coerceAtLeast(0)
                val h = rect.height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) {
                    placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                }
            }
            .graphicsLayer {
                val f = fractionProvider().coerceIn(0f, 1f)
                shadowElevation = lerp(collapsedElevationPx, expandedElevationPx, f)
                shape = RoundedCornerShape(lerp(collapsedCornerPx, expandedCornerPx, f))
                clip = true
            }
            .then(hazeModifier)
            .drawBehind {
                val f = fractionProvider().coerceIn(0f, 1f)
                val cornerPx = lerp(collapsedCornerPx, expandedCornerPx, f)
                val corner = CornerRadius(cornerPx, cornerPx)
                if (!isBlur || hazeState == null) {
                    val bg = lerp(colors.collapsedContainer, colors.expandedContainer, f)
                    drawRoundRect(color = bg, cornerRadius = corner)
                }
                val borderColor = lerp(colors.collapsedBorder, colors.expandedBorder, f)
                val borderPx = lerp(collapsedBorderPx, 0f, f)
                if (borderPx > 0.25f && borderColor.alpha > 0.01f) {
                    val inset = borderPx / 2f
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - borderPx, size.height - borderPx),
                        cornerRadius = CornerRadius(cornerPx - inset, cornerPx - inset),
                        style = Stroke(width = borderPx),
                    )
                }
            },
    ) {
        PopupOptionList(
            items = items,
            selectedIndex = selectedIndex,
            direction = direction,
            fractionProvider = fractionProvider,
            contentColor = colors.content,
            verticalPadding = PopupPanelVerticalPadding,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )

        if (selectedIndex != null) {
            val rowHeightPx = with(density) { PopupUnifiedHeight.toPx() }
            FlyingSelectedRow(
                item = items[selectedIndex],
                selectedIndex = selectedIndex,
                anchor = anchor,
                panelRect = panelRect,
                fractionProvider = fractionProvider,
                contentColor = colors.content,
                collapsedVisualHeightPx = with(density) { collapsedHeight.toPx() },
                expandedRowHeightPx = rowHeightPx,
                panelPaddingPx = panelPaddingPx,
            )
        }
    }
}

/**
 * The vertically-arranged option rows. Each non-selected row reveals on a sub-curve of the shared
 * fraction, ordered from the side nearest the button so the list "unfurls" out of it. The list is
 * scrollable only once fully expanded so a half-open panel never scrolls.
 */
@Composable
private fun PopupOptionList(
    items: List<OtoPopupItem>,
    selectedIndex: Int?,
    direction: OpenDirection,
    fractionProvider: () -> Float,
    contentColor: Color,
    verticalPadding: Dp,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val rowEnterTranslationPx = with(LocalDensity.current) { PopupRowEnterTranslation.toPx() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(PopupRowVerticalSpacing),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selectedIndex
            val orderFromOrigin = if (direction.opensDown) index else items.lastIndex - index
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PopupUnifiedHeight)
                    .graphicsLayer {
                        if (isSelected) {
                            alpha = if (fractionProvider() >= 0.999f) 1f else 0f
                        } else {
                            val startAt = (PopupStaggerBase + orderFromOrigin * PopupStaggerStep)
                                .coerceAtMost(PopupStaggerMax)
                            val raw = ((fractionProvider() - startAt) / (1f - startAt)).coerceIn(0f, 1f)
                            val eased = FastOutSlowInEasing.transform(raw)
                            alpha = eased
                            val dir = if (direction.opensDown) 1f else -1f
                            translationY = (1f - eased) * rowEnterTranslationPx * dir
                        }
                    }
                    .selectable(
                        selected = isSelected,
                        enabled = item.enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = if (selectedIndex != null) Role.RadioButton else Role.Button,
                        onClick = {
                            onSelect(index)
                            onDismiss()
                        },
                    )
                    .padding(horizontal = PopupRowHorizontalPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!isSelected) {
                    PopupRowContent(item = item, contentColor = contentColor)
                }
            }
        }
    }
}

/**
 * The selected row drawn as an independent child that lerps from the collapsed button's content box
 * to its computed in-list slot, so the current selection visibly moves into place instead of
 * cross-fading. Decorative during flight; semantics are cleared so the static list owns them.
 */
@Composable
private fun FlyingSelectedRow(
    item: OtoPopupItem,
    selectedIndex: Int,
    anchor: Rect,
    panelRect: Rect,
    fractionProvider: () -> Float,
    contentColor: Color,
    collapsedVisualHeightPx: Float,
    expandedRowHeightPx: Float,
    panelPaddingPx: Float,
) {
    val density = LocalDensity.current
    val horizontalPaddingPx = with(density) { PopupRowHorizontalPadding.toPx() }
    val collapsedPaddingPx = with(density) { PopupCollapsedHorizontalPadding.toPx() }
    val collapsedLeadingPaddingPx = with(density) { PopupSpacingBase.toPx() }
    val rowSpacingPx = with(density) { PopupRowVerticalSpacing.toPx() }
    val chevronBlockPx = with(density) { chevronBlockWidth() }

    Box(
        modifier = Modifier
            .clearAndSetSemantics { }
            .layout { measurable, constraints ->
                val f = fractionProvider().coerceIn(0f, 1f)
                val srcLeft = anchor.left + collapsedLeadingPaddingPx + chevronBlockPx
                val srcTop = anchor.top + (anchor.height - collapsedVisualHeightPx) / 2f
                val src = Rect(srcLeft, srcTop, anchor.right - collapsedPaddingPx, srcTop + collapsedVisualHeightPx)
                val tgtLeft = panelRect.left + horizontalPaddingPx
                val tgtTop = panelRect.top + panelPaddingPx + selectedIndex * (expandedRowHeightPx + rowSpacingPx)
                val tgt = Rect(tgtLeft, tgtTop, panelRect.right - horizontalPaddingPx, tgtTop + expandedRowHeightPx)
                val rect = lerpRect(src, tgt, f)
                val surfaceLeft = lerp(anchor.left, panelRect.left, f)
                val surfaceTop = lerp(anchor.top, panelRect.top, f)
                val w = rect.width.roundToInt().coerceAtLeast(0)
                val h = rect.height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        (rect.left - surfaceLeft).roundToInt(),
                        (rect.top - surfaceTop).roundToInt(),
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        PopupRowContent(item = item, contentColor = contentColor)
    }
}

/**
 * Shared row body: the caller's content slot plus the optional trailing count slot, wrapped so the
 * content color is provided once. [modifier] sizes the outer row; [weightContent] lets the content
 * slot consume the free space (used by the in-panel rows) versus hugging its content (used by the
 * collapsed button and the off-surface measurer).
 */
@Composable
private fun PopupRowBody(
    item: OtoPopupItem,
    contentColor: Color,
    modifier: Modifier = Modifier,
    weightContent: Boolean = false,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PopupCountSpacing),
        ) {
            Row(
                modifier = if (weightContent) Modifier.weight(1f, fill = false) else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                content = item.content,
            )
            item.count?.invoke()
        }
    }
}

/** The in-panel row body: full width with the content slot taking the free space. */
@Composable
private fun PopupRowContent(
    item: OtoPopupItem,
    contentColor: Color,
) {
    PopupRowBody(
        item = item,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
        weightContent = true,
    )
}

// ==========================================
// Morph State & Geometry
// ==========================================

/**
 * Single source of truth for the collapse <-> expand morph, mirroring the player's expand state.
 *
 * [fraction] is the continuous expansion amount: 0f = collapsed button, 1f = expanded panel. Every
 * morphed property (rect, corner, colors, elevation, the flying row, per-row stagger) is a pure
 * function of this one value, so they all move on one clock.
 *
 * PERFORMANCE CONTRACT: read [fraction].value ONLY inside draw/layout-phase lambdas
 * (graphicsLayer {}, Modifier.layout {}, drawBehind {}). [provider] hands it down as a deferred read
 * so the panel subtree is not recomposed every animation frame.
 */
@Stable
private class PopupMorphState(
    initial: Float,
    private val scope: CoroutineScope,
) {
    val fraction: Animatable<Float, *> = Animatable(initial)

    fun provider(): () -> Float = { fraction.value }

    private val _isVisible = mutableStateOf(initial > 0.0001f)
    val isVisible: Boolean
        get() = _isVisible.value

    fun animateTo(target: Float) {
        scope.launch {
            if (target > 0.0001f) {
                _isVisible.value = true
            }
            fraction.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = SPRING_DAMPING,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            if (target <= 0.0001f) {
                _isVisible.value = false
            }
        }
    }

    private companion object {
        const val SPRING_DAMPING = 0.9f
    }
}

/** Always positions the popup at the window's top-left so window coords map directly to popup coords. */
private object TopLeftPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(0, 0)
}

/**
 * Resolved expansion origin after the anchor's available room is measured.
 *
 * Each variant fixes one corner of the button as the origin for the diagonal morph.
 */
private enum class OpenDirection {
    TopLeft,     // Fixed Top-Left, expands Down-Right
    TopRight,    // Fixed Top-Right, expands Down-Left
    BottomLeft,  // Fixed Bottom-Left, expands Up-Right
    BottomRight, // Fixed Bottom-Right, expands Up-Left
}

/** True when the option reveal should start at the panel top and travel downward. */
private val OpenDirection.opensDown: Boolean
    get() = this == OpenDirection.TopLeft || this == OpenDirection.TopRight

/** Linear scalar interpolation; Compose ships color/Dp lerp but not a bare Float one. */
private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/** Per-edge rectangle interpolation used for both the container and the flying selected row. */
private fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    left = lerp(start.left, stop.left, fraction),
    top = lerp(start.top, stop.top, fraction),
    right = lerp(start.right, stop.right, fraction),
    bottom = lerp(start.bottom, stop.bottom, fraction),
)

/**
 * Decides the full expansion origin from the anchor's measured room.
 *
 * Each axis picks its origin edge (Top vs Bottom, Left vs Right) independently to maximize
 * available room, unless a specific corner was requested. This ensures the panel always grows
 * diagonally from one of the button's four corners.
 */
private fun resolveDirection(
    anchor: Rect,
    panelHeightPx: Float,
    panelWidthPx: Float,
    windowHeightPx: Float,
    windowWidthPx: Float,
    edgeMarginPx: Float,
    requested: OtoPopupAlignment,
): OpenDirection {
    // 1. Vertical origin (Top vs Bottom)
    val spaceBelow = windowHeightPx - anchor.bottom - edgeMarginPx
    val spaceAbove = anchor.top - edgeMarginPx
    val opensDown = when (requested) {
        OtoPopupAlignment.TopLeft, OtoPopupAlignment.TopRight -> true
        OtoPopupAlignment.BottomLeft, OtoPopupAlignment.BottomRight -> false
        OtoPopupAlignment.Auto -> {
            if (spaceBelow >= panelHeightPx) true
            else if (spaceAbove >= panelHeightPx) false
            else spaceBelow >= spaceAbove
        }
    }

    // 2. Horizontal origin (Left vs Right)
    val maxRight = windowWidthPx - edgeMarginPx
    val spaceToRight = maxRight - anchor.left
    val spaceToLeft = anchor.right - edgeMarginPx
    val opensRight = when (requested) {
        OtoPopupAlignment.TopLeft, OtoPopupAlignment.BottomLeft -> true
        OtoPopupAlignment.TopRight, OtoPopupAlignment.BottomRight -> false
        OtoPopupAlignment.Auto -> {
            if (spaceToRight >= panelWidthPx) true
            else if (spaceToLeft >= panelWidthPx) false
            else spaceToRight >= spaceToLeft
        }
    }

    return when {
        opensDown && opensRight -> OpenDirection.TopLeft
        opensDown && !opensRight -> OpenDirection.TopRight
        !opensDown && opensRight -> OpenDirection.BottomLeft
        else -> OpenDirection.BottomRight
    }
}

/**
 * Computes the expanded panel rectangle in window coordinates from the anchor, the chosen direction,
 * the resolved panel width, and the clamped panel height.
 *
 * Horizontal and vertical placement align the chosen origin edges exactly, ensuring the morph
 * starts from a stable corner.
 */
private fun resolvePanelRect(
    anchor: Rect,
    direction: OpenDirection,
    panelWidthPx: Float,
    panelHeightPx: Float,
    windowWidthPx: Float,
    edgeMarginPx: Float,
): Rect {
    val preferredLeft = when (direction) {
        OpenDirection.TopLeft, OpenDirection.BottomLeft -> anchor.left
        OpenDirection.TopRight, OpenDirection.BottomRight -> anchor.right - panelWidthPx
    }

    val maxLeft = windowWidthPx - edgeMarginPx - panelWidthPx
    val left = if (maxLeft >= edgeMarginPx) {
        preferredLeft.coerceIn(edgeMarginPx, maxLeft)
    } else {
        edgeMarginPx
    }
    val right = left + panelWidthPx

    return if (direction.opensDown) {
        Rect(left, anchor.top, right, anchor.top + panelHeightPx)
    } else {
        Rect(left, anchor.bottom - panelHeightPx, right, anchor.bottom)
    }
}

/** Measures the chevron icon plus spacing block used by collapsed and flying rows. */
private fun Density.chevronBlockWidth(): Float =
    (PopupChevronSize + PopupSpacingBase).toPx()

/** Calculates total panel height for a fixed number of rows. */
private fun Density.calculatePanelHeight(rowCount: Int): Float {
    val rowHeightPx = PopupUnifiedHeight.toPx()
    val rowSpacingPx = PopupRowVerticalSpacing.toPx()
    return PopupPanelVerticalPadding.toPx() * 2f +
        rowCount * rowHeightPx +
        (rowCount - 1).coerceAtLeast(0) * rowSpacingPx
}

// ==========================================
// Dimensions and Tuning Constants
// ==========================================

/** The standard height for options and rows inside the Popup. */
private val PopupUnifiedHeight = 32.dp

/** Vertical gap between items in the Popup. */
private val PopupRowVerticalSpacing = 4.dp

/** Default spacing metric used for margins, padding, and layout offsets. */
private val PopupSpacingBase = 8.dp

/** Vertical padding inside the expanded panel. */
private val PopupPanelVerticalPadding = PopupSpacingBase

/** Horizontal padding at the trailing end of the collapsed button. */
private val PopupCollapsedHorizontalPadding = 16.dp

/** Horizontal padding on the sides of each item row in the expanded panel. */
private val PopupRowHorizontalPadding = 20.dp

/** Minimum touch target height for the collapsed button. */
private val PopupCollapsedTouchTarget = 48.dp

/** The width of the collapsed button's outline border. */
private val PopupBorderWidth = 1.dp

/** Size of the Popup chevron arrow icon. */
private val PopupChevronSize = 18.dp

/** Space between the primary text and the optional count text inside item rows. */
private val PopupCountSpacing = 12.dp

/** Vertical translation offset for the staggered entry animation of list options. */
private val PopupRowEnterTranslation = 14.dp

/** Maximum height of the Popup panel relative to the window height. */
private const val PopupMaxHeightFraction = 0.6f

/** Start fraction of the Popup stagger reveal animation. */
private const val PopupStaggerBase = 0.15f

/** The animation stagger step duration added per row. */
private const val PopupStaggerStep = 0.06f

/** Upper ceiling limit for the stagger animation delay fraction. */
private const val PopupStaggerMax = 0.6f

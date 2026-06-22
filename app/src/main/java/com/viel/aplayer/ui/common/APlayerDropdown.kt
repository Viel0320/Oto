package com.viel.aplayer.ui.common

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.theme.LocalHazeState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One dropdown option.
 *
 * [content] is the primary slot; callers fully own the row's leading/main content. [count] is an
 * pass null to omit. so the same component renders both "label + count"
 * filter rows and bare action rows. [key] gives each row a stable identity used for stagger keying
 * and for mapping the currently-selected row to its in-list position during the morph.
 */
@Immutable
class APlayerDropdownItem(
    val key: Any,
    val enabled: Boolean = true,
    val content: @Composable RowScope.() -> Unit,
    val count: (@Composable () -> Unit)? = null,
)

/**
 * Convenience builder for the common "label + optional count" row, such as "All 75".
 *
 * Keeps callers from writing a slot lambda for the dominant case while still producing a full
 * [APlayerDropdownItem] so the count slot stays optional and the row stays customizable elsewhere.
 */
fun aPlayerTextDropdownItem(
    key: Any,
    label: String,
    count: Int? = null,
    enabled: Boolean = true,
): APlayerDropdownItem = APlayerDropdownItem(
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
 * Requested open direction. [Auto] picks Down/Up from the anchor's on-screen room; [Down]/[Up] force
 * it. The resolved direction also sets the animation origin so the panel always grows from the edge
 * nearest the button.
 */
enum class APlayerDropdownAlignment { Auto, Down, Up }

/**
 * Expanded panel width strategy.
 *
 * [MatchAnchor] keeps the panel exactly as wide as the button so the morph grows straight out of it.
 * [Wrap] sizes to the widest option. [Fixed] pins an explicit width.
 */
@Immutable
sealed interface APlayerDropdownWidth {
    data object MatchAnchor : APlayerDropdownWidth
    data object Wrap : APlayerDropdownWidth
    data class Fixed(val width: Dp) : APlayerDropdownWidth
}

/**
 * Color roles for both morph endpoints. Interpolated continuously by the expand fraction, so the
 * collapsed outlined button and the filled floating panel are two ends of one surface, not two
 * surfaces swapped at a threshold.
 */
@Immutable
data class APlayerDropdownColors(
    val collapsedContainer: Color,
    val collapsedBorder: Color,
    val expandedContainer: Color,
    val expandedBorder: Color,
    val content: Color,
)

/** Corner radii for both morph endpoints; the live corner is lerp'd between them by the fraction. */
@Immutable
data class APlayerDropdownShapes(
    val collapsedCorner: Dp = 8.dp,
    val expandedCorner: Dp = 8.dp,
)

/**
 * Theme-aligned defaults. Collapsed reads as a Material outlined button (surface + 1dp outline);
 * expanded reads as a filled menu surface (surfaceContainerHigh, no border, soft elevation).
 */
object APlayerDropdownDefaults {
    val CollapsedElevation = 0.dp
    val ExpandedElevation = 6.dp

    @Composable
    fun colors(): APlayerDropdownColors {
        val scheme = MaterialTheme.colorScheme
        return APlayerDropdownColors(
            collapsedContainer = scheme.surface,
            collapsedBorder = scheme.outline,
            expandedContainer = scheme.surfaceContainerHigh,
            expandedBorder = Color.Transparent,
            content = scheme.onSurface,
        )
    }

    @Composable
    fun shapes(): APlayerDropdownShapes = APlayerDropdownShapes()
}
/**
 * Resolved expansion origin after the anchor's available room is measured.
 *
 * The vertical half controls whether rows unfold down or up. The horizontal half controls which edge
 * the expanded panel grows toward when a centered panel would cross a screen edge: `Right` anchors
 * the panel to the button's left edge, while `Left` anchors it to the button's right edge. The final
 * rectangle is still clamped by the window margins, so oversized fixed-width menus degrade to the
 * closest visible edge.
 */
private enum class OpenDirection {
    DownCenter,
    DownRight,
    DownLeft,
    UpCenter,
    UpRight,
    UpLeft,
}

/** True when the option reveal should start at the panel top and travel downward. */
private val OpenDirection.opensDown: Boolean
    get() = when (this) {
        OpenDirection.DownCenter,
        OpenDirection.DownRight,
        OpenDirection.DownLeft,
        -> true
        OpenDirection.UpCenter,
        OpenDirection.UpRight,
        OpenDirection.UpLeft,
        -> false
    }

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
private class DropdownMorphState(
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
 * Down aligns the button's top to the panel's top; Up aligns the button's bottom to the panel's
 * bottom. The centered horizontal placement remains preferred because it preserves the old visual
 * behavior on ordinary anchors. When centering would cross a window edge, the panel opens toward the
 * side with enough room; if neither side can fully fit the requested width, the larger side wins.
 */
private fun resolveDirection(
    anchor: Rect,
    panelHeightPx: Float,
    panelWidthPx: Float,
    windowHeightPx: Float,
    windowWidthPx: Float,
    edgeMarginPx: Float,
    requested: APlayerDropdownAlignment,
): OpenDirection {
    val spaceBelow = windowHeightPx - anchor.bottom - edgeMarginPx
    val spaceAbove = anchor.top - edgeMarginPx
    val opensDown = when (requested) {
        APlayerDropdownAlignment.Down -> true
        APlayerDropdownAlignment.Up -> false
        APlayerDropdownAlignment.Auto -> when {
            spaceBelow >= panelHeightPx -> true
            spaceAbove >= panelHeightPx -> false
            else -> spaceBelow >= spaceAbove
        }
    }

    val maxRight = windowWidthPx - edgeMarginPx
    val centeredLeft = anchor.center.x - panelWidthPx / 2f
    val centeredRight = centeredLeft + panelWidthPx
    if (centeredLeft >= edgeMarginPx && centeredRight <= maxRight) {
        return if (opensDown) OpenDirection.DownCenter else OpenDirection.UpCenter
    }

    val spaceToRight = maxRight - anchor.left
    val spaceToLeft = anchor.right - edgeMarginPx
    val fitsRight = spaceToRight >= panelWidthPx
    val fitsLeft = spaceToLeft >= panelWidthPx
    val opensRight = when {
        fitsRight && !fitsLeft -> true
        fitsLeft && !fitsRight -> false
        else -> spaceToRight >= spaceToLeft
    }

    return when {
        opensDown && opensRight -> OpenDirection.DownRight
        opensDown && !opensRight -> OpenDirection.DownLeft
        !opensDown && opensRight -> OpenDirection.UpRight
        else -> OpenDirection.UpLeft
    }
}

/**
 * Computes the expanded panel rectangle in window coordinates from the anchor, the chosen direction,
 * the resolved panel width, and the clamped panel height.
 *
 * Horizontal placement uses the resolved growth side first, then clamps the chosen left edge inside
 * the screen margins. This keeps the morph origin close to the button on narrow screens while still
 * preserving centered menus where there is enough room.
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
        OpenDirection.DownCenter,
        OpenDirection.UpCenter,
        -> anchor.center.x - panelWidthPx / 2f
        OpenDirection.DownRight,
        OpenDirection.UpRight,
        -> anchor.left
        OpenDirection.DownLeft,
        OpenDirection.UpLeft,
        -> anchor.right - panelWidthPx
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
/**
 * A dropdown that morphs between a collapsed outlined button and an expanded floating panel.
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
fun APlayerDropdown(
    items: List<APlayerDropdownItem>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    collapsedContent: (@Composable RowScope.() -> Unit)? = null,
    alignment: APlayerDropdownAlignment = APlayerDropdownAlignment.Auto,
    panelWidth: APlayerDropdownWidth = APlayerDropdownWidth.MatchAnchor,
    panelMaxHeight: Dp = Dp.Unspecified,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    colors: APlayerDropdownColors = APlayerDropdownDefaults.colors(),
    shapes: APlayerDropdownShapes = APlayerDropdownDefaults.shapes(),
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val morph = remember { DropdownMorphState(if (expanded) 1f else 0f, scope) }
    LaunchedEffect(expanded) { morph.animateTo(if (expanded) 1f else 0f) }

    val safeSelectedIndex = selectedIndex?.takeIf { it in items.indices }

    val resolvedHazeState = hazeState ?: LocalHazeState.current

    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val fractionProvider = morph.provider()

    Box(
        modifier = modifier
            .graphicsLayer { alpha = 1f - fractionProvider().coerceIn(0f, 1f) }
    ) {
        CollapsedAnchor(
            items = items,
            selectedIndex = safeSelectedIndex,
            collapsedContent = collapsedContent,
            colors = colors,
            shapes = shapes,
            expanded = expanded,
            hazeState = resolvedHazeState,
            glassEffectMode = glassEffectMode,
            onClick = { onExpandedChange(!expanded) },
            onVisualBoundsMeasured = { anchorBounds = it },
        )
    }

    val bounds = anchorBounds
    if (bounds != null && morph.isVisible) {
        DropdownPopup(
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
            glassEffectMode = glassEffectMode,
            density = density,
            onSelect = onSelect,
            onDismiss = { onExpandedChange(false) },
        )
    }
}

/**
 * The resting collapsed button: an outlined surface with the current selection (filter) or a custom
 * [collapsedContent] (menu), plus a chevron. This is the in-layout node whose bounds are measured;
 * during the morph it is hidden and the popup's morph surface stands in for it.
 *
 * In [GlassEffectMode.Haze] the visual pill carries the same haze effect as the expanded morph
 * surface, so the resting state reads as glass too instead of a bare outlined chip.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun CollapsedAnchor(
    items: List<APlayerDropdownItem>,
    selectedIndex: Int?,
    collapsedContent: (@Composable RowScope.() -> Unit)?,
    colors: APlayerDropdownColors,
    shapes: APlayerDropdownShapes,
    expanded: Boolean,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    onClick: () -> Unit,
    onVisualBoundsMeasured: (Rect) -> Unit,
) {
    val shape = RoundedCornerShape(shapes.collapsedCorner)
    val expandedStateDescription = if (expanded) "expanded" else "collapsed"

    val containerColor = Color.Transparent
    val borderColor = colors.collapsedBorder
    val contentColor = colors.content

    val hazeModifier = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin())
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = DropdownCollapsedTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.DropdownList,
                onClick = onClick,
            )
            .semantics { stateDescription = expandedStateDescription },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .onGloballyPositioned { onVisualBoundsMeasured(it.boundsInWindow()) }
                .height(DropdownUnifiedHeight)
                .clip(shape)
                .then(hazeModifier)
                .background(containerColor, shape)
                .border(DropdownBorderWidth, borderColor, shape)
                .padding(start = DropdownSpacingBase, end = DropdownCollapsedHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DropdownSpacingBase),
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(DropdownChevronSize),
                tint = contentColor,
            )
            if (collapsedContent != null) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                ) {
                    collapsedContent()
                }
            } else {
                val item = selectedIndex?.let(items::getOrNull)
                if (item != null) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides contentColor,
                    ) {
                        ProvideTextStyle(
                            MaterialTheme.typography.labelLarge
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DropdownCountSpacing),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, content = item.content)
                                item.count?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Always positions the popup at the window's top-left so window coords map directly to popup coords. */
private object TopLeftPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ) = androidx.compose.ui.unit.IntOffset(0, 0)
}

/**
 * clipping disabled. so the morph surface can paint above or
 * below the anchor. Estimates the panel height to choose a direction on the first frame (no jump),
 * then refines from the real measured height. All morphed geometry is computed per-frame from the
 * shared fraction and read only in draw/layout lambdas.
 */
@Composable
private fun DropdownPopup(
    anchor: Rect,
    items: List<APlayerDropdownItem>,
    selectedIndex: Int?,
    morph: DropdownMorphState,
    colors: APlayerDropdownColors,
    shapes: APlayerDropdownShapes,
    alignment: APlayerDropdownAlignment,
    panelWidth: APlayerDropdownWidth,
    panelMaxHeight: Dp,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    density: Density,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
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
            val edgeMarginPx = with(density) { DropdownSpacingBase.toPx() }
            val maxWrapWidth = maxWidth - DropdownSpacingBase * 2

            var measuredPanelHeightPx by remember { mutableStateOf<Float?>(null) }
            var measuredPanelWidthPx by remember { mutableStateOf<Float?>(null) }

            val fixedWidthDp: Dp? = when (panelWidth) {
                APlayerDropdownWidth.MatchAnchor -> with(density) { anchor.width.toDp() }
                is APlayerDropdownWidth.Fixed -> panelWidth.width
                APlayerDropdownWidth.Wrap -> null
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
                winHPx * DropdownMaxHeightFraction
            }
            val panelHeightPx = (measuredPanelHeightPx ?: estimatedHeightPx).coerceAtMost(maxHeightPx)

            val resolvedWidthPx = when (panelWidth) {
                APlayerDropdownWidth.MatchAnchor -> anchor.width
                is APlayerDropdownWidth.Fixed -> with(density) { panelWidth.width.toPx() }
                APlayerDropdownWidth.Wrap -> {
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

            DropdownMorphSurface(
                anchor = anchor,
                panelRect = panelRect,
                direction = direction,
                items = items,
                selectedIndex = selectedIndex,
                morph = morph,
                colors = colors,
                shapes = shapes,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode,
                density = density,
                onSelect = onSelect,
                onDismiss = onDismiss,
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
    items: List<APlayerDropdownItem>,
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
            .padding(vertical = DropdownPanelVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(DropdownRowVerticalSpacing),
    ) {
        items.forEach { item ->
            Box(
                modifier = Modifier
                    .wrapContentWidth()
                    .heightIn(min = DropdownUnifiedHeight)
                    .padding(horizontal = DropdownRowHorizontalPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DropdownCountSpacing),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, content = item.content)
                        item.count?.invoke()
                    }
                }
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
private fun DropdownMorphSurface(
    anchor: Rect,
    panelRect: Rect,
    direction: OpenDirection,
    items: List<APlayerDropdownItem>,
    selectedIndex: Int?,
    morph: DropdownMorphState,
    colors: APlayerDropdownColors,
    shapes: APlayerDropdownShapes,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    density: Density,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val fractionProvider = morph.provider()
    val collapsedCornerPx = with(density) { shapes.collapsedCorner.toPx() }
    val expandedCornerPx = with(density) { shapes.expandedCorner.toPx() }
    val collapsedBorderPx = with(density) { DropdownBorderWidth.toPx() }
    val collapsedElevationPx = with(density) { APlayerDropdownDefaults.CollapsedElevation.toPx() }
    val expandedElevationPx = with(density) { APlayerDropdownDefaults.ExpandedElevation.toPx() }
    val panelPaddingPx = with(density) { DropdownPanelVerticalPadding.toPx() }

    val hazeModifier = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
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
                val placeable = measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(w, h),
                )
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
                if (glassEffectMode != GlassEffectMode.Haze || hazeState == null) {
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
        DropdownOptionList(
            items = items,
            selectedIndex = selectedIndex,
            direction = direction,
            fractionProvider = fractionProvider,
            contentColor = colors.content,
            verticalPadding = DropdownPanelVerticalPadding,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )

        if (selectedIndex != null) {
            val rowHeightPx = with(density) { DropdownUnifiedHeight.toPx() }
            FlyingSelectedRow(
                item = items[selectedIndex],
                selectedIndex = selectedIndex,
                anchor = anchor,
                panelRect = panelRect,
                fractionProvider = fractionProvider,
                contentColor = colors.content,
                collapsedVisualHeightPx = with(density) { DropdownUnifiedHeight.toPx() },
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
private fun DropdownOptionList(
    items: List<APlayerDropdownItem>,
    selectedIndex: Int?,
    direction: OpenDirection,
    fractionProvider: () -> Float,
    contentColor: Color,
    verticalPadding: Dp,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val rowEnterTranslationPx = with(LocalDensity.current) { DropdownRowEnterTranslation.toPx() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(DropdownRowVerticalSpacing),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selectedIndex
            val orderFromOrigin = if (direction.opensDown) index else items.lastIndex - index
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DropdownUnifiedHeight)
                    .graphicsLayer {
                        if (isSelected) {
                            alpha = if (fractionProvider() >= 0.999f) 1f else 0f
                        } else {
                            val startAt = (DropdownStaggerBase + orderFromOrigin * DropdownStaggerStep)
                                .coerceAtMost(DropdownStaggerMax)
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
                    .padding(horizontal = DropdownRowHorizontalPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!isSelected) {
                    DropdownRowContent(item = item, contentColor = contentColor)
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
    item: APlayerDropdownItem,
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
    val horizontalPaddingPx = with(density) { DropdownRowHorizontalPadding.toPx() }
    val collapsedPaddingPx = with(density) { DropdownCollapsedHorizontalPadding.toPx() }
    val collapsedLeadingPaddingPx = with(density) { DropdownSpacingBase.toPx() }
    val rowSpacingPx = with(density) { DropdownRowVerticalSpacing.toPx() }
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
                val placeable = measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(w, h),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        (rect.left - surfaceLeft).roundToInt(),
                        (rect.top - surfaceTop).roundToInt(),
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        DropdownRowContent(item = item, contentColor = contentColor)
    }
}

/** Shared row body: the caller's content slot plus the optional trailing count slot. */
@Composable
private fun DropdownRowContent(
    item: APlayerDropdownItem,
    contentColor: Color,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides contentColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DropdownCountSpacing),
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                content = item.content,
            )
            item.count?.invoke()
        }
    }
}
private val DropdownUnifiedHeight = 32.dp
private val DropdownRowVerticalSpacing = 4.dp

private val DropdownSpacingBase = 8.dp
private val DropdownPanelVerticalPadding = DropdownSpacingBase

private val DropdownCollapsedHorizontalPadding = 16.dp
private val DropdownRowHorizontalPadding = 20.dp

private val DropdownCollapsedTouchTarget = 48.dp
private val DropdownBorderWidth = 1.dp
private val DropdownChevronSize = 18.dp
private val DropdownCountSpacing = 12.dp
private val DropdownRowEnterTranslation = 14.dp
/** Measures the chevron icon plus spacing block used by collapsed and flying rows. */
private fun Density.chevronBlockWidth(): Float =
    (DropdownChevronSize + DropdownSpacingBase).toPx()

/** Calculates total panel height for a fixed number of rows. */
private fun Density.calculatePanelHeight(rowCount: Int): Float {
    val rowHeightPx = DropdownUnifiedHeight.toPx()
    val rowSpacingPx = DropdownRowVerticalSpacing.toPx()
    return DropdownPanelVerticalPadding.toPx() * 2f +
        rowCount * rowHeightPx +
        (rowCount - 1).coerceAtLeast(0) * rowSpacingPx
}
private const val DropdownMaxHeightFraction = 0.6f
private const val DropdownStaggerBase = 0.15f
private const val DropdownStaggerStep = 0.06f
private const val DropdownStaggerMax = 0.6f

package com.viel.aplayer.ui.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.detail.components.DetailInfoChip
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.player.miniplayer.PillCompactMediaPlayer
import com.viel.aplayer.ui.settings.about.AboutLibrariesScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks compact controls and metadata chips under constrained layouts.
 *
 * Exercises the individual UI slices named in the accessibility finding so compact player,
 * Detail, and About regressions are caught without coupling these checks to full app navigation.
 */
@RunWith(AndroidJUnit4::class)
class StableBoundsAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pillPlayPauseCommandKeepsMinimumTouchTarget() {
        val playDescription = composeRule.activity.getString(R.string.playback_play_content_description)

        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                PillCompactMediaPlayer(
                    bookId = "accessibility-pill-book",
                    isPlaying = false,
                    actions = MiniPlayerActions(onPlayPauseClick = {})
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(playDescription)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aboutProjectLinkCommandKeepsMinimumTouchTarget() {
        val visitDescription = composeRule.activity.getString(
            R.string.about_visit_project_homepage_content_description
        )

        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                AboutLibrariesScreen(
                    onBack = {},
                    glassEffectMode = GlassEffectMode.Material
                )
            }
        }

        // Project links are intentionally inside expanded license details, so the test opens the
        // first generated dependency card before asserting the link command's touch target.
        composeRule
            .onAllNodes(hasText("Activity") and hasClickAction())
            .onFirst()
            .performScrollTo()
            .performClick()

        composeRule
            .onAllNodesWithContentDescription(visitDescription)
            .onFirst()
            .performScrollTo()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun detailInfoChipStaysInsideNarrowLargeFontBounds() {
        val rootTag = "detail-info-chip-overflow-root"
        val chipTag = "detail-info-chip-stable-bounds"
        val narrowWidth = 112.dp
        val rootWidth = 240.dp
        val rootHeight = 64.dp

        composeRule.setContent {
            APlayerTheme(dynamicColor = false) {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density,
                        fontScale = 2f
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .width(rootWidth)
                            .height(rootHeight)
                            .background(Color.White)
                            .androidxComposeUiTestTag(rootTag)
                    ) {
                        DetailInfoChip(
                            icon = Icons.Rounded.Event,
                            value = "Very long localized metadata value 2026",
                            modifier = Modifier
                                .width(narrowWidth)
                                .androidxComposeUiTestTag(chipTag)
                        )
                    }
                }
            }
        }

        composeRule.assertNodeWidthAtMost(chipTag, narrowWidth)

        composeRule.assertNoNonBackgroundPixelsBeyond(
            rootTag = rootTag,
            boundary = narrowWidth + 4.dp,
            background = Color.White
        )
    }

    private companion object {
        private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.assertNodeWidthAtMost(
            tag: String,
            maxWidth: Dp
        ) {
            val actualWidthPx = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().size.width
            val maxWidthPx = with(density) { maxWidth.roundToPx() }

            assertTrue(
                "Expected node '$tag' width <= $maxWidthPx px, but was $actualWidthPx px",
                actualWidthPx <= maxWidthPx
            )
        }

        private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.assertNoNonBackgroundPixelsBeyond(
            rootTag: String,
            boundary: Dp,
            background: Color
        ) {
            val image = onNodeWithTag(rootTag, useUnmergedTree = true).captureToImage()
            val pixels = image.toPixelMap()
            val startX = with(density) { boundary.roundToPx() }.coerceAtMost(image.width)
            var paintedPixels = 0

            for (x in startX until image.width) {
                for (y in 0 until image.height) {
                    if (!pixels[x, y].isCloseTo(background)) {
                        paintedPixels += 1
                    }
                }
            }

            assertEquals("Expected no painted pixels beyond $startX px", 0, paintedPixels)
        }

        private fun Color.isCloseTo(expected: Color): Boolean =
            kotlin.math.abs(red - expected.red) < 0.01f &&
                kotlin.math.abs(green - expected.green) < 0.01f &&
                kotlin.math.abs(blue - expected.blue) < 0.01f &&
                kotlin.math.abs(alpha - expected.alpha) < 0.01f

        private fun Modifier.androidxComposeUiTestTag(tag: String): Modifier =
            this.testTag(tag)
    }
}

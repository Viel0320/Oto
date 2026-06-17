package com.viel.aplayer.ui.common.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression baseline for the MaterialKolor-backed dynamic color generation that replaced the
 * in-house HSL approximation. Verifies the generator is genuinely seed-driven and produces
 * distinct light/dark schemes, without asserting exact HCT output (which is owned by the library).
 */
class DynamicColorSchemeTest {

    private val seed = Color(0xFF6750A4)

    @Test
    fun light_and_dark_primaries_differ_for_same_seed() {
        val light = dynamicColorScheme(seedColor = seed, isDark = false, style = PaletteStyle.Content)
        val dark = dynamicColorScheme(seedColor = seed, isDark = true, style = PaletteStyle.Content)
        assertNotEquals(light.primary, dark.primary)
    }

    @Test
    fun scheme_is_seed_driven_not_uninitialised() {
        val scheme = dynamicColorScheme(seedColor = seed, isDark = false, style = PaletteStyle.Content)
        // A seed-derived primary should be opaque and distinct from the surface role.
        assertTrue(scheme.primary.alpha > 0f)
        assertNotEquals(scheme.primary, scheme.surface)
    }

    @Test
    fun different_seeds_produce_different_primaries() {
        val purple = dynamicColorScheme(seedColor = Color(0xFF6750A4), isDark = false, style = PaletteStyle.Content)
        val teal = dynamicColorScheme(seedColor = Color(0xFF006A60), isDark = false, style = PaletteStyle.Content)
        assertNotEquals(purple.primary, teal.primary)
    }
}

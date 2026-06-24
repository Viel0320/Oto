package com.viel.oto.ui.common.theme

import com.viel.oto.shared.settings.GlassEffectMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualEffectPolicyTest {
    @Test
    fun `haze downgrades to material on low ram or power saver devices`() {
        val lowRam = VisualEffectEnvironment(isLowRamDevice = true, isPowerSaveMode = false)
        val powerSaver = VisualEffectEnvironment(isLowRamDevice = false, isPowerSaveMode = true)

        assertEquals(
            GlassEffectMode.Material,
            VisualEffectPolicy.resolveGlassEffectMode(GlassEffectMode.Haze, lowRam)
        )
        assertEquals(
            GlassEffectMode.Material,
            VisualEffectPolicy.resolveGlassEffectMode(GlassEffectMode.Haze, powerSaver)
        )
    }

    @Test
    fun `haze remains enabled when the runtime environment is unconstrained`() {
        val unconstrained = VisualEffectEnvironment(isLowRamDevice = false, isPowerSaveMode = false)

        assertEquals(
            GlassEffectMode.Haze,
            VisualEffectPolicy.resolveGlassEffectMode(GlassEffectMode.Haze, unconstrained)
        )
    }
}

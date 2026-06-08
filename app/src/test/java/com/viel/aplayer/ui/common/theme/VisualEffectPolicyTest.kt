package com.viel.aplayer.ui.common.theme

import com.viel.aplayer.data.store.GlassEffectMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualEffectPolicyTest {
    @Test
    fun `haze downgrades to material on low ram or power saver devices`() {
        val lowRam = VisualEffectEnvironment(isLowRamDevice = true, isPowerSaveMode = false)
        val powerSaver = VisualEffectEnvironment(isLowRamDevice = false, isPowerSaveMode = true)

        // Expensive Effect Downgrade Policy (Protects playback UI on constrained devices)
        // Haze remains a user preference, but the executable mode degrades when runtime capability signals are constrained.
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

        // Unconstrained Effect Policy (Preserves explicit visual preference on capable devices)
        // Devices without low-RAM or power-save constraints should keep the requested Haze mode.
        assertEquals(
            GlassEffectMode.Haze,
            VisualEffectPolicy.resolveGlassEffectMode(GlassEffectMode.Haze, unconstrained)
        )
    }
}

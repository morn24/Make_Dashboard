package com.morn24.dashboard.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetColorResolverTest {
    @Test
    fun darkBackgroundStaysDarkAcrossEveryAccentPalette() {
        val colors = WidgetColorResolver.palettes.map { palette ->
            WidgetColorResolver.resolve(WidgetAppearance(colorMode = AppearanceColorMode.DARK, accentPaletteId = palette.id))
        }

        assertEquals(1, colors.map(ResolvedWidgetColors::background).distinct().size)
        assertEquals(WidgetColorResolver.palettes.size, colors.map(ResolvedWidgetColors::action).distinct().size)
        assertEquals(WidgetColorResolver.palettes.size, colors.map(ResolvedWidgetColors::schedule).distinct().size)
        assertEquals(WidgetColorResolver.palettes.size, colors.map(ResolvedWidgetColors::project).distinct().size)
        assertEquals(WidgetColorResolver.palettes.size, colors.map(ResolvedWidgetColors::progress).distinct().size)
        assertTrue(colors.all { it.background == 0xFF171A17.toInt() })
    }

    @Test
    fun paletteSelectionIsIndependentFromBackgroundAndOpacitySettings() {
        val mint = WidgetAppearance(colorMode = AppearanceColorMode.DARK, accentPaletteId = "mint-blue-lavender", backgroundOpacityPercent = 0)
        val amber = mint.copy(accentPaletteId = "amber-sky-mauve")
        val first = WidgetColorResolver.resolve(mint)
        val second = WidgetColorResolver.resolve(amber)

        assertEquals(first.background, second.background)
        assertNotEquals(first.action, second.action)
        assertEquals(0, mint.backgroundOpacityPercent)
        assertEquals(50, mint.copy(backgroundOpacityPercent = 50).backgroundOpacityPercent)
        assertEquals(100, mint.copy(backgroundOpacityPercent = 100).backgroundOpacityPercent)
    }

    @Test
    fun customHexIsRetainedAndStillInfluencesPaletteRoles() {
        val custom = WidgetAppearance(
            colorMode = AppearanceColorMode.CUSTOM,
            backgroundHex = "#123456",
            accentHex = "#D06030",
            accentPaletteId = "sky-amber-purple",
        )
        val resolved = WidgetColorResolver.resolve(custom)
        val other = WidgetColorResolver.resolve(custom.copy(accentPaletteId = "amber-sky-mauve"))

        assertEquals(0xFF123456.toInt(), resolved.background)
        assertEquals("#D06030", custom.sanitized().accentHex)
        assertNotEquals(resolved.action, other.action)
    }

    @Test
    fun legacyAndUnknownPaletteSettingsFallBackWithoutChangingOtherFields() {
        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString<WidgetAppearance>(
            """{"colorMode":"DARK","backgroundHex":"#171A17","accentHex":"#91D5B3","fontScalePercent":130,"backgroundOpacityPercent":35,"contrastAssist":false,"futureField":"ignored"}""",
        )
        val unknown = legacy.copy(accentPaletteId = "future-palette").sanitized()

        assertEquals("mint-blue-lavender", legacy.sanitized().accentPaletteId)
        assertEquals("mint-blue-lavender", unknown.accentPaletteId)
        assertEquals(130, unknown.fontScalePercent)
        assertEquals(35, unknown.backgroundOpacityPercent)
        assertEquals("#91D5B3", unknown.accentHex)
        assertEquals(false, unknown.contrastAssist)
    }

    @Test
    fun calendarEventHexIsKeptExactlyAndMalformedValuesUseNeutralFallback() {
        assertEquals(0xFFFF0000.toInt(), WidgetColorResolver.eventColor("#FF0000"))
        assertEquals(0xFF123ABC.toInt(), WidgetColorResolver.eventColor("#123abc"))
        assertEquals(0xFF9AA0A6.toInt(), WidgetColorResolver.eventColor("not-a-color"))
        assertEquals(0xFF9AA0A6.toInt(), WidgetColorResolver.eventColor(null))
    }
}

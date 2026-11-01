package com.morn24.dashboard.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    fun warmPalettesAreAddedWithoutChangingLegacyIdsAndKeepTheirOwnAccentColors() {
        assertEquals(
            listOf("mint-blue-lavender", "sky-amber-purple", "lavender-mint-peach", "amber-sky-mauve"),
            WidgetColorResolver.palettes.take(4).map(WidgetAccentPalette::id),
        )

        val warm = WidgetColorResolver.palettes.drop(4)
        assertEquals(
            listOf("coral-red-sage-cream", "apricot-orange-blue-mint", "butter-yellow-sky-plum"),
            warm.map(WidgetAccentPalette::id),
        )
        val resolved = warm.map { palette ->
            palette to WidgetColorResolver.resolve(
                WidgetAppearance(colorMode = AppearanceColorMode.DARK, accentPaletteId = palette.id),
            )
        }
        assertTrue(resolved.all { (palette, colors) -> colors.action == palette.action && colors.progress == palette.progress })
        resolved.forEach { (_, colors) ->
            val red = colors.action ushr 16 and 0xFF
            val green = colors.action ushr 8 and 0xFF
            assertTrue(red > green)
        }
    }

    @Test
    fun warmPaletteAccentsRemainReadableOnLightBackgrounds() {
        WidgetColorResolver.palettes.drop(4).forEach { palette ->
            val colors = WidgetColorResolver.resolve(
                WidgetAppearance(colorMode = AppearanceColorMode.LIGHT, accentPaletteId = palette.id),
            )
            assertTrue(contrastRatio(colors.action, colors.background) >= 4.5)
            assertTrue(contrastRatio(colors.progress, colors.background) >= 4.5)
            assertTrue(contrastRatio(colors.todo, colors.background) >= 4.5)
            assertTrue(contrastRatio(colors.schedule, colors.background) >= 4.5)
            assertTrue(contrastRatio(colors.project, colors.background) >= 4.5)
        }
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

    @Test
    fun sharedCalendarHexFlowsThroughMapperToWidgetMarkerColor() {
        val events = DashboardDisplayData.events(
            Json.parseToJsonElement(
                """{"version":1,"events":[{"id":"fixture-event-01","title":"일정 EVT-01","calendar":"익명 fixture","color":"#E53935","allDay":false,"start":"2026-11-01T09:30:00+09:00","end":"2026-11-01T10:00:00+09:00"}]}""",
            ).jsonObject,
        )

        assertEquals("#E53935", events.single().color)
        assertEquals(0xFFE53935.toInt(), WidgetColorResolver.eventColor(events.single().color))
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = (color ushr shift and 0xFF) / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val brighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (brighter + 0.05) / (darker + 0.05)
    }
}

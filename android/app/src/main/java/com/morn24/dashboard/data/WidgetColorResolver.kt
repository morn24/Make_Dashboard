package com.morn24.dashboard.data

/** Color roles shared by the Compose appearance preview and the Glance widget. */
data class ResolvedWidgetColors(
    val background: Int,
    val foreground: Int,
    val secondaryText: Int,
    val action: Int,
    val progress: Int,
    val todo: Int,
    val schedule: Int,
    val project: Int,
    val todoSurface: Int,
    val scheduleSurface: Int,
    val projectSurface: Int,
    val error: Int,
)

data class WidgetAccentPalette(
    val id: String,
    val name: String,
    val todo: Int,
    val schedule: Int,
    val project: Int,
    val progress: Int,
    val action: Int,
)

object WidgetColorResolver {
    val palettes: List<WidgetAccentPalette> = listOf(
        WidgetAccentPalette(
            id = "mint-blue-lavender",
            name = "민트 · 블루 · 라벤더",
            todo = rgb(0x91, 0xD5, 0xB3),
            schedule = rgb(0xAF, 0xC8, 0xFF),
            project = rgb(0xD5, 0xB7, 0xED),
            progress = rgb(0x91, 0xD5, 0xB3),
            action = rgb(0x91, 0xD5, 0xB3),
        ),
        WidgetAccentPalette(
            id = "sky-amber-purple",
            name = "스카이 · 앰버 · 퍼플",
            todo = rgb(0x8C, 0xCE, 0xFF),
            schedule = rgb(0xF2, 0xCC, 0x8F),
            project = rgb(0xB8, 0xB5, 0xFF),
            progress = rgb(0x7F, 0xB6, 0xE6),
            action = rgb(0x8C, 0xCE, 0xFF),
        ),
        WidgetAccentPalette(
            id = "lavender-mint-peach",
            name = "라벤더 · 민트 · 피치",
            todo = rgb(0xC8, 0xB8, 0xFF),
            schedule = rgb(0x97, 0xDC, 0xC5),
            project = rgb(0xF0, 0xB8, 0xA8),
            progress = rgb(0xB1, 0xA4, 0xE9),
            action = rgb(0xC8, 0xB8, 0xFF),
        ),
        WidgetAccentPalette(
            id = "amber-sky-mauve",
            name = "앰버 · 스카이 · 모브",
            todo = rgb(0xF4, 0xCF, 0x8E),
            schedule = rgb(0x9B, 0xCF, 0xFF),
            project = rgb(0xD5, 0xBA, 0xED),
            progress = rgb(0xDB, 0xB8, 0x79),
            action = rgb(0xF4, 0xCF, 0x8E),
        ),
    )

    fun resolve(
        appearance: WidgetAppearance,
        automaticBackground: Int = rgb(0xFF, 0xFF, 0xFF),
        automaticAccent: Int = rgb(0x00, 0x6C, 0x4C),
    ): ResolvedWidgetColors {
        val safe = appearance.sanitized()
        val background = when (safe.colorMode) {
            AppearanceColorMode.AUTOMATIC -> automaticBackground
            AppearanceColorMode.LIGHT -> rgb(0xFF, 0xF8, 0xF2)
            AppearanceColorMode.DARK -> rgb(0x17, 0x1A, 0x17)
            AppearanceColorMode.OCEAN -> rgb(0xE9, 0xF3, 0xFC)
            AppearanceColorMode.FOREST -> rgb(0xED, 0xF4, 0xE9)
            AppearanceColorMode.CUSTOM -> parseHex(safe.backgroundHex) ?: rgb(0xFF, 0xFF, 0xFF)
        }
        val legacyAccent = when (safe.colorMode) {
            AppearanceColorMode.AUTOMATIC -> automaticAccent
            AppearanceColorMode.LIGHT -> rgb(0x00, 0x6C, 0x4C)
            AppearanceColorMode.DARK -> rgb(0x91, 0xD5, 0xB3)
            AppearanceColorMode.OCEAN -> rgb(0x20, 0x56, 0xA0)
            AppearanceColorMode.FOREST -> rgb(0x35, 0x62, 0x3B)
            AppearanceColorMode.CUSTOM -> parseHex(safe.accentHex) ?: rgb(0x00, 0x6C, 0x4C)
        }
        val palette = palettes.firstOrNull { it.id == safe.accentPaletteId } ?: palettes.first()
        val isDark = luminance(background) <= 0.45
        val foreground = if (isDark) rgb(0xFF, 0xFF, 0xFF) else rgb(0x19, 0x1B, 0x19)
        val secondaryText = if (isDark) rgb(0xD0, 0xD4, 0xD0) else rgb(0x50, 0x54, 0x50)
        val blendTowardPalette = 0.45
        val todo = mix(legacyAccent, palette.todo, blendTowardPalette)
        val schedule = mix(legacyAccent, palette.schedule, 0.22)
        val project = mix(legacyAccent, palette.project, 0.22)
        val action = mix(legacyAccent, palette.action, blendTowardPalette)
        val progress = mix(legacyAccent, palette.progress, blendTowardPalette)
        val panelMix = if (safe.contrastAssist) 0.15 else 0.09
        val todoSurface = mix(background, todo, panelMix)
        val scheduleSurface = mix(background, schedule, panelMix)
        val projectSurface = mix(background, project, panelMix)

        return ResolvedWidgetColors(
            background = background,
            foreground = foreground,
            secondaryText = secondaryText,
            action = action,
            progress = progress,
            todo = todo,
            schedule = schedule,
            project = project,
            todoSurface = todoSurface,
            scheduleSurface = scheduleSurface,
            projectSurface = projectSurface,
            error = if (isDark) rgb(0xFF, 0xB4, 0xAB) else rgb(0xB3, 0x26, 0x1E),
        )
    }

    fun eventColor(hex: String?): Int = hex?.let(::parseHex) ?: rgb(0x9A, 0xA0, 0xA6)

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    fun mix(first: Int, second: Int, fraction: Double): Int {
        val ratio = fraction.coerceIn(0.0, 1.0)
        fun component(shift: Int): Int =
            (((first ushr shift) and 0xFF) * (1.0 - ratio) + ((second ushr shift) and 0xFF) * ratio).toInt().coerceIn(0, 255)
        return rgb(component(16), component(8), component(0))
    }

    private fun parseHex(value: String): Int? {
        if (!HEX.matches(value)) return null
        return value.drop(1).toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
}

package com.morn24.dashboard.data

import kotlinx.serialization.Serializable

data class EncryptedToken(
    val ivBase64: String,
    val ciphertextBase64: String,
)

data class DashboardSettings(
    val owner: String = DEFAULT_OWNER,
    val repository: String = DEFAULT_REPOSITORY,
    val branch: String = DEFAULT_BRANCH,
    val webUrl: String = DEFAULT_WEB_URL,
    val encryptedToken: EncryptedToken? = null,
) {
    val repositoryIdentity: String get() = "$owner/$repository@$branch"

    companion object {
        const val DEFAULT_OWNER = "morn24"
        const val DEFAULT_REPOSITORY = "dashboard-data"
        const val DEFAULT_BRANCH = "main"
        const val DEFAULT_WEB_URL = "https://morn24.github.io/Make_Dashboard/"
    }
}

enum class DashboardFile(val path: String, val key: String, val core: Boolean = true) {
    TODOS("data/todos.json", "todos"),
    PROJECTS("data/projects.json", "projects"),
    CALENDAR("data/calendar.json", "calendar"),

    /** PMR-Wiki summary (read only). Optional: a missing or unreadable file never shows as a widget error. */
    PMR("data/pmr-wiki.json", "pmr", core = false),
    ;

    companion object {
        /** Files whose state is reported to the user (sync status, errors, timestamps). */
        val coreFiles: List<DashboardFile> get() = entries.filter { it.core }
    }
}

data class CachedJsonFile(
    val rawJson: String?,
    val sha: String?,
    val lastSuccessAtMillis: Long?,
    val error: String?,
    val lastAttemptAtMillis: Long? = null,
    val notFoundAtMillis: Long? = null,
)

@Serializable
data class PendingTodoToggle(
    val operationId: String,
    val todoId: String,
    /** Legacy (v1.5) target value; kept so a pending record left by the old app still decodes. */
    val targetDoneOn: String?,
    val tappedDate: String,
    val expectedGeneration: Long,
    val createdAtMillis: Long,
    val title: String = "할 일",
    val undoRecordId: String? = null,
    val requiresExpectedMatch: Boolean = false,
    val expectedDoneOn: String? = null,
    /** The user intent: complete (true) or cancel the completion (false). Null = written by v1.5. */
    val makeDone: Boolean? = null,
) {
    /** What the overlay and the remote write apply: v1.5 records infer it from the old target. */
    val isCompletion: Boolean get() = makeDone ?: (undoRecordId == null && targetDoneOn != null)
}

@Serializable
enum class UndoKind { COMPLETION, DELETION }

@Serializable
data class CompletedTodoUndo(
    val recordId: String,
    val todoId: String,
    val title: String,
    val originalDoneOn: String?,
    val completedDoneOn: String,
    val completedDate: String,
    val generation: Long,
    val completedAtMillis: Long,
    val kind: UndoKind = UndoKind.COMPLETION,
    /** `history` before the completion (null = the key was absent). Only trusted when [historyCaptured]. */
    val originalHistory: List<String>? = null,
    val historyCaptured: Boolean = false,
    /** For [UndoKind.DELETION]: the deleted item JSON and the id of the item that followed it. */
    val deletedItemJson: String? = null,
    val afterId: String? = null,
)

@Serializable
enum class AppearanceColorMode { AUTOMATIC, LIGHT, DARK, OCEAN, FOREST, CUSTOM }

@Serializable
data class WidgetAppearance(
    val colorMode: AppearanceColorMode = AppearanceColorMode.AUTOMATIC,
    val accentPaletteId: String = "mint-blue-lavender",
    val backgroundHex: String = "#FFF8F2",
    val accentHex: String = "#006C4C",
    val fontScalePercent: Int = 100,
    val backgroundOpacityPercent: Int = 100,
    val contrastAssist: Boolean = true,
) {
    fun sanitized(): WidgetAppearance = copy(
        accentPaletteId = accentPaletteId.takeIf { id -> WidgetColorResolver.palettes.any { it.id == id } }
            ?: WidgetColorResolver.palettes.first().id,
        fontScalePercent = fontScalePercent.coerceIn(80, 150).let { (it / 10) * 10 },
        backgroundOpacityPercent = backgroundOpacityPercent.coerceIn(0, 100).let { (it / 5) * 5 },
        backgroundHex = backgroundHex.takeIf(HEX_COLOR::matches) ?: DEFAULT.backgroundHex,
        accentHex = accentHex.takeIf(HEX_COLOR::matches) ?: DEFAULT.accentHex,
    )

    companion object {
        val DEFAULT = WidgetAppearance()
        private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

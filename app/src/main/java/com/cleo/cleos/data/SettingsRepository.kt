package com.cleo.cleos.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cleo.cleos.glass.GlassPart
import com.cleo.cleos.glass.GlassTuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

enum class GlassMode { Auto, Light, Dark }

data class AppSettings(
    val apiBaseUrl: String = ApiPresets.DeepSeek.baseUrl,
    val apiModel: String = ApiPresets.DeepSeek.defaultModel,
    val aiName: String = "",
    val userName: String = "",
    /** Written by the user, sent as-is. Empty means no persona at all. */
    val persona: String = "",
    /** How many recent messages go to the model with each turn. */
    val historySize: Int = 40,
    /** File name inside ImageStore, or null for the built-in wallpaper. */
    val wallpaper: String? = null,
    val glassMode: GlassMode = GlassMode.Auto,
    /** Wallpaper analysis, cached so startup doesn't decode the image to pick colours. */
    val wallpaperDark: Boolean? = null,
    val wallpaperHue: Float? = null,
    val wallpaperChroma: Float? = null,
    val wallpaperTrough: Float? = null,
    val wallpaperPeak: Float? = null,
    /** Glass the user tuned in the glass lab and applied, per part. */
    val glassTuning: Map<GlassPart, GlassTuning> = emptyMap(),
)

data class ApiPreset(val name: String, val baseUrl: String, val defaultModel: String)

/** Starting points only; the settings screen can list what an endpoint really serves. */
object ApiPresets {
    val DeepSeek = ApiPreset("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash")
    val all = listOf(
        DeepSeek,
        ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        ApiPreset("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
        ApiPreset("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        ApiPreset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    )
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val apiBaseUrl = stringPreferencesKey("api_base_url")
        val apiModel = stringPreferencesKey("api_model")
        val aiName = stringPreferencesKey("ai_name")
        val userName = stringPreferencesKey("user_name")
        val persona = stringPreferencesKey("persona")
        val historySize = intPreferencesKey("history_size")
        val wallpaper = stringPreferencesKey("wallpaper")
        val glassMode = stringPreferencesKey("glass_mode")
        val wallpaperDark = stringPreferencesKey("wallpaper_dark")
        val wallpaperHue = floatPreferencesKey("wallpaper_hue")
        val wallpaperChroma = floatPreferencesKey("wallpaper_chroma")
        val wallpaperTrough = floatPreferencesKey("wallpaper_trough")
        val wallpaperPeak = floatPreferencesKey("wallpaper_peak")
        val glassTuning = stringPreferencesKey("glass_tuning")
        val currentConversation = stringPreferencesKey("current_conversation")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            apiBaseUrl = this[Keys.apiBaseUrl] ?: d.apiBaseUrl,
            apiModel = this[Keys.apiModel] ?: d.apiModel,
            aiName = this[Keys.aiName] ?: d.aiName,
            userName = this[Keys.userName] ?: d.userName,
            persona = this[Keys.persona] ?: d.persona,
            historySize = this[Keys.historySize] ?: d.historySize,
            wallpaper = this[Keys.wallpaper],
            glassMode = this[Keys.glassMode]?.let { runCatching { GlassMode.valueOf(it) }.getOrNull() } ?: d.glassMode,
            wallpaperDark = this[Keys.wallpaperDark]?.toBooleanStrictOrNull(),
            wallpaperHue = this[Keys.wallpaperHue],
            wallpaperChroma = this[Keys.wallpaperChroma],
            wallpaperTrough = this[Keys.wallpaperTrough],
            wallpaperPeak = this[Keys.wallpaperPeak],
            glassTuning = decodeTuning(this[Keys.glassTuning]),
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.apiBaseUrl] = next.apiBaseUrl
            prefs[Keys.apiModel] = next.apiModel
            prefs[Keys.aiName] = next.aiName
            prefs[Keys.userName] = next.userName
            prefs[Keys.persona] = next.persona
            prefs[Keys.historySize] = next.historySize
            prefs[Keys.glassMode] = next.glassMode.name
            if (next.wallpaper != null) prefs[Keys.wallpaper] = next.wallpaper else prefs.remove(Keys.wallpaper)
            if (next.wallpaperDark != null) prefs[Keys.wallpaperDark] = next.wallpaperDark.toString() else prefs.remove(Keys.wallpaperDark)
            if (next.wallpaperHue != null) prefs[Keys.wallpaperHue] = next.wallpaperHue else prefs.remove(Keys.wallpaperHue)
            if (next.wallpaperChroma != null) prefs[Keys.wallpaperChroma] = next.wallpaperChroma else prefs.remove(Keys.wallpaperChroma)
            if (next.wallpaperTrough != null) prefs[Keys.wallpaperTrough] = next.wallpaperTrough else prefs.remove(Keys.wallpaperTrough)
            if (next.wallpaperPeak != null) prefs[Keys.wallpaperPeak] = next.wallpaperPeak else prefs.remove(Keys.wallpaperPeak)
            if (next.glassTuning.isNotEmpty()) prefs[Keys.glassTuning] = encodeTuning(next.glassTuning) else prefs.remove(Keys.glassTuning)
        }
    }

    suspend fun setGlassTuning(part: GlassPart, tuning: GlassTuning?) = update {
        it.copy(glassTuning = if (tuning == null) it.glassTuning - part else it.glassTuning + (part to tuning))
    }

    val currentConversation: Flow<Long?> =
        context.settingsStore.data.map { it[Keys.currentConversation]?.toLongOrNull() }

    suspend fun setCurrentConversation(id: Long?) {
        context.settingsStore.edit {
            if (id == null) it.remove(Keys.currentConversation) else it[Keys.currentConversation] = id.toString()
        }
    }
}

private val tuningJson = Json { ignoreUnknownKeys = true }

// Stored by enum name, so a part removed in some later version is skipped, not an error.
internal fun encodeTuning(map: Map<GlassPart, GlassTuning>): String =
    tuningJson.encodeToString(map.mapKeys { it.key.name })

internal fun decodeTuning(raw: String?): Map<GlassPart, GlassTuning> {
    if (raw.isNullOrBlank()) return emptyMap()
    val byName = runCatching { tuningJson.decodeFromString<Map<String, GlassTuning>>(raw) }.getOrDefault(emptyMap())
    return byName.mapNotNull { (name, t) -> GlassPart.entries.firstOrNull { it.name == name }?.let { it to t } }.toMap()
}

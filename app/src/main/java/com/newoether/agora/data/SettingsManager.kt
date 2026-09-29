package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.util.DebugLog
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * DataStore-backed settings for the remote-only shell.
 *
 * The upstream provider/local-model/MCP/automation/sandbox/backup surface was
 * removed in S3 together with the systems that consumed it. What remains is
 * exactly what live code reads: theme/motion/haptics/language, chat rendering
 * toggles, and `customProviders` (still used to normalize provider ids in
 * snackbar text).
 */
class SettingsManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val appLanguage: Flow<String> = context.dataStore.data.map { it[APP_LANGUAGE] ?: "system" }

    /** Custom provider identities kept only for snackbar/display normalization. */
    val customProviders: Flow<List<CustomProviderConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[CUSTOM_PROVIDERS_JSON] ?: "[]"
        try {
            val decoded = json.decodeFromString<List<CustomProviderConfig>>(jsonStr)
            CustomProviderNamePolicy.sanitize(decoded).accepted
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode customProviders", e)
            emptyList()
        }
    }

    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "FOLLOW_DEVICE" }
    val amoledEnabled: Flow<Boolean> = context.dataStore.data.map { it[AMOLED_ENABLED] ?: false }
    val colorScheme: Flow<String> = context.dataStore.data.map {
        it[COLOR_SCHEME] ?: DEFAULT_COLOR_SCHEME
    }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map {
        it[DYNAMIC_COLOR] ?: DEFAULT_DYNAMIC_COLOR
    }
    val schemeStyle: Flow<String> = context.dataStore.data.map {
        it[SCHEME_STYLE] ?: DEFAULT_SCHEME_STYLE
    }
    val fontPreference: Flow<String> = context.dataStore.data.map { it[FONT_PREFERENCE] ?: "app_default" }
    val customFontPath: Flow<String> = context.dataStore.data.map { it[CUSTOM_FONT_PATH] ?: "" }
    val reduceMotion: Flow<Boolean> = context.dataStore.data.map { it[REDUCE_MOTION] ?: false }
    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { it[HAPTICS_ENABLED] ?: true }
    val parseInlineDollarMath: Flow<Boolean> =
        context.dataStore.data.map { it[PARSE_INLINE_DOLLAR_MATH] ?: false }
    val toolCallDisplayMode: Flow<String> =
        context.dataStore.data.map { ToolCallDisplayModes.normalize(it[TOOL_CALL_DISPLAY_MODE]) }
    val thinkingSegmentDisplayMode: Flow<String> = context.dataStore.data.map {
        ThinkingSegmentDisplayModes.normalize(it[THINKING_SEGMENT_DISPLAY_MODE])
    }
    val autoExpandActiveGroup: Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_EXPAND_ACTIVE_GROUP] ?: true }
}

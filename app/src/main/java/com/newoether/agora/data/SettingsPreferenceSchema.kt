package com.newoether.agora.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

// ── Live settings schema ──────────────────────────────────────
// Only keys actually read by the remote shell remain. Keys owned by deleted
// provider/automation/sandbox/backup systems were dropped in S3; their stored
// values are orphaned and may be cleaned up later.
internal val APP_LANGUAGE = stringPreferencesKey("app_language")

internal const val DEFAULT_COLOR_SCHEME = "FOREST"
internal const val DEFAULT_SCHEME_STYLE = "TONAL_SPOT"
internal const val DEFAULT_DYNAMIC_COLOR = false

internal val THEME_MODE = stringPreferencesKey("theme_mode")
internal val AMOLED_ENABLED = booleanPreferencesKey("amoled_enabled")
internal val COLOR_SCHEME = stringPreferencesKey("color_scheme")
internal val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
internal val SCHEME_STYLE = stringPreferencesKey("scheme_style")
internal val FONT_PREFERENCE = stringPreferencesKey("font_preference")
internal val CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")
internal val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
internal val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
internal val PARSE_INLINE_DOLLAR_MATH = booleanPreferencesKey("parse_inline_dollar_math")
internal val TOOL_CALL_DISPLAY_MODE = stringPreferencesKey("tool_call_display_mode")
internal val THINKING_SEGMENT_DISPLAY_MODE = stringPreferencesKey("thinking_segment_display_mode")
internal val AUTO_EXPAND_ACTIVE_GROUP = booleanPreferencesKey("auto_expand_active_group")

package com.winlator.cmod.ai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/**
 * Settings for the in-session AI assistant.
 *
 * Everything lives in the default SharedPreferences, which is the same place the rest of this
 * project already keeps secrets (see CoverArtManager's SteamGridDB key). That is plain text on
 * disk; the settings screen says so out loud instead of pretending otherwise.
 */
public final class AiConfig {
    public static final String PREF_ENABLED = "ai_assistant_enabled";
    public static final String PREF_BASE_URL = "ai_base_url";
    public static final String PREF_API_KEY = "ai_api_key";
    public static final String PREF_MODEL = "ai_model";
    public static final String PREF_AUTO_DIAGNOSE = "ai_auto_diagnose";
    public static final String PREF_ALLOW_COMMANDS = "ai_allow_commands";
    public static final String PREF_MAX_TOOL_ROUNDS = "ai_max_tool_rounds";
    public static final String PREF_BALL_X = "ai_ball_x";
    public static final String PREF_BALL_Y = "ai_ball_y";

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 6;

    private AiConfig() {}

    public static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    /** Base URL with any trailing slash removed, so we can append "/chat/completions" blindly. */
    public static String getBaseUrl(Context context) {
        String value = prefs(context).getString(PREF_BASE_URL, DEFAULT_BASE_URL);
        if (value == null || value.trim().isEmpty()) value = DEFAULT_BASE_URL;
        value = value.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        // Tolerate users pasting the full endpoint instead of the API root.
        if (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length());
        }
        return value;
    }

    public static void setBaseUrl(Context context, String baseUrl) {
        prefs(context).edit().putString(PREF_BASE_URL, baseUrl == null ? "" : baseUrl.trim()).apply();
    }

    public static String getApiKey(Context context) {
        String value = prefs(context).getString(PREF_API_KEY, "");
        return value == null ? "" : value.trim();
    }

    public static void setApiKey(Context context, String apiKey) {
        prefs(context).edit().putString(PREF_API_KEY, apiKey == null ? "" : apiKey.trim()).apply();
    }

    public static String getModel(Context context) {
        String value = prefs(context).getString(PREF_MODEL, DEFAULT_MODEL);
        if (value == null || value.trim().isEmpty()) value = DEFAULT_MODEL;
        return value.trim();
    }

    public static void setModel(Context context, String model) {
        prefs(context).edit().putString(PREF_MODEL, model == null ? "" : model.trim()).apply();
    }

    public static boolean isAutoDiagnose(Context context) {
        return prefs(context).getBoolean(PREF_AUTO_DIAGNOSE, true);
    }

    public static void setAutoDiagnose(Context context, boolean value) {
        prefs(context).edit().putBoolean(PREF_AUTO_DIAGNOSE, value).apply();
    }

    /** Read-only shell tools are on by default; this is the kill switch when someone wants chat only. */
    public static boolean isCommandAccessAllowed(Context context) {
        return prefs(context).getBoolean(PREF_ALLOW_COMMANDS, true);
    }

    public static void setCommandAccessAllowed(Context context, boolean value) {
        prefs(context).edit().putBoolean(PREF_ALLOW_COMMANDS, value).apply();
    }

    public static int getMaxToolRounds(Context context) {
        int value = prefs(context).getInt(PREF_MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);
        if (value < 1) value = 1;
        if (value > 12) value = 12;
        return value;
    }

    public static void setMaxToolRounds(Context context, int value) {
        if (value < 1) value = 1;
        if (value > 12) value = 12;
        prefs(context).edit().putInt(PREF_MAX_TOOL_ROUNDS, value).apply();
    }

    /** True when we have enough to actually talk to a provider. */
    public static boolean isConfigured(Context context) {
        return !getApiKey(context).isEmpty();
    }

    public static float getBallX(Context context, float fallback) {
        return prefs(context).getFloat(PREF_BALL_X, fallback);
    }

    public static float getBallY(Context context, float fallback) {
        return prefs(context).getFloat(PREF_BALL_Y, fallback);
    }

    public static void setBallPosition(Context context, float x, float y) {
        prefs(context).edit().putFloat(PREF_BALL_X, x).putFloat(PREF_BALL_Y, y).apply();
    }
}

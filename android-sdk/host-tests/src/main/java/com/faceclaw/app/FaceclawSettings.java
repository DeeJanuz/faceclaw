package com.faceclaw.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings fixture. The host's store is Kotlin (FaceclawSettings.kt) and
 * depends on the shared module's platform layer; the boundary tests only need
 * its string and boolean persistence.
 */
public final class FaceclawSettings {
    private static FaceclawSettings instance;
    private final SharedPreferences prefs;

    private FaceclawSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("faceclaw_settings", Context.MODE_PRIVATE);
    }

    public static synchronized FaceclawSettings getInstance(Context context) {
        if (instance == null) instance = new FaceclawSettings(context);
        return instance;
    }

    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    public void setString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public void setBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }
}

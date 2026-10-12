package com.animeshahilya.espeakng.test;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import com.animeshahilya.espeakng.EspeakApp;

import org.junit.rules.ExternalResource;

import java.util.Map;
import java.util.Set;

/**
 * A {@code @ClassRule} for tests that clear the app's settings: puts the
 * phone's own settings (natural voices, per-language choices, rate...) back
 * afterwards, so running the suite on a daily-use device changes nothing.
 * Settings named in the constructor are removed for the class's duration,
 * e.g. the "Supported languages" filter for tests that need every language.
 *
 * The saved copy is also written to its own preferences file and deleted
 * only after the restore: a run killed in between (adb dropped, process
 * crashed) leaves it behind, and the next run restores from it instead of
 * saving the half-changed settings.
 */
public final class KeepUserSettings extends ExternalResource {
    private static final String COPY = "keep_user_settings";

    private final String[] mClear;
    private Map<String, ?> mSaved;

    public KeepUserSettings(String... clear) {
        mClear = clear;
    }

    private static Context context() {
        return EspeakApp.requireStorageContext(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    private static SharedPreferences prefs() {
        return EspeakApp.prefs(context());
    }

    private static SharedPreferences copy() {
        return context().getSharedPreferences(COPY, Context.MODE_PRIVATE);
    }

    @Override
    protected void before() {
        final Map<String, ?> leftover = copy().getAll();
        if (leftover.isEmpty()) {
            mSaved = prefs().getAll();
            putAll(copy().edit(), mSaved).commit();
        } else {
            mSaved = leftover;
        }
        final SharedPreferences.Editor e = prefs().edit();
        for (String key : mClear) {
            e.remove(key);
        }
        e.commit();
    }

    @Override
    protected void after() {
        putAll(prefs().edit().clear(), mSaved).commit();
        copy().edit().clear().commit();
        context().deleteSharedPreferences(COPY);
    }

    @SuppressWarnings("unchecked")
    private static SharedPreferences.Editor putAll(SharedPreferences.Editor e, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            final String k = entry.getKey();
            final Object v = entry.getValue();
            if (v instanceof String) e.putString(k, (String) v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v);
            else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
        }
        return e;
    }
}

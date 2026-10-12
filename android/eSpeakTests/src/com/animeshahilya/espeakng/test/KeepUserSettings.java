package com.animeshahilya.espeakng.test;

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
 */
public final class KeepUserSettings extends ExternalResource {
    private Map<String, ?> mSaved;

    private static SharedPreferences prefs() {
        return EspeakApp.prefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Override
    protected void before() {
        mSaved = prefs().getAll();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void after() {
        final SharedPreferences.Editor e = prefs().edit().clear();
        for (Map.Entry<String, ?> entry : mSaved.entrySet()) {
            final String k = entry.getKey();
            final Object v = entry.getValue();
            if (v instanceof String) e.putString(k, (String) v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v);
            else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
        }
        e.commit();
    }
}

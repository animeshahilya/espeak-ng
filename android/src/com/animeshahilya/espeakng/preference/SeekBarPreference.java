/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.animeshahilya.espeakng.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.DialogPreference;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.VoiceSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * A dialog preference that edits one or more of eSpeak's numeric voice
 * parameters. One section -- name, value, slider, reset button -- is added to
 * the dialog per parameter.
 *
 * A phone hosts all four parameters in a single dialog. Wear gives each
 * parameter its own preference, and therefore its own dialog, because the
 * rotating crown only ever delivers its scroll events to the focused view and
 * a watch has no D-pad to move focus with. A second slider in the same dialog
 * would be unreachable by the crown, so the split is a requirement of the
 * input model rather than a matter of screen size. See TtsSettingsActivity.
 *
 * Dialog UI lives in {@link SeekBarDialogFragment}; this class owns the
 * parameter model, persistence, and summary. Fields are package-visible for
 * the fragment in the same package.
 */
public class SeekBarPreference extends DialogPreference
{
    /**
     * One editable parameter: where its value is stored, how it is presented,
     * and -- while the dialog is open -- the views bound to it.
     */
    public static class Parameter
    {
        final String key;
        final String title;
        final int min;
        final int max;
        final int defaultValue;
        final String formatter;

        /** The value shown by the slider right now. */
        int current;
        /** The value to fall back to if the dialog is cancelled. */
        int saved;

        /** Only the speech rate carries the rate-boost checkbox. */
        boolean hasRateBoost;
        boolean boost;
        boolean savedBoost;
        /** How far boost multiplies the rate; see VoiceSettings#getRateBoostMultiplier(). */
        int boostMultiplier = VoiceSettings.RATE_BOOST_MULTIPLIER;
        int valueMultiplier = 1;

        SeekBar mSeekBar;
        TextView mValueText;
        CheckBox mRateBoost;

        /**
         * True between onStartTrackingTouch and onStopTrackingTouch. A drag
         * emits a progress change per pixel, so persisting is deferred to the
         * end of the gesture; see {@link SeekBarPreference#persist}.
         */
        boolean dragging;

        public Parameter(String key, String title, int min, int max, int defaultValue, int current, String formatter)
        {
            this.key = key;
            this.title = title;
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.current = current;
            this.saved = current;
            this.formatter = formatter;
        }

        public void setValueMultiplier(int multiplier)
        {
            this.valueMultiplier = multiplier;
        }

        public int getValueMultiplier()
        {
            return this.valueMultiplier;
        }

        public void enableRateBoost(boolean enabled, int multiplier)
        {
            hasRateBoost = true;
            boost = enabled;
            savedBoost = enabled;
            boostMultiplier = multiplier;
        }
    }

    final List<Parameter> mParameters = new ArrayList<Parameter>();

    /**
     * Whether the rate-boost checkbox shows inside the rate dialog. Phones
     * have a standalone Rate boost preference, so the embedded toggle is
     * hidden there to leave exactly one control per setting; watches keep it
     * as their only control. The stored boost value still drives the
     * displayed (multiplied) value either way.
     */
    private boolean mRateBoostToggleVisible = true;

    public void setRateBoostToggleVisible(boolean visible) {
        mRateBoostToggleVisible = visible;
    }

    boolean isRateBoostToggleVisible() {
        return mRateBoostToggleVisible;
    }

    public SeekBarPreference(Context context, AttributeSet attrs, int defStyle)
    {
        super(context, attrs, defStyle);
        setLayoutResource(R.layout.information_view);
    }

    public SeekBarPreference(Context context, AttributeSet attrs)
    {
        this(context, attrs, 0);
    }

    public SeekBarPreference(Context context)
    {
        this(context, null);
    }

    public void addParameter(Parameter parameter)
    {
        mParameters.add(parameter);
    }

    /**
     * The value to show for a parameter, which is not the stored value when
     * the rate boost multiplies it.
     */
    int getDisplayValue(Parameter parameter)
    {
        int value = parameter.current * parameter.valueMultiplier;
        if (parameter.hasRateBoost && parameter.boost) {
            value = value * parameter.boostMultiplier;
            int boostedMax = parameter.max * parameter.boostMultiplier * parameter.valueMultiplier;
            if (value > boostedMax) {
                value = boostedMax;
            }
        }
        return value;
    }

    void updateValueText(Parameter parameter)
    {
        String text = String.format(parameter.formatter, Integer.toString(getDisplayValue(parameter)));
        String label = (mParameters.size() > 1) ? (parameter.title + ", " + text) : text;
        parameter.mValueText.setText(label);
        parameter.mSeekBar.setContentDescription(parameter.title + ", " + text);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            parameter.mSeekBar.setStateDescription(text);
        }
    }

    /**
     * Writes one parameter through to the settings eSpeak actually reads, so a
     * change is audible immediately rather than at the next dialog dismissal.
     *
     * apply() rather than commit(): the in-memory value is updated before this
     * returns, which is all TtsService needs since it reads the same
     * SharedPreferences instance in this process, and the disk write stays off
     * the main thread. Rotary detents and TalkBack steps arrive one after
     * another, so a synchronous write here would be felt.
     */
    void persist(Parameter parameter)
    {
        if (!shouldPersist()) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences();
        if (prefs == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(parameter.key, Integer.toString(parameter.current));
        if (parameter.hasRateBoost && mRateBoostToggleVisible) {
            // Only write PREF_RATE_BOOST when this dialog's own checkbox is the
            // control the user can see and touch (Wear). On phones the checkbox
            // is hidden - parameter.boost there is just a snapshot taken when
            // this dialog was built, and the real value lives in the standalone
            // Rate boost preference. Without this guard, opening this dialog and
            // hitting Cancel would silently overwrite that preference back to
            // whatever it was when the screen was constructed, discarding any
            // change made via the standalone checkbox in the meantime.
            editor.putBoolean(VoiceSettings.PREF_RATE_BOOST, parameter.boost);
        }
        editor.apply();
    }

    /**
     * The preference row's summary: the value on its own when the preference
     * owns a single parameter, otherwise each parameter named and listed.
     */
    public String buildSummary()
    {
        StringBuilder summary = new StringBuilder();
        for (Parameter parameter : mParameters) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            if (mParameters.size() > 1) {
                summary.append(parameter.title).append(": ");
            }
            summary.append(String.format(parameter.formatter, Integer.toString(getDisplayValue(parameter))));
        }
        return summary.toString();
    }
}

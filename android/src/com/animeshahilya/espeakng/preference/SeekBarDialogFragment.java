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

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceDialogFragmentCompat;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.preference.SeekBarPreference.Parameter;

/**
 * Dialog for {@link SeekBarPreference}: one name/value/slider/reset section
 * per parameter. Wiring moved verbatim from the framework
 * {@code onCreateDialogView}/{@code onBindDialogView}/{@code onClick}/
 * {@code onDismiss} overrides, which have no AndroidX equivalents; the
 * parameter model, persistence, and summary stay on the preference.
 */
public class SeekBarDialogFragment extends PreferenceDialogFragmentCompat {
    public static SeekBarDialogFragment newInstance(String key) {
        SeekBarDialogFragment fragment = new SeekBarDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    private SeekBarPreference getSeekBarPreference() {
        return (SeekBarPreference) getPreference();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (!(getPreference() instanceof SeekBarPreference)) {
            // Restored after the screen tree was rebuilt under it (rotation
            // or process restore): getPreference() no longer resolves, and
            // every getter below would NPE/ClassCastException. Drop this
            // restored instance instead of crashing the settings screen.
            dismissAllowingStateLoss();
            return new AlertDialog.Builder(getContext()).create();
        }
        SeekBarPreference preference = getSeekBarPreference();
        View root = LayoutInflater.from(getContext()).inflate(R.layout.seekbar_preference, null);
        ViewGroup container = (ViewGroup) root.findViewById(R.id.parameters);
        LayoutInflater inflater = LayoutInflater.from(getContext());

        for (final Parameter parameter : preference.mParameters) {
            final View section = inflater.inflate(R.layout.seekbar_preference_section, container, false);
            bindSection(preference, parameter, section);
            container.addView(section);
        }

        // Null listeners: the OK path is handled in onStart() and every
        // dismissal funnels through onDismiss(), mirroring the old overrides.
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(root)
                .setTitle(preference.getDialogTitle())
                .setIcon(preference.getDialogIcon());
        AlertDialog dialog = builder.create();

        for (final Parameter parameter : preference.mParameters) {
            // Read before touching the SeekBar: setMax() notifies the progress
            // listener, which writes the slider's position -- still zero at
            // that point -- back over parameter.current.
            final int value = parameter.current;

            parameter.saved = value;
            parameter.savedBoost = parameter.boost;

            // Neither call persists anything, because neither reports itself
            // as a change made by the user. That is what keeps opening the
            // dialog from publishing a half-initialised set of values; an
            // earlier revision announced rate = 80 WPM and volume = 0 here.
            parameter.mSeekBar.setMax(parameter.max - parameter.min);
            parameter.mSeekBar.setProgress(value - parameter.min);
            parameter.current = value;

            preference.updateValueText(parameter);
        }

        // Only meaningful for the one-parameter (Wear) dialog, where it hands
        // the crown to the single slider. With four sliders there is nothing
        // sensible to focus, and taking focus would just misdirect the crown.
        if (preference.mParameters.size() == 1) {
            preference.mParameters.get(0).mSeekBar.requestFocus();
        }

        return dialog;
    }

    private void bindSection(final SeekBarPreference preference, final Parameter parameter, View section)
    {
        parameter.mSeekBar = (SeekBar) section.findViewById(R.id.seekBar);
        parameter.mValueText = (TextView) section.findViewById(R.id.valueText);
        parameter.mRateBoost = (CheckBox) section.findViewById(R.id.rateBoost);

        final Button reset = (Button) section.findViewById(R.id.resetToDefault);
        // Every section carries an identically labelled button, so name the
        // parameter for anyone who reaches it with a screen reader.
        reset.setContentDescription(parameter.title + ", " + reset.getText());
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            // announceForAccessibility was deprecated in Android 16 (use live regions
            // for dynamic content instead). These one-shot polite announcements for
            // explicit user actions are the non-disruptive case; the live-region
            // alternative would change the announced content and needs TalkBack
            // verification before it can replace this.
            @SuppressWarnings("deprecation")
            public void onClick(View v)
            {
                parameter.mSeekBar.setProgress(parameter.defaultValue - parameter.min);
                // setProgress() reports fromUser == false, so persist here
                // rather than leaving it to the progress listener.
                preference.persist(parameter);
                String text = String.format(parameter.formatter,
                        Integer.toString(preference.getDisplayValue(parameter)));
                v.announceForAccessibility(parameter.title + ": " + text);
            }
        });

        parameter.mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                parameter.current = progress + parameter.min;
                preference.updateValueText(parameter);

                // TalkBack gestures and D-pad presses arrive here with
                // fromUser set and no surrounding touch gesture, and they are
                // the only notification we get -- onStopTrackingTouch never
                // fires for them. Persisting now is what makes the change
                // audible on the next thing eSpeak speaks.
                if (fromUser && !parameter.dragging) {
                    preference.persist(parameter);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                parameter.dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                parameter.dragging = false;
                preference.persist(parameter);
            }
        });

        if (parameter.hasRateBoost && preference.isRateBoostToggleVisible()) {
            // Checked before the listener is attached, so restoring the stored
            // state does not count as toggling it and write it straight back.
            parameter.mRateBoost.setChecked(parameter.boost);
            parameter.mRateBoost.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
                {
                    parameter.boost = isChecked;
                    preference.updateValueText(parameter);
                    preference.persist(parameter);
                }
            });
        } else {
            parameter.mRateBoost.setVisibility(View.GONE);
        }

        attachRotaryEncoder(preference, parameter);
    }

    /**
     * Wire the Wear OS rotating crown to a slider. Rotary input is delivered
     * as ACTION_SCROLL events on SOURCE_ROTARY_ENCODER and only reaches the
     * focused view, so the slider has to be focusable and, in the
     * one-parameter dialog, take focus when it opens. The listener is a no-op
     * on phones (no rotary device ever fires it), so this code path stays
     * harmless on non-watch builds.
     */
    private void attachRotaryEncoder(final SeekBarPreference preference, final Parameter parameter)
    {
        final SeekBar seekBar = parameter.mSeekBar;
        final int range = parameter.max - parameter.min;
        final int step = Math.max(1, range / 40);

        seekBar.setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent ev)
            {
                if (ev.getAction() != MotionEvent.ACTION_SCROLL
                        || !ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    return false;
                }
                float scroll = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
                if (scroll == 0f) {
                    return false;
                }
                int delta = (scroll > 0f ? -1 : 1) * step;
                int updated = Math.max(0, Math.min(range, seekBar.getProgress() + delta));
                if (updated != seekBar.getProgress()) {
                    seekBar.setProgress(updated);
                    // As with the reset button: setProgress() is not a user
                    // change as far as the listener is concerned, but this is.
                    preference.persist(parameter);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                return true;
            }
        });
        seekBar.setFocusable(true);
        seekBar.setFocusableInTouchMode(true);
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;

        // OK only updates the last-saved values; the actual persist happens
        // in onDismiss for every dismissal path (see below).
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> onDialogClosed(true));
        }

        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null) {
            negative.setOnClickListener(v -> dialog.cancel());
        }
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (!positiveResult) {
            return;
        }
        // Update the last saved values so these will be persisted when the
        // dialog is dismissed below.
        SeekBarPreference preference = getSeekBarPreference();
        for (Parameter parameter : preference.mParameters) {
            parameter.saved = parameter.current;
            parameter.savedBoost = parameter.boost;
        }
        dismiss();
    }

    @Override
    public void onDismiss(android.content.DialogInterface dialog) {
        // There are 3 ways to dismiss a dialog:
        //   1.  Pressing the OK (positive) button.
        //   2.  Pressing the Cancel (negative) button.
        //   3.  Pressing the Back button.
        //
        // For [1], the new values need to be persisted. For [2] and [3], the
        // old values need to be persisted (so the last saved values are
        // restored).
        //
        // 1.  If the user presses the OK button, the last saved values are
        //     updated to be the new values (see the onStart handler above).
        //
        // 2.  In all cases, the last saved values are persisted when the dialog
        //     is closed here.
        SeekBarPreference preference = getSeekBarPreference();
        for (Parameter parameter : preference.mParameters) {
            parameter.current = parameter.saved;
            parameter.boost = parameter.savedBoost;
            preference.persist(parameter);
        }

        String summary = preference.buildSummary();
        preference.callChangeListener(summary);
        preference.setSummary(summary);

        // Release the dialog's views; the preference would otherwise keep
        // them (and their Activity context) alive until the tree is rebuilt.
        // A reopened dialog rebinds them in onCreateDialog().
        for (Parameter parameter : preference.mParameters) {
            parameter.mSeekBar = null;
            parameter.mValueText = null;
            parameter.mRateBoost = null;
        }

        super.onDismiss(dialog);
    }
}

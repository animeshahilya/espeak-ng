package com.animeshahilya.espeakng.preference;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceDialogFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Shared base for the app's preference dialogs. OK is routed to
 * {@link #onDialogClosed(boolean)} (which must dismiss itself) and Cancel to
 * {@code cancel()}, and restored instances whose preference no longer
 * resolves are dropped instead of crashing the settings screen.
 */
abstract class ButtonDialogFragment extends PreferenceDialogFragmentCompat {
    static <T extends ButtonDialogFragment> T withKey(T fragment, String key) {
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Restored after the screen tree was rebuilt under it (rotation or process
     * restore): getPreference() no longer resolves to the expected type.
     * Returns a throwaway dialog after dismissing this instance, or null when
     * the preference is fine and the caller should build the real dialog.
     */
    protected final Dialog staleDialogUnless(Class<?> preferenceType) {
        if (preferenceType.isInstance(getPreference())) {
            return null;
        }
        dismissAllowingStateLoss();
        return new MaterialAlertDialogBuilder(getContext()).create();
    }

    /**
     * OK/Cancel dialog around {@code content}. The buttons get null listeners
     * here; {@link #onStart()} wires the real ones so OK can keep the dialog
     * open (an AlertDialog button listener auto-dismisses).
     */
    protected final AlertDialog buildDialog(View content) {
        return new MaterialAlertDialogBuilder(getContext())
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(content)
                .setTitle(getPreference().getDialogTitle())
                .setIcon(getPreference().getDialogIcon())
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> onDialogClosed(true));
        }
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null) {
            negative.setOnClickListener(v -> dialog.cancel());
        }
    }
}

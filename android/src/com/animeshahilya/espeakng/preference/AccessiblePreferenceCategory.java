package com.animeshahilya.espeakng.preference;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceViewHolder;

/**
 * A PreferenceCategory that marks itself and its title view as accessibility headings
 * (API 28+) so TalkBack users can jump between preference sections using heading navigation.
 */
public class AccessiblePreferenceCategory extends PreferenceCategory {

    public AccessiblePreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public AccessiblePreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AccessiblePreferenceCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AccessiblePreferenceCategory(Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            View itemView = holder.itemView;
            itemView.setAccessibilityHeading(true);
            View titleView = itemView.findViewById(android.R.id.title);
            if (titleView != null) {
                titleView.setAccessibilityHeading(true);
            }
        }
    }
}

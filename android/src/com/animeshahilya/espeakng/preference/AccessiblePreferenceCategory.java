package com.animeshahilya.espeakng.preference;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceViewHolder;

import com.animeshahilya.espeakng.R;

/**
 * A PreferenceCategory that marks itself and its title view as accessibility headings
 * (API 28+) so TalkBack users can jump between preference sections using heading navigation.
 * Uses a Material 3 styled layout with proper visual hierarchy.
 */
public class AccessiblePreferenceCategory extends PreferenceCategory {

    public AccessiblePreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setLayoutResource(R.layout.preference_category_material);
    }

    public AccessiblePreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutResource(R.layout.preference_category_material);
    }

    public AccessiblePreferenceCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_category_material);
    }

    public AccessiblePreferenceCategory(Context context) {
        super(context);
        setLayoutResource(R.layout.preference_category_material);
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
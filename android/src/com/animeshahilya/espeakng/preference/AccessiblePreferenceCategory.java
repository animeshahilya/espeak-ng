package com.animeshahilya.espeakng.preference;

import android.content.Context;
import android.os.Build;
import android.preference.PreferenceCategory;
import android.util.AttributeSet;
import android.view.View;

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
    protected void onBindView(View view) {
        super.onBindView(view);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setAccessibilityHeading(true);
            View titleView = view.findViewById(android.R.id.title);
            if (titleView != null) {
                titleView.setAccessibilityHeading(true);
            }
        }
    }
}

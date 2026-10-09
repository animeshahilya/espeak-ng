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
        View itemView = holder.itemView;
        CharSequence title = getTitle();
        boolean hasTitle = title != null && title.length() > 0;
        CharSequence summary = getSummary();
        boolean hasSummary = summary != null && summary.length() > 0;

        View titleView = itemView.findViewById(android.R.id.title);
        if (titleView != null) {
            titleView.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
        }

        View summaryView = itemView.findViewById(android.R.id.summary);
        if (summaryView instanceof android.widget.TextView) {
            if (hasSummary) {
                ((android.widget.TextView) summaryView).setText(summary);
                summaryView.setVisibility(View.VISIBLE);
            } else {
                summaryView.setVisibility(View.GONE);
            }
        }

        androidx.core.view.ViewCompat.setAccessibilityHeading(itemView, hasTitle);
        if (titleView != null) {
            androidx.core.view.ViewCompat.setAccessibilityHeading(titleView, hasTitle);
        }

        if (!hasTitle && !hasSummary) {
            itemView.setPaddingRelative(0, 0, 0, 0);
            itemView.setMinimumHeight(0);
        } else {
            int pStart = itemView.getResources().getDimensionPixelSize(R.dimen.category_header_padding_start);
            int pEnd = itemView.getResources().getDimensionPixelSize(R.dimen.category_header_padding_end);
            int pTop = itemView.getResources().getDimensionPixelSize(R.dimen.category_header_padding_top);
            int pBottom = itemView.getResources().getDimensionPixelSize(R.dimen.category_header_padding_bottom);
            itemView.setPaddingRelative(pStart, pTop, pEnd, pBottom);
        }
    }
}
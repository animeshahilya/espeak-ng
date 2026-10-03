package com.animeshahilya.espeakng.preference;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.animeshahilya.espeakng.R;

/**
 * Native iOS-styled Inset Grouped layout and styling for PreferenceFragmentCompat.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Floating uppercase category headers.</li>
 *   <li>Inset cards with 16dp horizontal margins and 16dp rounded corners.</li>
 *   <li>Subtle inset dividers between items within the same card.</li>
 *   <li>Touch ripples clipped to the rounded corners.</li>
 *   <li>TalkBack friendly navigation and touch targets (>= 52dp).</li>
 * </ul>
 */
public class InsetGroupedItemDecoration extends RecyclerView.ItemDecoration {

    private final int mMarginHorizontal;
    private final int mCardCornerRadius;
    private final int mGroupBottomSpacing;
    private final int mDividerIndentWithIcon;
    private final int mDividerIndentWithoutIcon;
    private final int mMinItemHeight;

    private final int mCardBgColor;
    private final int mRippleColor;
    private final Paint mDividerPaint;

    public InsetGroupedItemDecoration(Context context) {
        mMarginHorizontal = dpToPx(context, 16);
        mCardCornerRadius = dpToPx(context, 16);
        mGroupBottomSpacing = dpToPx(context, 14);
        mDividerIndentWithIcon = dpToPx(context, 56);
        mDividerIndentWithoutIcon = dpToPx(context, 16);
        mMinItemHeight = dpToPx(context, 52);

        mCardBgColor = ContextCompat.getColor(context, R.color.ios_card_background);
        mRippleColor = ContextCompat.getColor(context, R.color.ios_ripple);

        mDividerPaint = new Paint();
        mDividerPaint.setColor(ContextCompat.getColor(context, R.color.ios_divider));
        mDividerPaint.setStrokeWidth(Math.max(1f, dpToPx(context, 1)));
        mDividerPaint.setStyle(Paint.Style.STROKE);
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos == RecyclerView.NO_POSITION) {
            super.getItemOffsets(outRect, view, parent, state);
            return;
        }

        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) {
            super.getItemOffsets(outRect, view, parent, state);
            return;
        }

        PreferenceGroupAdapter pga = (PreferenceGroupAdapter) adapter;
        if (pos >= pga.getItemCount()) {
            super.getItemOffsets(outRect, view, parent, state);
            return;
        }
        Preference pref = pga.getItem(pos);
        if (pref == null || pref instanceof PreferenceCategory) {
            // Category headers span full width with their own internal padding
            outRect.set(0, 0, 0, 0);
            return;
        }

        // Preference items inside the card get 16dp horizontal insets
        outRect.left = mMarginHorizontal;
        outRect.right = mMarginHorizontal;
        outRect.top = 0;

        boolean isLastInGroup = (pos == pga.getItemCount() - 1)
                || (pos + 1 < pga.getItemCount() && pga.getItem(pos + 1) instanceof PreferenceCategory);
        outRect.bottom = isLastInGroup ? mGroupBottomSpacing : 0;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) {
            return;
        }

        PreferenceGroupAdapter pga = (PreferenceGroupAdapter) adapter;
        int childCount = parent.getChildCount();

        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION || pos >= pga.getItemCount()) continue;

            Preference pref = pga.getItem(pos);
            if (pref == null || pref instanceof PreferenceCategory) continue;

            boolean isFirst = (pos == 0) || (pos > 0 && pga.getItem(pos - 1) instanceof PreferenceCategory);
            boolean isLast = (pos == pga.getItemCount() - 1)
                    || (pos + 1 < pga.getItemCount() && pga.getItem(pos + 1) instanceof PreferenceCategory);

            applyCardStyle(child, isFirst, isLast, pref instanceof PreferenceScreen);
        }
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) {
            return;
        }

        PreferenceGroupAdapter pga = (PreferenceGroupAdapter) adapter;
        int childCount = parent.getChildCount();

        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION || pos >= pga.getItemCount()) continue;

            Preference pref = pga.getItem(pos);
            if (pref == null || pref instanceof PreferenceCategory) continue;

            boolean isLast = (pos == pga.getItemCount() - 1)
                    || (pos + 1 < pga.getItemCount() && pga.getItem(pos + 1) instanceof PreferenceCategory);

            if (!isLast) {
                View iconView = child.findViewById(android.R.id.icon);
                boolean hasIcon = iconView != null && iconView.getVisibility() == View.VISIBLE;
                boolean isRtl = child.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                float startX;
                float endX;
                if (isRtl) {
                    startX = child.getLeft();
                    endX = child.getRight() - (hasIcon ? mDividerIndentWithIcon : mDividerIndentWithoutIcon);
                } else {
                    startX = child.getLeft() + (hasIcon ? mDividerIndentWithIcon : mDividerIndentWithoutIcon);
                    endX = child.getRight();
                }
                float y = child.getBottom();
                c.drawLine(startX, y, endX, y, mDividerPaint);
            }
        }
    }

    private void applyCardStyle(View view, boolean isFirst, boolean isLast, boolean isScreen) {
        // Ensure touch target accessibility height
        if (view.getMinimumHeight() < mMinItemHeight) {
            view.setMinimumHeight(mMinItemHeight);
        }

        int shapeKey = (isFirst ? 1 : 0) | (isLast ? 2 : 0) | (isScreen ? 4 : 0);
        Object currentTag = view.getTag(R.id.tag_card_shape);
        if (currentTag instanceof Integer && ((Integer) currentTag) == shapeKey) {
            return;
        }

        float r = mCardCornerRadius;
        float[] radii;
        if (isFirst && isLast) {
            radii = new float[] {r, r, r, r, r, r, r, r};
        } else if (isFirst) {
            radii = new float[] {r, r, r, r, 0, 0, 0, 0};
        } else if (isLast) {
            radii = new float[] {0, 0, 0, 0, r, r, r, r};
        } else {
            radii = new float[] {0, 0, 0, 0, 0, 0, 0, 0};
        }

        GradientDrawable contentBg = new GradientDrawable();
        contentBg.setShape(GradientDrawable.RECTANGLE);
        contentBg.setColor(mCardBgColor);
        contentBg.setCornerRadii(radii);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadii(radii);

        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(mRippleColor), contentBg, mask);
        view.setBackground(ripple);
        view.setTag(R.id.tag_card_shape, shapeKey);

        if (isScreen) {
            ViewCompat.setAccessibilityDelegate(view, new AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            AccessibilityNodeInfoCompat.ACTION_CLICK,
                            host.getContext().getString(R.string.accessibility_action_open)));
                }
            });
        } else {
            ViewCompat.setAccessibilityDelegate(view, null);
        }
    }
}

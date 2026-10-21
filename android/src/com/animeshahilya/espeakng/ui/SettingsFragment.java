/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
 * Copyright (C) 2011 The Android Open Source Project
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

package com.animeshahilya.espeakng.ui;

import com.animeshahilya.espeakng.TtsSettingsActivity;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.piper.PiperSettings;
import com.animeshahilya.espeakng.ui.preference.SeekBarDialogFragment;
import com.animeshahilya.espeakng.ui.preference.SeekBarPreference;
import com.animeshahilya.espeakng.ui.preference.SpeakPunctuationDialogFragment;
import com.animeshahilya.espeakng.ui.preference.SpeakPunctuationPreference;
import com.animeshahilya.espeakng.ui.preference.SupportedLanguagesDialogFragment;
import com.animeshahilya.espeakng.ui.preference.SupportedLanguagesPreference;
import com.animeshahilya.espeakng.ui.preference.VoiceVariantDialogFragment;
import com.animeshahilya.espeakng.ui.preference.VoiceVariantPreference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SettingsFragment extends PreferenceFragmentCompat {
    private static final String DIALOG_FRAGMENT_TAG =
            "androidx.preference.PreferenceFragment.DIALOG";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // The fragment has its own PreferenceManager, separate from the
        // activity's. Everything on this screen persists through it, so it
        // must use the device-protected file that TtsService reads. A
        // Preference left on the default credential-encrypted storage has
        // no effect on speech, and its stray file is what #2536 copied
        // over the real settings on every screen reader restart.
        getPreferenceManager().setStorageDeviceProtected();
        // A loading row until the engine has loaded; see
        // SettingsBuilder.createPreferences(). An empty screen left TalkBack users on a
        // blank page for seconds with no indication anything was coming.
        final PreferenceScreen loading = getPreferenceManager().createPreferenceScreen(requireContext());
        final Preference loadingRow = new Preference(requireContext());
        loadingRow.setTitle(R.string.settings_loading);
        loadingRow.setSelectable(false);
        loadingRow.setEnabled(false);
        loading.addPreference(loadingRow);
        setPreferenceScreen(loading);
        SettingsBuilder.createPreferences(this);
    }

    // setTargetFragment is deprecated in general, but it is the documented
    // mechanism here: PreferenceDialogFragmentCompat.onCreate() requires a
    // target implementing DialogPreference.TargetFragment (which
    // PreferenceFragmentCompat does) and resolves the preference through
    // it - without this every custom dialog crashes on open.
    // Framework auto-showed nested PreferenceScreens in dialogs. AndroidX
    // does neither: base onPreferenceTreeClick only handles the deprecated
    // android:fragment path, and onNavigateToScreen merely delegates to an
    // OnPreferenceStartScreenCallback nobody implements (verified against
    // the 1.2.1 source - calling it is a silent no-op). So this fragment
    // re-roots itself instead: same manager, no tree rebuild. Back pops
    // through the activity's OnBackPressedCallback (registered in
    // TtsSettingsActivity.onCreate), which calls popToParent() before
    // finishing. Rotation does not save this stack (no onSaveInstanceState
    // override), dropping back to the root screen - matching the old
    // framework dialogs, which never survived rotation either.
    private final Deque<PreferenceScreen> mScreenStack = new ArrayDeque<>();

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference != null && preference.getKey() != null
                && preference.getKey().startsWith(SEARCH_RESULT_PREFIX)) {
            navigateToSearchResult(preference.getKey().substring(SEARCH_RESULT_PREFIX.length()));
            return true;
        }
        if (preference instanceof PreferenceScreen) {
            PreferenceScreen current = getPreferenceScreen();
            if (current != null) {
                mScreenStack.push(current);
            }
            setPreferenceScreen((PreferenceScreen) preference);
            updateTitle();
            focusFirstRow();
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    /**
     * Returns to the parent screen if navigated into a sub-screen.
     *
     * @return true if a sub-screen was showing and the parent restored.
     */
    public boolean popToParent() {
        PreferenceScreen parent = mScreenStack.poll();
        if (parent == null) {
            return false;
        }
        PiperSettings.onShown(parent); // a natural-voice page refreshes what changed below it
        setPreferenceScreen(parent);
        updateTitle();
        focusFirstRow();
        return true;
    }

    /**
     * Opens a page built in code (the natural-voice download and voice
     * pages) one level below the current one, so Back returns here.
     */
    public void pushScreen(PreferenceScreen screen) {
        SettingsBuilder.decoratePreferenceScreens(screen);
        final PreferenceScreen current = getPreferenceScreen();
        if (current != null) {
            mScreenStack.push(current);
        }
        setPreferenceScreen(screen);
        updateTitle();
        focusFirstRow();
    }

    /**
     * Moves TalkBack focus to the first row after a screen change.
     * Re-rooting swaps the whole list silently: sighted users see a new
     * list from the top, but a screen reader's focus stays where the old
     * list's position was -- potentially mid-list or on nothing. The
     * announcement alone (see updateTitle()) tells the user where they
     * landed without moving them there; this completes it. Sighted users
     * only get the scroll reset, never a focus jump.
     */
    private void focusFirstRow() {
        final View root = getView();
        final Activity activity = getActivity();
        if (root == null || activity == null) {
            return;
        }
        final View list = root.findViewById(androidx.preference.R.id.recycler_view);
        if (!(list instanceof androidx.recyclerview.widget.RecyclerView)) {
            return;
        }
        final androidx.recyclerview.widget.RecyclerView recycler =
                (androidx.recyclerview.widget.RecyclerView) list;
        recycler.scrollToPosition(0);
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager)
                        activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            return;
        }
        recycler.post(new Runnable() {
            @Override public void run() {
                try {
                    View targetRow = null;
                    for (int i = 0; i < recycler.getChildCount(); i++) {
                        View child = recycler.getChildAt(i);
                        if (child != null && child.getVisibility() == View.VISIBLE && child.getHeight() > 0) {
                            targetRow = child;
                            break;
                        }
                    }
                    if (targetRow == null) {
                        androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                                recycler.findViewHolderForAdapterPosition(0);
                        targetRow = holder != null ? holder.itemView : null;
                    }
                    if (targetRow != null) {
                        targetRow.performAccessibilityAction(
                                android.view.accessibility.AccessibilityNodeInfo
                                        .ACTION_ACCESSIBILITY_FOCUS, null);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Scrolls the current screen's list to the row for {@code key} and
     * moves TalkBack focus to it. Used after search navigation so the
     * user lands on the match, not the top of its screen.
     */
    private void scrollToKey(final String key) {
        final View root = getView();
        if (root == null || key == null) {
            return;
        }
        final View list = root.findViewById(androidx.preference.R.id.recycler_view);
        if (!(list instanceof androidx.recyclerview.widget.RecyclerView)) {
            return;
        }
        final androidx.recyclerview.widget.RecyclerView recycler =
                (androidx.recyclerview.widget.RecyclerView) list;
        final androidx.recyclerview.widget.RecyclerView.Adapter<?> adapter = recycler.getAdapter();
        if (!(adapter instanceof androidx.preference.PreferenceGroupAdapter)) {
            return;
        }
        final androidx.preference.PreferenceGroupAdapter group =
                (androidx.preference.PreferenceGroupAdapter) adapter;
        int position = -1;
        for (int i = 0; i < group.getItemCount(); i++) {
            try {
                Preference item = group.getItem(i);
                if (item != null && key.equals(item.getKey())) {
                    position = i;
                    break;
                }
            } catch (Exception ignored) {
                break;
            }
        }
        if (position < 0) {
            return;
        }
        recycler.scrollToPosition(position);
        final int target = position;
        recycler.post(new Runnable() {
            @Override public void run() {
                try {
                    androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                            recycler.findViewHolderForAdapterPosition(target);
                    if (holder != null) {
                        holder.itemView.performAccessibilityAction(
                                android.view.accessibility.AccessibilityNodeInfo
                                        .ACTION_ACCESSIBILITY_FOCUS, null);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    // Settings search: rows are tagged with this prefix so a tap can be
    // told apart from a real preference click in onPreferenceTreeClick.
    private static final String SEARCH_RESULT_PREFIX = "search_result::";
    private static final int SEARCH_MAX_RESULTS = 50;

    /** The full inflated tree; search always runs against this, not the showing screen. */
    private PreferenceScreen mRootScreen;
    private final List<SearchEntry> mSearchIndex = new ArrayList<SearchEntry>();
    /** Target key -> chain of sub-screen keys from the root. */
    private final Map<String, List<String>> mSearchChains = new HashMap<String, List<String>>();
    /** Non-null while the results screen is showing. */
    private PreferenceScreen mSearchResults;

    private static final class SearchEntry {
        final String key;
        final String title;
        final String detail;
        final List<String> chain;

        SearchEntry(String key, String title, String detail, List<String> chain) {
            this.key = key;
            this.title = title;
            this.detail = detail;
            this.chain = chain;
        }
    }

    /** Called once the engine-backed tree exists; indexes every titled row. */
    void onRootScreenReady(PreferenceScreen root) {
        mRootScreen = root;
        mSearchIndex.clear();
        mSearchChains.clear();
        if (root != null) {
            walkSearchTree(root, new ArrayList<String>(), root.getTitle());
        }
    }

    private void walkSearchTree(androidx.preference.PreferenceGroup group,
            List<String> chain, CharSequence crumb) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference p = group.getPreference(i);
            if (p == null || !p.isVisible()) {
                continue;
            }
            if (!(p instanceof PreferenceScreen) && p instanceof androidx.preference.PreferenceGroup) {
                // A category: its rows belong to the page it is on.
                walkSearchTree((androidx.preference.PreferenceGroup) p, chain, crumb);
                continue;
            }
            if (p.getTitle() == null) {
                continue;
            }
            if (p instanceof PreferenceScreen) {
                final List<String> sub = new ArrayList<String>(chain);
                sub.add(p.getKey());
                final String detail = crumb != null ? crumb.toString() : "";
                mSearchIndex.add(new SearchEntry(p.getKey(), p.getTitle().toString(), detail, sub));
                mSearchChains.put(p.getKey(), sub);
                walkSearchTree((PreferenceScreen) p, sub, p.getTitle());
            } else {
                if (p.getKey() == null || p.getKey().startsWith(SEARCH_RESULT_PREFIX)) {
                    continue;
                }
                CharSequence summary = p.getSummary();
                final String detail = (summary != null ? summary.toString() + " • " : "")
                        + (crumb != null ? crumb.toString() : "");
                mSearchIndex.add(new SearchEntry(p.getKey(), p.getTitle().toString(), detail, chain));
                if (!mSearchChains.containsKey(p.getKey())) {
                    mSearchChains.put(p.getKey(), chain);
                }
            }
        }
    }

    /**
     * Filters the whole tree for {@code query} and shows the matches.
     * Returns true when the query was consumed (including "not ready").
     */
    public boolean setSearchQuery(String query) {
        final String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            clearSearchQuery();
            return true;
        }
        if (mRootScreen == null || !isAdded()) {
            final Activity activity = getActivity();
            if (activity != null) {
                Toast.makeText(activity, R.string.search_not_ready, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        // Reindex every query: rows reveal and hide as settings change
        // (e.g. the custom intonation group), and the index is ~60 rows.
        onRootScreenReady(mRootScreen);
        final List<SearchEntry> matches = new ArrayList<SearchEntry>();
        for (SearchEntry e : mSearchIndex) {
            if (matches.size() >= SEARCH_MAX_RESULTS) {
                break;
            }
            final String haystack = (e.title + "\n" + e.detail).toLowerCase(Locale.ROOT);
            if (haystack.contains(q)) {
                matches.add(e);
            }
        }
        final PreferenceScreen current = getPreferenceScreen();
        if (mSearchResults == null && current != null) {
            mScreenStack.push(current);
        }
        final PreferenceScreen results =
                getPreferenceManager().createPreferenceScreen(requireContext());
        results.setTitle(getString(R.string.search_results_title)
                + " (" + matches.size() + ")");
        if (matches.isEmpty()) {
            final Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.search_no_match);
            empty.setSelectable(false);
            results.addPreference(empty);
        } else {
            for (SearchEntry e : matches) {
                final Preference row = new Preference(requireContext());
                row.setKey(SEARCH_RESULT_PREFIX + e.key);
                row.setTitle(e.title);
                row.setSummary(e.detail);
                results.addPreference(row);
            }
        }
        mSearchResults = results;
        setPreferenceScreen(results);
        updateTitle();
        return true;
    }

    /** Leaves the results screen, returning to the screen search replaced. */
    public void clearSearchQuery() {
        if (mSearchResults == null) {
            return;
        }
        mSearchResults = null;
        popToParent();
    }

    /** Follows one search result to its row: root, down its chain, scroll. */
    private void navigateToSearchResult(String targetKey) {
        final List<String> chain = mSearchChains.get(targetKey);
        mSearchResults = null;
        // Silent reset, not popToParent(): each pop would announce and
        // refocus, so following one result would chatter through every
        // screen on the way back to the root.
        PreferenceScreen current = getPreferenceScreen();
        PreferenceScreen parent;
        while ((parent = mScreenStack.poll()) != null) {
            current = parent;
        }
        if (current == null || chain == null) {
            return;
        }
        for (String screenKey : chain) {
            final Preference next = current.findPreference(screenKey);
            if (!(next instanceof PreferenceScreen)) {
                return;
            }
            mScreenStack.push(current);
            current = (PreferenceScreen) next;
        }
        setPreferenceScreen(current);
        updateTitle();
        // The list binds the new screen on a posted message; scroll after it.
        final View view = getView();
        if (view != null) {
            view.post(() -> scrollToKey(targetKey));
        }
    }

    private void updateTitle() {
        Activity activity = getActivity();
        if (activity instanceof AppCompatActivity) {
            androidx.appcompat.app.ActionBar actionBar =
                    ((AppCompatActivity) activity).getSupportActionBar();
            if (actionBar != null) {
                PreferenceScreen screen = getPreferenceScreen();
                CharSequence title;
                if (mScreenStack.isEmpty() || screen == null || screen.getTitle() == null) {
                    title = getString(R.string.app_name);
                } else {
                    title = screen.getTitle();
                }
                actionBar.setTitle(title);
                actionBar.setDisplayHomeAsUpEnabled(
                        !mScreenStack.isEmpty() || !TtsSettingsActivity.openedFromLauncher(activity));
                View list = getView() != null
                        ? getView().findViewById(androidx.preference.R.id.recycler_view)
                        : null;
                if (list != null && title != null) {
                    androidx.core.view.ViewCompat.setAccessibilityPaneTitle(list, title);
                }
                // Re-rooting the fragment swaps the whole list silently;
                // announce the new screen name so TalkBack users hear
                // where they landed instead of only seeing the title bar
                // change under them.
                announceScreenTitle(title);
            }
        }
    }

    /**
     * announceForAccessibility was deprecated in Android 16; these one-shot
     * polite announcements for explicit navigation are the non-disruptive
     * case (same rationale as SupportedLanguagesDialogFragment).
     */
    @SuppressWarnings("deprecation")
    private void announceScreenTitle(CharSequence title) {
        announce(title);
    }

    /** One-shot polite announcement through the preference list view. */
    @SuppressWarnings("deprecation")
    public void announce(CharSequence text) {
        View list = getView() != null
                ? getView().findViewById(androidx.preference.R.id.recycler_view)
                : null;
        if (list != null && text != null) {
            list.announceForAccessibility(text);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // The Voice preset tile or Tasker may have switched presets while
        // this screen was in the background: "In use" follows.
        final PreferenceScreen shown = getPreferenceScreen();
        if (shown != null && getContext() != null) {
            VoicePresets.refresh(getContext(), shown, TtsSettingsActivity.getPrefs());
        }
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof VoiceVariantPreference) {
            showDialogTargeted(VoiceVariantDialogFragment.newInstance(preference.getKey()));
            return;
        }
        if (preference instanceof SpeakPunctuationPreference) {
            showDialogTargeted(SpeakPunctuationDialogFragment.newInstance(preference.getKey()));
            return;
        }
        if (preference instanceof SeekBarPreference) {
            showDialogTargeted(SeekBarDialogFragment.newInstance(preference.getKey()));
            return;
        }
        if (preference instanceof SupportedLanguagesPreference) {
            showDialogTargeted(SupportedLanguagesDialogFragment.newInstance(preference.getKey()));
            return;
        }
        super.onDisplayPreferenceDialog(preference);
    }

    // Single choke point for the custom dialogs above. setTargetFragment
    // is deprecated in general (see the comment on this fragment), but it
    // is the documented mechanism for PreferenceDialogFragmentCompat,
    // which resolves its preference through the target.
    @SuppressWarnings("deprecation")
    private void showDialogTargeted(DialogFragment fragment) {
        fragment.setTargetFragment(this, 0);
        fragment.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setDivider(null);
        // AndroidX hosts the list in a RecyclerView, not a ListView.
        final View listView = view.findViewById(androidx.preference.R.id.recycler_view);
        if (listView instanceof androidx.recyclerview.widget.RecyclerView) {
            androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) listView;
            rv.setClipToPadding(false);
            rv.setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.ios_grouped_background));
            rv.addItemDecoration(new com.animeshahilya.espeakng.ui.preference.InsetGroupedItemDecoration(requireContext()));
        } else if (listView instanceof ViewGroup) {
            ((ViewGroup) listView).setClipToPadding(false);
        }
        final Context context = getActivity();
        final View.OnApplyWindowInsetsListener insetsListener = new View.OnApplyWindowInsetsListener() {
            // getSystemWindowInset* was deprecated in API 30; kept for the
            // pre-R branch, which has no WindowInsets.Type API. Content is
            // edge-to-edge (fitsSystemWindows=false on android.R.id.content),
            // and ActionBarOverlayLayout leaves the list at y=0 under the
            // status bar and the overlaid action bar - pad top past both so
            // the first row is reachable, bottom past the nav bar so the
            // last row can scroll clear of it. Action-bar height comes from
            // the laid-out container: resolving android.R.attr.actionBarSize
            // against the M3 theme over-reports here and leaves a dead gap.
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int topInset = 0;
                int bottomInset = 0;
                // Landscape: the navigation bar and the camera cutout sit
                // at the sides. The action bar already avoids them; without
                // these the list ran under the navigation bar (switches
                // half hidden) and out of line with the title.
                int leftInset = 0;
                int rightInset = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
                    bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    final android.graphics.Insets sides = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    leftInset = sides.left;
                    rightInset = sides.right;
                } else {
                    topInset = insets.getSystemWindowInsetTop();
                    bottomInset = insets.getSystemWindowInsetBottom();
                    leftInset = insets.getSystemWindowInsetLeft();
                    rightInset = insets.getSystemWindowInsetRight();
                }
                int actionBarHeight = 0;
                Activity activity = getActivity();
                if (activity instanceof AppCompatActivity
                        && ((AppCompatActivity) activity).getSupportActionBar() != null) {
                    View abContainer = activity.findViewById(
                            androidx.appcompat.R.id.action_bar_container);
                    if (abContainer != null && abContainer.getHeight() > 0) {
                        actionBarHeight = abContainer.getHeight();
                    }
                }
                if (listView != null && context != null) {
                    listView.setPadding(
                            leftInset,
                            topInset + actionBarHeight,
                            rightInset,
                            bottomInset + TtsSettingsActivity.dpToPx(context, 16)
                    );
                }
                return insets;
            }
        };
        view.setOnApplyWindowInsetsListener(insetsListener);
        if (listView != null) {
            listView.setOnApplyWindowInsetsListener(insetsListener);
        }
        view.requestApplyInsets();
        // The action bar may still be 0-tall on the first insets pass;
        // re-apply once it has measured so top padding clears it.
        final View abContainer = getActivity() != null
                ? getActivity().findViewById(androidx.appcompat.R.id.action_bar_container)
                : null;
        if (abContainer != null) {
            // Its top is the status bar's height (the insets the list
            // gets already include the action bar).
            abContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                    backStatusBar(getActivity(), v, t));
            if (abContainer.getHeight() > 0) {
                backStatusBar(getActivity(), abContainer, abContainer.getTop());
            }
            abContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    if (b - t > 0) {
                        v.removeOnLayoutChangeListener(this);
                        view.requestApplyInsets();
                    }
                }
            });
        }
    }

    private static final String STATUS_BAR_BACKING = "status_bar_backing";

    /**
     * Paints the status bar strip with the action bar's background. The
     * window is edge-to-edge (enforced from targetSdk 35, where
     * statusBarColor is ignored) and the list scrolls under its top padding,
     * so rows scrolled up showed through above the action bar.
     */
    private static void backStatusBar(Activity activity, View actionBar, int height) {
        if (activity == null) return;
        final View decorView = activity.getWindow().getDecorView();
        if (!(decorView instanceof android.widget.FrameLayout)
                || actionBar.getBackground() == null) {
            return;
        }
        final android.widget.FrameLayout decor = (android.widget.FrameLayout) decorView;
        // Insets are being dispatched: change the hierarchy after the pass.
        decor.post(() -> {
            View strip = decor.findViewWithTag(STATUS_BAR_BACKING);
            if (strip == null) {
                strip = new View(activity) {
                    // AppCompat's action bar background has no constant
                    // state to copy; draw its top (a flat colour) instead.
                    @Override
                    protected void onDraw(android.graphics.Canvas canvas) {
                        final android.graphics.drawable.Drawable bg = actionBar.getBackground();
                        if (bg != null) bg.draw(canvas);
                    }
                };
                strip.setWillNotDraw(false);
                strip.setTag(STATUS_BAR_BACKING);
                strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                decor.addView(strip, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, height, android.view.Gravity.TOP));
            } else if (strip.getLayoutParams().height != height) {
                strip.getLayoutParams().height = height;
                strip.requestLayout();
            }
        });
    }
}

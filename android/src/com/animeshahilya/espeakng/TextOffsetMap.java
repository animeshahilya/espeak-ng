/*
 * Copyright (C) 2026 eSpeak NG contributors
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

package com.animeshahilya.espeakng;

/**
 * Maps UTF-16 boundary offsets in a transformed string back to offsets in an
 * earlier version of the text, so that word-boundary events reported for the
 * text actually handed to the synthesizer can be translated back to offsets
 * in the text the caller originally supplied.
 *
 * <p>{@link TtsService#onSynthesizeText} runs a chain of preprocessing steps
 * (user-dictionary replacement, NATO spelling expansion, programming-symbol
 * expansion, Indian-number formatting, digit separation, Unicode
 * normalization, emoji handling, ...), several of which can change the
 * length of the text. Without compensation, {@code rangeStart()} offsets
 * computed against the final, transformed text point at the wrong place in
 * the original text as soon as any earlier step changed length - e.g. a
 * dictionary rule expanding "AIIMS" into a much longer phrase shifts every
 * word-boundary position reported afterward. Each call to {@link #diff} maps
 * one step's output back to its input; {@link #composeWith} chains that map
 * behind the accumulated map from all earlier steps, so the final map
 * translates all the way back to the original text regardless of how many
 * steps ran.
 */
final class TextOffsetMap {
    /**
     * Boundary map of size {@code newLength + 1}: entry {@code i} is the
     * offset in the previous text corresponding to UTF-16 offset {@code i}
     * in the newer text. Interior positions of a changed run map to the end
     * of the corresponding run in the previous text (matching
     * {@link UnicodeNormalization.Result}'s convention), so mapped ranges
     * are always monotonic and never invert.
     */
    private final int[] mToPrevious;

    private TextOffsetMap(int[] toPrevious) {
        mToPrevious = toPrevious;
    }

    /** Wraps an already-computed boundary map (e.g. from {@link UnicodeNormalization}). */
    static TextOffsetMap fromBoundaryMap(int[] toPrevious) {
        return new TextOffsetMap(toPrevious);
    }

    /** Maps a UTF-16 offset in the newer text to one in the previous text. */
    int toPrevious(int offset) {
        if (offset <= 0) {
            return 0;
        }
        if (offset >= mToPrevious.length) {
            return mToPrevious[mToPrevious.length - 1];
        }
        return mToPrevious[offset];
    }

    /**
     * Returns a map from this (newer) map's domain straight back through
     * {@code older}'s domain - i.e. {@code result.toPrevious(x) ==
     * older.toPrevious(this.toPrevious(x))}. Pass the accumulated map from
     * every earlier step as {@code older} to fold a new step into the chain.
     */
    TextOffsetMap composeWith(TextOffsetMap older) {
        if (older == null) {
            return this;
        }
        final int[] composed = new int[mToPrevious.length];
        for (int i = 0; i < mToPrevious.length; i++) {
            composed[i] = older.toPrevious(mToPrevious[i]);
        }
        return new TextOffsetMap(composed);
    }

    /**
     * Above this many oldLength*newLength character-pairs, the exact O(n*m)
     * diff is skipped in favour of the cheap prefix/suffix fallback, to
     * bound worst-case cost on pathologically long input. Ordinary
     * clause-sized TTS text stays far under this.
     */
    private static final long MAX_DP_CELLS = 400_000L;

    /**
     * Builds a map from {@code after} back to {@code before}, given both
     * strings. Diffs at the character level using a longest-common-
     * subsequence alignment, so multiple disjoint edits in the same call
     * (e.g. two separate dictionary rules firing in one sentence) are each
     * tracked precisely rather than collapsed into one region. Falls back
     * to a coarse common-prefix/suffix trim - a single changed region -
     * for inputs too large to diff exactly.
     */
    static TextOffsetMap diff(String before, String after) {
        final int n = before.length();
        final int m = after.length();

        int prefix = 0;
        final int maxPrefix = Math.min(n, m);
        while (prefix < maxPrefix && before.charAt(prefix) == after.charAt(prefix)) {
            prefix++;
        }

        int suffix = 0;
        final int maxSuffix = Math.min(n, m) - prefix;
        while (suffix < maxSuffix
                && before.charAt(n - 1 - suffix) == after.charAt(m - 1 - suffix)) {
            suffix++;
        }

        final int[] offsets = new int[m + 1];
        for (int k = 0; k <= prefix; k++) {
            offsets[k] = k;
        }

        final int nSlice = n - suffix - prefix;
        final int mSlice = m - suffix - prefix;

        // Bound both the DP table area and the individual dimensions.
        // A slice like 1 x 400000 would pass the product check but allocate
        // 400000 arrays. Cap each dimension at 2000 (2000^2 = 4M > MAX_DP_CELLS,
        // but the product check will catch the extreme cases).
        final int MAX_DIM = 2000;
        if ((long) nSlice * (long) mSlice > MAX_DP_CELLS
                || nSlice > MAX_DIM
                || mSlice > MAX_DIM) {
            fillUnmatchedRun(offsets, prefix, m - suffix, prefix, n - suffix);
        } else if (nSlice > 0 && mSlice > 0) {
            // Suffix-based LCS length table for the changed middle slice only.
            // Suffix-first so alignment takes the earliest valid match on ties.
            final int[][] dp = new int[nSlice + 1][mSlice + 1];
            for (int i = nSlice - 1; i >= 0; i--) {
                final char bi = before.charAt(prefix + i);
                final int[] dpRow = dp[i];
                final int[] nextRow = dp[i + 1];
                for (int j = mSlice - 1; j >= 0; j--) {
                    if (bi == after.charAt(prefix + j)) {
                        dpRow[j] = nextRow[j + 1] + 1;
                    } else {
                        dpRow[j] = Math.max(nextRow[j], dpRow[j + 1]);
                    }
                }
            }

            int i = 0, j = 0;
            int prevOldEnd = prefix;
            int prevNewEnd = prefix;
            while (i < nSlice && j < mSlice) {
                if (before.charAt(prefix + i) == after.charAt(prefix + j)) {
                    fillUnmatchedRun(offsets, prevNewEnd, prefix + j, prevOldEnd, prefix + i);
                    offsets[prefix + j] = prefix + i;
                    prevOldEnd = prefix + i + 1;
                    prevNewEnd = prefix + j + 1;
                    i++;
                    j++;
                } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                    i++;
                } else {
                    j++;
                }
            }
            fillUnmatchedRun(offsets, prevNewEnd, m - suffix, prevOldEnd, n - suffix);
        } else {
            fillUnmatchedRun(offsets, prefix, m - suffix, prefix, n - suffix);
        }

        for (int k = m - suffix; k <= m; k++) {
            offsets[k] = n - (m - k);
        }

        return new TextOffsetMap(offsets);
    }

    private static void fillUnmatchedRun(int[] offsets, int newStart, int newEndExclusive,
                                          int oldStart, int oldEnd) {
        if (newStart >= newEndExclusive) {
            return;
        }
        offsets[newStart] = oldStart;
        for (int k = newStart + 1; k < newEndExclusive; k++) {
            offsets[k] = oldEnd;
        }
    }

    private static TextOffsetMap diffByPrefixSuffix(String before, String after) {
        final int n = before.length();
        final int m = after.length();
        int prefix = 0;
        final int maxPrefix = Math.min(n, m);
        while (prefix < maxPrefix && before.charAt(prefix) == after.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        final int maxSuffix = Math.min(n, m) - prefix;
        while (suffix < maxSuffix
                && before.charAt(n - 1 - suffix) == after.charAt(m - 1 - suffix)) {
            suffix++;
        }

        final int[] offsets = new int[m + 1];
        for (int k = 0; k <= prefix; k++) {
            offsets[k] = k;
        }
        fillUnmatchedRun(offsets, prefix, m - suffix, prefix, n - suffix);
        for (int k = m - suffix; k <= m; k++) {
            offsets[k] = n - (m - k);
        }
        return new TextOffsetMap(offsets);
    }
}

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emoji announcement by name, using NVDA's CLDR dictionary
 * ({@link NvdaEmojiTable}). NVDA matches dictionary identifiers longest
 * first, so full sequences (ZWJ families, flags, skin-toned emoji, keycaps)
 * resolve to a single name. Glue codepoints with no name of their own -
 * zero-width joiners, variation selectors, lone regional indicators and the
 * keycap mark - are dropped, matching what NVDA's pass-through amounts to
 * audibly (the synthesizer never sees them as words). Emoji newer than the
 * table fall back to raw passthrough, i.e. the pre-CLDR behavior.
 */
public final class NvdaEmoji {

    private static final class Node {
        final Map<Character, Node> next = new HashMap<Character, Node>();
        String name;
    }

    private static final Node ROOT = new Node();

    static {
        List<NvdaEmojiTable.Entry> entries = NvdaEmojiTable.entries();
        for (int i = 0; i < entries.size(); i++) {
            NvdaEmojiTable.Entry entry = entries.get(i);
            Node node = ROOT;
            for (int j = 0; j < entry.id.length(); j++) {
                char c = entry.id.charAt(j);
                Node child = node.next.get(c);
                if (child == null) {
                    child = new Node();
                    node.next.put(c, child);
                }
                node = child;
            }
            node.name = entry.name;
        }
    }

    private NvdaEmoji() {
    }

    private static boolean isGlue(int codePoint) {
        return codePoint == 0x200D
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF)
                || codePoint == 0x20E3;
    }

    /**
     * Replaces the emoji in {@code cluster} with CLDR names, longest match
     * first. Unknown non-glue codepoints pass through raw.
     */
    public static String substitute(String cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return cluster;
        }
        final int len = cluster.length();
        StringBuilder sb = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            Node node = ROOT;
            int j = i;
            String best = null;
            int bestEnd = i;
            while (j < len) {
                node = node.next.get(cluster.charAt(j));
                if (node == null) {
                    break;
                }
                j++;
                if (node.name != null) {
                    best = node.name;
                    bestEnd = j;
                }
            }
            if (best != null) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                sb.append(best);
                i = bestEnd;
                continue;
            }
            int cp = cluster.codePointAt(i);
            int step = Character.charCount(cp);
            if (isGlue(cp)) {
                i += step;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            sb.append(cluster, i, i + step);
            i += step;
        }
        return sb.toString();
    }
}

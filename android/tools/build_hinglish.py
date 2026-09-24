#!/usr/bin/env python3
"""Builds assets/hinglish/hi.tsv, the word list HinglishReader uses.

Source: Google's Dakshina dataset (CC BY-SA 4.0),
https://github.com/google-research-datasets/dakshina - the Hindi
romanization lexicon (hi/lexicons/hi.translit.sampled.train.tsv) plus the
word-aligned romanized sentences (hi/romanized/hi.romanized.rejoined.aligned.tsv),
leaving out the official test sentences so they stay usable for scoring.

English words come from CMUdict: a Latin spelling that is also an English
word ("main", "to", "ho") is flagged 1, and the app only converts those in
a sentence that is mostly Hindi.

Usage: build_hinglish.py DAKSHINA_HI_DIR CMUDICT OUT_TSV [--min-count N]
Output lines: roman<TAB>devanagari<TAB>0|1, sorted.
"""
import argparse
import collections
import re
import sys

LATIN = re.compile(r"^[a-z]+$")
# Devanagari minus the danda and double danda (U+0964/U+0965), so "है।" -> "है".
DEVA = "ऀ-ॣ०-ॿ"
STRIP = re.compile(rf"^[^\w{DEVA}]+|[^\w{DEVA}]+$")

# Chat Hindi the Wikipedia-based data gets wrong or lacks. "main" there is
# mostly में ("in"); in messages it is almost always मैं ("I"). The rest are
# common chat spellings and shortcuts.
CHAT_WORDS = {
    "main": "मैं", "mai": "मैं", "yaar": "यार", "yar": "यार",
    "nhi": "नहीं", "nahin": "नहीं", "kr": "कर", "krna": "करना", "krte": "करते",
    "pta": "पता", "bht": "बहुत", "bohot": "बहुत", "acha": "अच्छा", "accha": "अच्छा",
    "thik": "ठीक", "theek": "ठीक", "abhi": "अभी", "kyu": "क्यों", "kyun": "क्यों",
}


def aligned_sentences(path):
    """Yields [(native, roman), ...] per sentence."""
    sentence = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 2:
                continue
            if parts[0] == "</s>":
                yield sentence
                sentence = []
            else:
                sentence.append((parts[0], parts[1]))
    if sentence:
        yield sentence


def clean(token):
    return STRIP.sub("", token)


def test_keys(hi_dir):
    with open(f"{hi_dir}/romanized/hi.romanized.rejoined.test.roman.txt", encoding="utf-8") as f:
        return {" ".join(line.split()) for line in f}


def english_words(cmudict):
    words = set()
    with open(cmudict, encoding="latin-1") as f:
        for line in f:
            if line.startswith(";;;"):
                continue
            word = line.split(" ", 1)[0].split("(")[0].lower()
            if LATIN.match(word):
                words.add(word)
    return words


def build(hi_dir, cmudict, min_count):
    counts = collections.defaultdict(collections.Counter)
    with open(f"{hi_dir}/lexicons/hi.translit.sampled.train.tsv", encoding="utf-8") as f:
        for line in f:
            native, roman, n = line.rstrip("\n").split("\t")
            roman = roman.lower()
            if LATIN.match(roman):
                counts[roman][native] += int(n)
    held_out = test_keys(hi_dir)
    for sentence in aligned_sentences(f"{hi_dir}/romanized/hi.romanized.rejoined.aligned.tsv"):
        if " ".join(r for _, r in sentence) in held_out:
            continue
        for native, roman in sentence:
            native, roman = clean(native), clean(roman).lower()
            if native and LATIN.match(roman) and re.search(f"[{DEVA}]", native):
                counts[roman][native] += 1
    for roman, native in CHAT_WORDS.items():
        counts[roman] = collections.Counter({native: 1000})
    english = english_words(cmudict)
    rows = []
    for roman, natives in counts.items():
        native, n = natives.most_common(1)[0]
        if sum(natives.values()) < min_count or not native:
            continue
        rows.append((roman, native, "1" if roman in english else "0"))
    return sorted(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("hi_dir")
    ap.add_argument("cmudict")
    ap.add_argument("out")
    ap.add_argument("--min-count", type=int, default=2)
    args = ap.parse_args()
    rows = build(args.hi_dir, args.cmudict, args.min_count)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write("\t".join(row) + "\n")
    print(f"{len(rows)} words ({sum(r[2] == '1' for r in rows)} also English)", file=sys.stderr)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Records piper1-gpl's Lithuanian phonemizer as a fixture for LithuanianPhonemesTest.

piper1-gpl (src/piper/phonemize_lithuanian.py, main branch; not yet in
piper-tts 1.8.0 on PyPI) keeps eSpeak NG's IPA for each word and moves the
stress to the syllable its dictionary names, with one of three pitch-accent
marks. The app's LithuanianPhonemes ports it; this records, for real text,
the eSpeak IPA piper got for every word and piper's final output, so the
JVM test checks the Java port against piper's own code with the same input.

  python -m venv v && v/Scripts/pip install piper-tts==1.8.0
  (copy phonemize_lithuanian.py and lithuanian/ from piper1-gpl into the
   installed piper package; give it a threading.Lock() as ESPEAK_LOCK)
  v/Scripts/python tools/lithuanian_fixture.py CORPUS.json [more.txt ...]

CORPUS.json: voice-eval's corpus/lt.json (words, sentences, ... lists, plus
"article"). Writes test/resources/lithuanian_cases.json:
  {"words": {word: espeak IPA}, "cases": [[sentence, expected IPA], ...]}
"""
import json
import os
import re
import sys

from piper.phonemize_lithuanian import _SENTENCE_SPLIT, _WORD_CLEAN, LithuanianPhonemizer

EXTRA = [
    "Labas, mama.", "Mano mama grįžo.", "Ačiū, tėti!", "Kur tu eini, vaikeli?",
    "Rašyk el. paštu.", "Raidė el ir raidė z.", "Šiandien diena graži.",
    "Vaikai žaidžia kieme.", "Kalbėdamas jis šypsojosi.", "Ar maistas skanus?",
    "Valdyba posėdžiavo: žalias planas patvirtintas.", "1990 m. kovo 11 d.",
]


def main():
    corpus = json.load(open(sys.argv[1], encoding="utf-8"))
    texts = [t for k, v in corpus.items() if isinstance(v, list) for t in v if isinstance(t, str)]
    if isinstance(corpus.get("article"), str):
        texts.append(corpus["article"])
    for path in sys.argv[2:]:
        texts += [l.strip() for l in open(path, encoding="utf-8") if l.strip()]
    texts += EXTRA
    p = LithuanianPhonemizer()
    words, cases = {}, []
    for text in texts:
        for sentence in _SENTENCE_SPLIT.split(text.strip()):
            if not sentence:
                continue
            for token in sentence.split():
                w = _WORD_CLEAN.sub("", token)
                if w:
                    words[w] = p._espeak_word(w)
            cases.append([sentence, p.phonemize_sentence(sentence)])
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "test", "resources",
                       "lithuanian_cases.json")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"words": words, "cases": cases}, f, ensure_ascii=False, indent=0)
    print(len(cases), "cases,", len(words), "words ->", os.path.normpath(out))


if __name__ == "__main__":
    main()

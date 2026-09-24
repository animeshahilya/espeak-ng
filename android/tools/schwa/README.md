# Learned inherent-vowel rules (Bengali, Hindi, Marathi)

Bengali and Hindi drop the inherent vowel in the middle of many words
(bn আপনি "apni", hi कहना "kəhnaː") but not all, and the spelling alone does
not say which. These tools learn eSpeak rules for it from WikiPron: rule
induction over the letter contexts eSpeak rules can express, learned on 80%
of the words and checked on the 20% held out.

Results on the held-out words when the rules were added:

| Language | Words read right | Fixed / broken |
|---|---|---|
| Bengali | 59.0% -> 61.9% | 50 / 12 |
| Hindi | 75.4% -> 77.7% | 154 / 14 |
| Marathi | 73.3% -> 74.7% | 19 / 6 |

Nepali was tried and left out: WikiPron has too few Nepali words (about
600 inherent vowels to learn from), and the rules gained one word on the
held-out set.

## Steps

Put WikiPron's `ben_beng_dhaka_broad.tsv` / `hin_deva_broad.tsv` /
`mar_deva_broad.tsv` in this folder and build eSpeak on the host (`espeak-ng/build`).

**Bengali** (eSpeak keeps every medial inherent vowel, so rules only drop):

1. `python bn_slots.py` labels each inherent vowel eSpeak says KEEP or DROP.
2. `MIN_PREC=0.75 python bn_learn.py 40` learns rules and reports held-out
   accuracy (`bn_rules_learned.json`).

**Hindi, Marathi** (the phoneme table already drops many, so rules correct
both ways; `LANG` is `hi` or `mr`):

1. Build once with schwa deletion off (in `phsource/ph_hindi` /
   `ph_marathi`, phoneme `V`, replace the medial `ChangePhoneme(NULL)` with
   `ChangePhoneme(@)`) and run `python dump.py LANG WIKIPRON.tsv
   LANG_nodel.tsv`; restore the file, rebuild, and dump `LANG_norm.tsv`.
2. `python deva_slots.py LANG WIKIPRON.tsv` labels each inherent vowel with
   what WikiPron wants and what eSpeak does now.
3. `MIN_PREC=0.75 python deva_learn.py LANG 40` learns corrections
   (`LANG_rules_learned.json`).

**All:** `python emit.py bn|hi|mr ../../../dictsource/<lang>_rules
../../../dictsource/<lang>_rules` writes the rules in, each line ending
`// generated`. It is safe to re-run: earlier generated lines are removed
first. Hindi and Marathi "keep" rules emit `@4`, a schwa their phoneme tables
never reduce.

Always compare word-level results before and after on the held-out words
(`score.py` has the scoring helpers), and read some everyday sentences:
WikiPron is a dictionary, so it has few inflected forms.

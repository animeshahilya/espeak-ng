# Learned inherent-vowel rules (Bengali, Hindi)

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

## Steps

Put WikiPron's `ben_beng_dhaka_broad.tsv` / `hin_deva_broad.tsv` in this
folder and build eSpeak on the host (`espeak-ng/build`).

**Bengali** (eSpeak keeps every medial inherent vowel, so rules only drop):

1. `python bn_slots.py` labels each inherent vowel eSpeak says KEEP or DROP.
2. `MIN_PREC=0.75 python bn_learn.py 40` learns rules and reports held-out
   accuracy (`bn_rules_learned.json`).

**Hindi** (the phoneme table already drops many, so rules correct both ways):

1. Build once with schwa deletion off (in `phsource/ph_hindi`, phoneme `V`,
   replace `ChangePhoneme(NULL)` with `ChangePhoneme(@)`), save eSpeak's
   output for every word to `hi_nodel.tsv`; restore the file, rebuild, and
   save `hi_norm.tsv` (word TAB IPA per line).
2. `python hi_slots.py` labels each inherent vowel with what WikiPron wants
   and what eSpeak does now.
3. `MIN_PREC=0.75 python hi_learn.py 40` learns corrections
   (`hi_rules_learned.json`).

**Both:** `python emit.py bn|hi ../../../dictsource/<lang>_rules
../../../dictsource/<lang>_rules` writes the rules in, each line ending
`// generated`. It is safe to re-run: earlier generated lines are removed
first. Hindi "keep" rules emit `@4`, a schwa `ph_hindi` never reduces.

Always compare word-level results before and after on the held-out words
(`score.py` has the scoring helpers), and read some everyday sentences:
WikiPron is a dictionary, so it has few inflected forms.

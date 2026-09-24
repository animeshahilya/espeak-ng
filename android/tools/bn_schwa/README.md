# Bengali inherent-vowel rules (rule induction)

Bengali drops the inherent vowel in the middle of many words (আপনি "apni",
আমরা "amra"), but not all (অনুমতি "onumoti"), and the spelling alone does
not say which. These tools learn eSpeak rules for it from WikiPron instead
of a hand-written rule or a word list.

1. Put WikiPron's `ben_beng_dhaka_broad.tsv` in this folder and build
   eSpeak on the host (`espeak-ng/build`).
2. `python bn_slots.py` labels every inherent vowel eSpeak says as KEEP or
   DROP against WikiPron (`bn_slots.tsv`).
3. `MIN_PREC=0.75 python bn_learn.py 40` learns up to 40 rules on 80% of
   the words and reports held-out accuracy (`bn_rules_learned.json`).
4. `python bn_emit.py ../../../dictsource/bn_rules ../../../dictsource/bn_rules`
   writes them into bn_rules as eSpeak rules (re-runnable), together with
   the hand rule for verb forms.

Always compare word-level results before and after on the held-out words
(`score.py` has the scoring helpers) before committing new rules.

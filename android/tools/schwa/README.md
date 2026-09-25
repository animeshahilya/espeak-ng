# Learned inherent-vowel rules (Bengali, Gujarati, Hindi, Marathi, Nepali, Punjabi)

Indo-Aryan languages drop the inherent vowel in the middle of many words
(bn আপনি "apni", hi कहना "kəhnaː", gu અંગસંગ "angsang", ne हरेक "harek", pa ਪੰਜਾਬ "panjaab")
but not all, and the spelling alone does not say which. These tools learn eSpeak rules for
it from WikiPron: rule induction over the letter contexts eSpeak rules can express, learned
on 80% of the words and checked on the 20% held out.

Results across WikiPron datasets when rules were added:

| Language | Script | Words read right | Notes / Impact |
|---|---|---|---|
| Bengali (`bn`) | Bengali | 59.0% -> 61.9% | 50 fixed / 12 broken (held-out) |
| Gujarati (`gu`) | Gujarati | 77.4% -> 78.4% | PER 6.3% -> 6.1%; +3.3% held-out slot accuracy |
| Hindi (`hi`) | Devanagari | 75.4% -> 77.7% | 154 fixed / 14 broken (held-out) |
| Marathi (`mr`) | Devanagari | 73.3% -> 74.7% | 19 fixed / 6 broken (held-out) |
| Nepali (`ne`) | Devanagari | 64.0% -> 65.0% | PER 11.6% -> 11.2%; 36 false-keep schwas eliminated |
| Punjabi (`pa`) | Gurmukhi | 63.6% -> 64.3% | PER 13.7% -> 13.5%; false keeps cut 81 -> 73 |

## Unified Toolchain

1. **Dump reference & baseline**:
   `python dump_nodel.py LANG WIKIPRON.tsv`
   Generates `LANG_norm.tsv` (normal eSpeak output) and `LANG_nodel.tsv` (no-deletion output
   by temporarily compiling dictionary rules with `@` so inherent vowels are preserved).

2. **Extract slots**:
   `python indic_slots.py LANG WIKIPRON.tsv`
   Extracts inherent-vowel positions and maps whether reference and eSpeak keep or drop each vowel
   (`LANG_slots.tsv`). Supports Devanagari, Gujarati, Gurmukhi, and Bengali scripts.

3. **Learn rules**:
   `MIN_PREC=0.75 python indic_learn.py LANG [MAX_RULES]`
   Greedy rule induction over positional features (`pre3..pre1`, `self`, `post1..post3`)
   with exact characters or character classes (`C`, `V`, `N`, virama). Saves `LANG_rules_learned.json`.

4. **Emit rules to eSpeak**:
   `python emit.py LANG ../../../dictsource/<lang>_rules ../../../dictsource/<lang>_rules`
   Emits the learned rules with `// generated` tags under each consonant group.
   Safe to re-run: existing generated blocks are removed first.

5. **Compile & score**:
   Compile the dictionary via `espeak-ng.exe --compile=LANG --path=build`, then run:
   `python score.py LANG WIKIPRON.tsv`


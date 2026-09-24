# Urdu pronunciations from Hindi spellings

Urdu does not write short vowels, so eSpeak's Urdu rules have to guess them
(کتاب could be "kataab" or "kitaab"). Hindi writes them. `ur_from_hindi.py`
maps Urdu words to their Hindi spelling through Google's Dakshina dataset
(the same Latin-letter typing of each word in both scripts), reads the
Hindi with eSpeak's Hindi, and puts back the Urdu sounds Hindi spelling
loses (ق خ غ ز ف ش). A match is dropped unless both spellings have the
same consonants and the same written long vowels. The result is a block
of entries at the end of `dictsource/ur_list`.

Results on WikiPron's 6,296 Urdu words, when the entries were added:
53.8% -> 57.1% read right (275 fixed, 66 broken). The mapping never saw
WikiPron, but the few filters were tuned while looking at it.

## Steps

1. Get the Urdu and Hindi lexicons from
   [Dakshina](https://github.com/google-research-datasets/dakshina)
   (`ur/lexicons/*.tsv`, `hi/lexicons/*.tsv`).
2. Build eSpeak on the host (`espeak-ng/build`).
3. `python ur_from_hindi.py DAKSHINA_DIR ../../../dictsource/ur_list ../../../dictsource/ur_list`

Re-running replaces the generated block. Words already listed above it are
left out, so a hand correction goes above the block.

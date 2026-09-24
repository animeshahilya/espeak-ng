"""eSpeak IPA for every WikiPron word: dump.py LANG WIKIPRON_TSV OUT (word TAB IPA)."""
import sys, score as S
lang, tsv, out = sys.argv[1:4]
words = []
for line in open(tsv, encoding='utf-8'):
    w = line.split('\t')[0]
    if ' ' not in w and w not in words:
        words.append(w)
got = []
for i in range(0, len(words), 2000):
    got += S.espeak(lang, words[i:i + 2000])
assert len(got) == len(words)
with open(out, 'w', encoding='utf-8') as f:
    for w, g in zip(words, got):
        f.write(f'{w}\t{g}\n')
